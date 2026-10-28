package com.example.demo.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.RegionUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import com.example.demo.mapper.MealDocumentMapper;

/**
 * 저장된 식단표로 만드는 부속 서류. 기준은 docs/MEAL_DOCUMENTS_RULES.md(사용자와 고쳐 가는 문서).
 * 조리계획표·급식운영일지는 엑셀, 보존식 라벨(조·중·석식만)은 PDF. 끼니 시간은 ERP 거래처 정보(ErpAccountClient).
 */
@Service
public class MealDocumentService {
	public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
	static final int DISPOSE_AFTER_DAYS = 7;
	static final int MIN_MENU_ROWS = 6;
	private static final String[] LABEL_RULES = {
			"1. 각 음식별로 약 150g정도를 소독된 기구와 위생장갑을 사용하여 채취한다.",
			"2. 음식간에 혼합되지 않도록 주의한다.",
			"3. 덮개를 사용한다.",
			"4. 냉동고보관(-18℃ 이하)" };

	private final MealDocumentMapper mapper;
	private final ErpAccountClient erp;

	public MealDocumentService(MealDocumentMapper mapper, ErpAccountClient erp) {
		this.mapper = mapper;
		this.erp = erp;
	}

	public record Document(String fileName, String contentType, byte[] bytes) {}

	record Slot(int code, String name, boolean main) {}
	record Menu(String menuId, String name) {}
	record Meal(long serviceId, LocalDate date, int slotCode, BigDecimal servings, List<Menu> menus) {}
	record Item(String name, String spec, BigDecimal qty, String unit) {}
	record Plan(String accountId, String accountName, String title, List<Slot> slots, List<LocalDate> dates,
			Map<String, Meal> meals, Map<String, List<Item>> items, Map<Integer, MealTimeParser.SlotTime> times) {
		Meal meal(LocalDate date, int slotCode) { return meals.get(date + "|" + slotCode); }
		List<Item> items(Meal meal, Menu menu) { return items.getOrDefault(meal.serviceId() + "|" + menu.menuId(), List.of()); }
	}

	public Document build(String type, String accountId, String tableId) {
		Plan plan = load(accountId, tableId);
		return switch (type) {
			case "cooking-plan" -> new Document("조리계획표_" + plan.title() + ".xlsx", XLSX, cookingPlan(plan));
			case "operation-log" -> new Document("급식운영일지_" + plan.title() + ".xlsx", XLSX, operationLog(plan));
			case "retention-label" -> new Document("보존식라벨_" + plan.title() + ".pdf", "application/pdf", retentionLabels(plan));
			default -> throw new IllegalArgumentException("서류 종류를 확인해주세요.");
		};
	}

	Plan load(String accountId, String tableId) {
		if (accountId == null || accountId.isBlank() || tableId == null || tableId.isBlank())
			throw new IllegalArgumentException("거래처와 식단표를 골라주세요.");
		Map<String, Object> key = Map.of("account_id", accountId, "table_id", tableId);
		Map<String, Object> header = mapper.plan(key);
		if (header == null) throw new IllegalArgumentException("식단표를 찾을 수 없습니다.");
		List<Slot> slots = mapper.slots(key).stream()
				.map(r -> new Slot(num(r.get("meal_slot_code")).intValue(), str(r.get("meal_slot_name")), "MAIN".equals(str(r.get("slot_kind")))))
				.toList();
		Map<Long, List<Menu>> menus = new LinkedHashMap<>();
		for (var r : mapper.menus(key))
			menus.computeIfAbsent(num(r.get("meal_service_id")).longValue(), k -> new ArrayList<>())
					.add(new Menu(str(r.get("menu_id")), str(r.get("menu_name"))));
		Map<String, Meal> meals = new LinkedHashMap<>();
		TreeSet<LocalDate> dates = new TreeSet<>();
		for (var r : mapper.services(key)) {
			long id = num(r.get("meal_service_id")).longValue();
			LocalDate date = date(r.get("meal_date"));
			int code = num(r.get("meal_slot_code")).intValue();
			dates.add(date);
			meals.put(date + "|" + code, new Meal(id, date, code, num(r.get("planned_servings")), menus.getOrDefault(id, List.of())));
		}
		Map<String, List<Item>> items = new LinkedHashMap<>();
		for (var r : mapper.requirements(key))
			items.computeIfAbsent(num(r.get("meal_service_id")).longValue() + "|" + str(r.get("menu_id")), k -> new ArrayList<>())
					.add(new Item(str(r.get("item_name")), spec(r), num(r.get("total_required_qty")), str(r.get("base_unit"))));
		String accountName = Objects.toString(header.get("account_name"), accountId);
		String title = accountName + "_" + header.get("table_year") + "년" + header.get("table_month") + "월" + header.get("table_week") + "주";
		return new Plan(accountId, accountName, title.replaceAll("[\\\\/:*?\"<>|\\s]+", ""), slots, List.copyOf(dates), meals, items,
				erp.mealTimes(accountId));
	}

	// ───────────────────────── 조리계획표(엑셀) ─────────────────────────

	byte[] cookingPlan(Plan plan) {
		try (XSSFWorkbook wb = new XSSFWorkbook()) {
			Styles st = new Styles(wb);
			Sheet sh = wb.createSheet("조리계획표");
			int[] widths = { 22, 52, 26, 12 };
			for (int i = 0; i < widths.length; i++) sh.setColumnWidth(i, widths[i] * 256);
			int r = 0;
			for (LocalDate date : plan.dates()) {
				for (Slot slot : plan.slots()) {
					Meal meal = plan.meal(date, slot.code());
					if (meal == null || meal.menus().isEmpty()) continue;
					Row head = sh.createRow(r);
					cell(head, 0, longDate(date), st.title);
					cell(head, 2, slot.name() + servingsText(meal.servings()), st.title);
					sh.addMergedRegion(new CellRangeAddress(r, r, 0, 1));
					sh.addMergedRegion(new CellRangeAddress(r, r, 2, 3));
					r++;
					Row cols = sh.createRow(r++);
					String[] names = { "메뉴명", "품목명", "규격", "총량(Kg)" };
					for (int i = 0; i < names.length; i++) cell(cols, i, names[i], st.header);
					for (Menu menu : meal.menus()) {
						List<Item> items = plan.items(meal, menu);
						int first = r;
						if (items.isEmpty()) {
							Row row = sh.createRow(r++);
							cell(row, 0, menu.name(), st.menu);
							cell(row, 1, "", st.text);
							cell(row, 2, "", st.text);
							cell(row, 3, "", st.text);
						}
						for (Item item : items) {
							Row row = sh.createRow(r++);
							cell(row, 0, r - 1 == first ? menu.name() : "", st.menu);
							cell(row, 1, item.name(), st.text);
							cell(row, 2, item.spec(), st.text);
							writeQty(row.createCell(3), item, st);
						}
						if (r - 1 > first) sh.addMergedRegion(new CellRangeAddress(first, r - 1, 0, 0));
					}
					r++;
				}
			}
			if (r == 0) cell(sh.createRow(0), 0, "저장된 메뉴가 없습니다.", st.text);
			sh.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
			sh.setFitToPage(true);
			sh.getPrintSetup().setFitWidth((short) 1);
			sh.getPrintSetup().setFitHeight((short) 0);
			return bytes(wb);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** 기준단위 g·ml는 kg(L)로, kg·L는 그대로, 그 밖(EA 등)은 단위를 붙인 글자 */
	private static void writeQty(Cell cell, Item item, Styles st) {
		BigDecimal qty = item.qty() == null ? BigDecimal.ZERO : item.qty();
		String unit = item.unit() == null ? "" : item.unit().trim().toLowerCase(Locale.ROOT);
		switch (unit) {
			case "g", "ml" -> { cell.setCellValue(qty.movePointLeft(3).doubleValue()); cell.setCellStyle(st.qty); }
			case "kg", "l" -> { cell.setCellValue(qty.doubleValue()); cell.setCellStyle(st.qty); }
			default -> { cell.setCellValue(qty.stripTrailingZeros().toPlainString() + (item.unit() == null ? "" : item.unit())); cell.setCellStyle(st.textRight); }
		}
	}

	// ───────────────────────── 급식운영일지(엑셀, 하루 한 시트) ─────────────────────────

	private static final int LABEL_COLS = 3, GROUP = 6;

	byte[] operationLog(Plan plan) {
		try (XSSFWorkbook wb = new XSSFWorkbook()) {
			Styles st = new Styles(wb);
			for (LocalDate date : plan.dates()) writeLogSheet(wb, st, plan, date);
			if (plan.dates().isEmpty()) cell(wb.createSheet("급식운영일지").createRow(0), 0, "저장된 식단이 없습니다.", st.text);
			return bytes(wb);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void writeLogSheet(XSSFWorkbook wb, Styles st, Plan plan, LocalDate date) {
		Sheet sh = wb.createSheet(String.format("%02d-%02d(%s)", date.getMonthValue(), date.getDayOfMonth(), weekday(date)));
		List<Slot> slots = plan.slots();
		int lastCol = LABEL_COLS + GROUP * Math.max(1, slots.size()) - 1;
		sh.setColumnWidth(0, 12 * 256);
		sh.setColumnWidth(1, 7 * 256);
		sh.setColumnWidth(2, 8 * 256);
		for (int g = 0; g < slots.size(); g++)
			for (int i = 0; i < GROUP; i++) sh.setColumnWidth(LABEL_COLS + g * GROUP + i, (i < 3 ? 8 : 7) * 256);

		int r = 0;
		cell(sh.createRow(r), 0, "급 식 운 영 일 지", st.bigTitle);
		merge(sh, r, r, 0, lastCol, null);
		sh.getRow(r).setHeightInPoints(30);
		r += 1;
		Row info = sh.createRow(r);
		cell(info, 0, plan.accountName(), st.plain);
		merge(sh, r, r, 0, LABEL_COLS + 2, null);
		cell(info, LABEL_COLS + 3, longDate(date), st.plainRight);
		merge(sh, r, r, LABEL_COLS + 3, lastCol, null);
		r += 2;

		// 메뉴 및 검식평가
		int menuRows = MIN_MENU_ROWS;
		for (Slot slot : slots) {
			Meal meal = plan.meal(date, slot.code());
			if (meal != null) menuRows = Math.max(menuRows, meal.menus().size());
		}
		int top = r;
		Row h1 = sh.createRow(r), h2 = sh.createRow(r + 1);
		fillRow(h1, 0, lastCol, st.header);
		fillRow(h2, 0, lastCol, st.header);
		cell(h1, 0, "메뉴 및 검식평가", st.header);
		for (int g = 0; g < slots.size(); g++) {
			int c = LABEL_COLS + g * GROUP;
			cell(h1, c, slots.get(g).name(), st.header);
			merge(sh, r, r + 1, c, c + 2, st.header);
			cell(h1, c + 3, "검식", st.header);
			merge(sh, r, r, c + 3, c + 5, st.header);
			cell(h2, c + 3, "맛/조화", st.header);
			cell(h2, c + 4, "이물질X", st.header);
			cell(h2, c + 5, "배식", st.header);
		}
		r += 2;
		for (int i = 0; i < menuRows; i++, r++) {
			Row row = sh.createRow(r);
			fillRow(row, 0, lastCol, st.text);
			for (int g = 0; g < slots.size(); g++) {
				int c = LABEL_COLS + g * GROUP;
				Meal meal = plan.meal(date, slots.get(g).code());
				boolean has = meal != null && i < meal.menus().size();
				cell(row, c, has ? meal.menus().get(i).name() : "", st.text);
				merge(sh, r, r, c, c + 2, st.text);
				for (int k = 3; k < 6; k++) cell(row, c + k, has ? "O" : "", st.center);
			}
		}
		merge(sh, top, r - 1, 0, LABEL_COLS - 1, st.header);

		// 보존식 반입시간·폐기일
		Row timeRow = sh.createRow(r), disposeRow = sh.createRow(r + 1);
		fillRow(timeRow, 0, lastCol, st.center);
		fillRow(disposeRow, 0, lastCol, st.center);
		cell(timeRow, 0, "보존식 반입시간", st.header);
		cell(disposeRow, 0, "보존식 폐기일", st.header);
		merge(sh, r, r, 0, LABEL_COLS - 1, st.header);
		merge(sh, r + 1, r + 1, 0, LABEL_COLS - 1, st.header);
		for (int g = 0; g < slots.size(); g++) {
			int c = LABEL_COLS + g * GROUP;
			Meal meal = plan.meal(date, slots.get(g).code());
			cell(timeRow, c, meal == null ? "" : logTime(plan.times().get(slots.get(g).code())), st.center);
			cell(disposeRow, c, meal == null ? "" : longDate(date.plusDays(DISPOSE_AFTER_DAYS)), st.center);
			merge(sh, r, r, c, c + GROUP - 1, st.center);
			merge(sh, r + 1, r + 1, c, c + GROUP - 1, st.center);
		}
		r += 2;

		// 입소자 식수현황: 칸 모양만, 값은 현장이 쓴다(층·식종 식수는 발주 시스템에 없음)
		String[][] kinds = { { "구분", "" }, { "상식", "일반밥" }, { "연식", "죽" }, { "", "미음" }, { "치료식", "당뇨식" }, { "", "저염식" },
				{ "경관식", "" }, { "합계", "" }, { "총 합계", "" } };
		top = r;
		for (int i = 0; i < kinds.length; i++, r++) {
			Row row = sh.createRow(r);
			fillRow(row, 0, lastCol, i == 0 ? st.header : st.center);
			if (i == 0) cell(row, 0, "입소자\n식수현황", st.header);
			cell(row, 1, kinds[i][0], st.header);
			cell(row, 2, kinds[i][1], st.header);
			for (int g = 0; g < slots.size(); g++) {
				int c = LABEL_COLS + g * GROUP;
				if (i == 0) {
					for (int k = 0; k < 4; k++) cell(row, c + k, "층", st.header);
					cell(row, c + 4, "직원", st.header);
					cell(row, c + 5, "특이사항", st.header);
				} else if (i == kinds.length - 1) {
					merge(sh, r, r, c, c + GROUP - 1, st.center);
				}
			}
		}
		merge(sh, top, top, 1, 2, st.header);
		merge(sh, top + 2, top + 3, 1, 1, st.header);
		merge(sh, top + 4, top + 5, 1, 1, st.header);
		for (int i : new int[] { 6, 7, 8 }) merge(sh, top + i, top + i, 1, 2, st.header);
		merge(sh, top, r - 1, 0, 0, st.header);
		Row total = sh.createRow(r);
		fillRow(total, 0, lastCol, st.center);
		cell(total, 0, "총 식수인원", st.header);
		merge(sh, r, r, 0, LABEL_COLS - 1, st.header);
		merge(sh, r, r, LABEL_COLS, lastCol, st.center);
		r++;

		// 잔반량·비고(현장 기록)
		Row waste = sh.createRow(r);
		fillRow(waste, 0, lastCol, st.center);
		cell(waste, 0, "일 잔반량", st.header);
		merge(sh, r, r, 0, LABEL_COLS - 1, st.header);
		for (int g = 0; g < slots.size(); g++) {
			int c = LABEL_COLS + g * GROUP;
			cell(waste, c, slots.get(g).name() + " 잔반량", st.small);
			merge(sh, r, r, c, c + GROUP - 1, st.small);
		}
		r++;
		String[] notes = { "식사 취소", "근무 인원      영양사       명     조리사       명     조리원       명",
				"휴무 / 조퇴 / 병가", "영양사 확인" };
		top = r;
		for (String note : notes) {
			Row row = sh.createRow(r);
			fillRow(row, 0, lastCol, st.text);
			cell(row, LABEL_COLS, note, st.small);
			merge(sh, r, r, LABEL_COLS, lastCol, st.small);
			row.setHeightInPoints(24);
			r++;
		}
		cell(sh.getRow(top), 0, "비고", st.header);
		merge(sh, top, r - 1, 0, LABEL_COLS - 1, st.header);

		sh.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
		sh.getPrintSetup().setLandscape(true);
		sh.setFitToPage(true);
		sh.getPrintSetup().setFitWidth((short) 1);
		sh.getPrintSetup().setFitHeight((short) 1);
	}

	/** 운영일지 표기(견본과 같이 24시 + am/pm): 'am 07:00' / 'pm 16:30'. 시간을 못 읽은 값은 ERP 글자 그대로 */
	static String logTime(MealTimeParser.SlotTime time) {
		if (time == null) return "";
		if (time.time() == null) return time.text();
		LocalTime t = time.time();
		return String.format("%s %02d:%02d", t.getHour() < 12 ? "am" : "pm", t.getHour(), t.getMinute());
	}

	// ───────────────────────── 보존식 라벨(PDF, 조·중·석식만) ─────────────────────────

	private static final float PAGE_MARGIN = 24, GAP = 12, LABEL_KEY_W = 58, PAD = 6;

	byte[] retentionLabels(Plan plan) {
		List<Meal> meals = new ArrayList<>();
		List<Slot> slotsOf = new ArrayList<>();
		for (LocalDate date : plan.dates())
			for (Slot slot : plan.slots()) {
				Meal meal = plan.meal(date, slot.code());
				if (slot.main() && meal != null && !meal.menus().isEmpty()) {
					meals.add(meal);
					slotsOf.add(slot);
				}
			}
		try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			PDType0Font regular = font(doc, "NanumGothic-Regular.ttf"), bold = font(doc, "NanumGothic-Bold.ttf");
			PDRectangle a4 = PDRectangle.A4;
			float w = (a4.getWidth() - PAGE_MARGIN * 2 - GAP) / 2, h = (a4.getHeight() - PAGE_MARGIN * 2 - GAP * 2) / 3;
			if (meals.isEmpty()) {
				PDPage page = new PDPage(a4);
				doc.addPage(page);
				try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
					text(cs, regular, 12, PAGE_MARGIN, a4.getHeight() - PAGE_MARGIN - 12, "조·중·석식 식단이 없습니다.");
				}
			}
			for (int start = 0; start < meals.size(); start += 6) {
				PDPage page = new PDPage(a4);
				doc.addPage(page);
				try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
					for (int i = start; i < Math.min(start + 6, meals.size()); i++) {
						int n = i - start;
						float x = PAGE_MARGIN + (n % 2) * (w + GAP);
						float top = a4.getHeight() - PAGE_MARGIN - (n / 2) * (h + GAP);
						drawLabel(cs, regular, bold, x, top, w, h, slotsOf.get(i), meals.get(i), plan.times().get(slotsOf.get(i).code()));
					}
				}
			}
			doc.save(out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void drawLabel(PDPageContentStream cs, PDType0Font regular, PDType0Font bold, float x, float top, float w, float h,
			Slot slot, Meal meal, MealTimeParser.SlotTime time) throws IOException {
		cs.setLineWidth(1f);
		cs.addRect(x, top - h, w, h);
		cs.stroke();
		float titleH = 26;
		centered(cs, bold, 14, x, top - 18, w, slot.name());
		hline(cs, x, top - titleH, w);
		float y = top - titleH;
		float rowH = 20, size = 9.5f, valueX = x + LABEL_KEY_W + PAD, valueW = w - LABEL_KEY_W - PAD * 2;
		String[][] fixed = { { "반입시간", labelTime(time) }, { "채취일", longDate(meal.date()) },
				{ "폐기일", longDate(meal.date().plusDays(DISPOSE_AFTER_DAYS)) }, { "담당자", "" } };
		for (String[] row : fixed) {
			text(cs, bold, size, x + PAD, y - 14, row[0]);
			text(cs, regular, size, valueX, y - 14, row[1]);
			y -= rowH;
			hline(cs, x, y, w);
		}
		List<String> ruleLines = new ArrayList<>();
		for (String rule : LABEL_RULES) ruleLines.addAll(wrap(regular, 8f, rule, valueW));
		float rulesTop = top - h + PAD + ruleLines.size() * 11 + 8;
		text(cs, bold, size, x + PAD, y - 14, "식단");
		// 메뉴가 많으면 글자를 줄여(6pt까지) 칸에 다 넣고, 그래도 넘치면 마지막 줄을 …로 끝낸다
		String menuText = String.join(", ", meal.menus().stream().map(Menu::name).toList());
		float menuSize = size, available = y - 14 - (rulesTop + 4);
		List<String> menuLines = wrap(regular, menuSize, menuText, valueW);
		while (menuSize > 6f && (menuLines.size() - 1) * menuSize * 1.3f > available) {
			menuSize -= 0.5f;
			menuLines = wrap(regular, menuSize, menuText, valueW);
		}
		int fit = Math.max(1, (int) (available / (menuSize * 1.3f)) + 1);
		if (menuLines.size() > fit) {
			menuLines = new ArrayList<>(menuLines.subList(0, fit));
			menuLines.set(fit - 1, menuLines.get(fit - 1) + " …");
		}
		float my = y - 14;
		for (String line : menuLines) {
			text(cs, regular, menuSize, valueX, my, line);
			my -= menuSize * 1.3f;
		}
		hline(cs, x, rulesTop, w);
		text(cs, bold, size, x + PAD, rulesTop - 14, "관리기준");
		float ry = rulesTop - 14;
		for (String line : ruleLines) {
			text(cs, regular, 8f, valueX, ry, line);
			ry -= 11;
		}
		vline(cs, x + LABEL_KEY_W, top - titleH, top - h);
	}

	/** 라벨 표기: '11:45'. 못 읽은 값은 ERP 글자 그대로 */
	static String labelTime(MealTimeParser.SlotTime time) {
		if (time == null) return "";
		return time.time() == null ? time.text() : String.format("%02d:%02d", time.time().getHour(), time.time().getMinute());
	}

	private static PDType0Font font(PDDocument doc, String file) throws IOException {
		try (InputStream in = MealDocumentService.class.getResourceAsStream("/fonts/" + file)) {
			if (in == null) throw new IOException("글꼴 파일이 없습니다: " + file);
			return PDType0Font.load(doc, in);
		}
	}

	/** 글꼴에 없는 글자는 PDF가 만들어지지 않으므로 바꾼다(℃ → °C, 그 밖은 ?) */
	static String printable(PDType0Font font, String text) {
		if (text == null) return "";
		StringBuilder out = new StringBuilder();
		text.replace("℃", "°C").codePoints().forEach(cp -> {
			String ch = new String(Character.toChars(cp));
			try {
				font.encode(ch);
				out.append(ch);
			} catch (IllegalArgumentException | IOException e) {
				out.append(Character.isWhitespace(cp) ? " " : "?");
			}
		});
		return out.toString();
	}

	private static List<String> wrap(PDType0Font font, float size, String raw, float width) throws IOException {
		// 띄어쓰기에서 줄을 바꾸고(메뉴 이름이 중간에 잘리지 않게), 한 낱말이 한 줄보다 길 때만 글자 단위로 자른다
		List<String> lines = new ArrayList<>();
		String line = "";
		for (String word : printable(font, raw).split(" ")) {
			String joined = line.isEmpty() ? word : line + " " + word;
			if (font.getStringWidth(joined) / 1000 * size <= width) {
				line = joined;
				continue;
			}
			if (!line.isEmpty()) lines.add(line);
			line = "";
			for (int i = 0; i < word.length();) {
				String ch = new String(Character.toChars(word.codePointAt(i)));
				if (!line.isEmpty() && font.getStringWidth(line + ch) / 1000 * size > width) {
					lines.add(line);
					line = "";
				}
				line += ch;
				i += ch.length();
			}
		}
		if (!line.isEmpty()) lines.add(line);
		return lines;
	}

	private static void text(PDPageContentStream cs, PDType0Font font, float size, float x, float y, String text) throws IOException {
		if (text == null || text.isEmpty()) return;
		cs.beginText();
		cs.setFont(font, size);
		cs.newLineAtOffset(x, y);
		cs.showText(printable(font, text));
		cs.endText();
	}

	private static void centered(PDPageContentStream cs, PDType0Font font, float size, float x, float y, float w, String text) throws IOException {
		float tw = font.getStringWidth(printable(font, text)) / 1000 * size;
		text(cs, font, size, x + (w - tw) / 2, y, text);
	}

	private static void hline(PDPageContentStream cs, float x, float y, float w) throws IOException {
		cs.moveTo(x, y);
		cs.lineTo(x + w, y);
		cs.stroke();
	}

	private static void vline(PDPageContentStream cs, float x, float fromY, float toY) throws IOException {
		cs.moveTo(x, fromY);
		cs.lineTo(x, toY);
		cs.stroke();
	}

	// ───────────────────────── 공통 ─────────────────────────

	static String longDate(LocalDate date) {
		return date + "(" + weekday(date) + ")";
	}

	static String weekday(LocalDate date) {
		return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
	}

	private static String servingsText(BigDecimal servings) {
		if (servings == null || servings.signum() <= 0) return "";
		return " · 식수 " + servings.stripTrailingZeros().toPlainString() + "명";
	}

	/** 규격: 포장 수량+단위/발주 단위(예 20kg/EA). 최근 입고 상품이 없으면 빈칸 */
	private static String spec(Map<String, Object> r) {
		if (r.get("package_qty") == null) return "";
		String qty = num(r.get("package_qty")).stripTrailingZeros().toPlainString();
		String unit = str(r.get("package_unit")), order = str(r.get("order_unit"));
		return qty + unit + (order.isEmpty() ? "" : "/" + order);
	}

	private static BigDecimal num(Object value) {
		if (value == null) return BigDecimal.ZERO;
		if (value instanceof BigDecimal b) return b;
		return new BigDecimal(value.toString());
	}

	private static String str(Object value) {
		return value == null ? "" : value.toString();
	}

	private static LocalDate date(Object value) {
		if (value instanceof LocalDate d) return d;
		if (value instanceof java.sql.Date d) return d.toLocalDate();
		return LocalDate.parse(value.toString().substring(0, 10));
	}

	private static byte[] bytes(XSSFWorkbook wb) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		wb.write(out);
		return out.toByteArray();
	}

	private static Cell cell(Row row, int col, String value, CellStyle style) {
		Cell cell = row.getCell(col);
		if (cell == null) cell = row.createCell(col);
		cell.setCellValue(value == null ? "" : value);
		cell.setCellStyle(style);
		return cell;
	}

	private static void fillRow(Row row, int from, int to, CellStyle style) {
		for (int c = from; c <= to; c++) cell(row, c, "", style);
	}

	/** 병합 + 테두리(병합 칸 전체). style이 null이면 테두리 없이 병합만 */
	private static void merge(Sheet sh, int r1, int r2, int c1, int c2, CellStyle style) {
		if (r1 == r2 && c1 == c2) return;
		CellRangeAddress range = new CellRangeAddress(r1, r2, c1, c2);
		sh.addMergedRegion(range);
		if (style != null) {
			RegionUtil.setBorderTop(BorderStyle.THIN, range, sh);
			RegionUtil.setBorderBottom(BorderStyle.THIN, range, sh);
			RegionUtil.setBorderLeft(BorderStyle.THIN, range, sh);
			RegionUtil.setBorderRight(BorderStyle.THIN, range, sh);
		}
	}

	private static final class Styles {
		final CellStyle bigTitle, title, header, menu, text, textRight, center, small, qty, plain, plainRight;

		Styles(XSSFWorkbook wb) {
			Font normal = wb.createFont();
			normal.setFontName("맑은 고딕");
			normal.setFontHeightInPoints((short) 10);
			Font boldFont = wb.createFont();
			boldFont.setFontName("맑은 고딕");
			boldFont.setFontHeightInPoints((short) 10);
			boldFont.setBold(true);
			Font big = wb.createFont();
			big.setFontName("맑은 고딕");
			big.setFontHeightInPoints((short) 18);
			big.setBold(true);
			Font smallFont = wb.createFont();
			smallFont.setFontName("맑은 고딕");
			smallFont.setFontHeightInPoints((short) 9);
			smallFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());

			bigTitle = wb.createCellStyle();
			bigTitle.setFont(big);
			bigTitle.setAlignment(HorizontalAlignment.CENTER);
			bigTitle.setVerticalAlignment(VerticalAlignment.CENTER);
			plain = wb.createCellStyle();
			plain.setFont(normal);
			plainRight = wb.createCellStyle();
			plainRight.setFont(normal);
			plainRight.setAlignment(HorizontalAlignment.RIGHT);

			title = boxed(wb, boldFont, HorizontalAlignment.LEFT);
			title.setFillForegroundColor(IndexedColors.LIGHT_TURQUOISE.getIndex());
			title.setFillPattern(FillPatternType.SOLID_FOREGROUND);
			header = boxed(wb, boldFont, HorizontalAlignment.CENTER);
			header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
			header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
			header.setWrapText(true);
			menu = boxed(wb, boldFont, HorizontalAlignment.LEFT);
			text = boxed(wb, normal, HorizontalAlignment.LEFT);
			textRight = boxed(wb, normal, HorizontalAlignment.RIGHT);
			center = boxed(wb, normal, HorizontalAlignment.CENTER);
			small = boxed(wb, smallFont, HorizontalAlignment.LEFT);
			small.setVerticalAlignment(VerticalAlignment.TOP);
			qty = boxed(wb, normal, HorizontalAlignment.RIGHT);
			qty.setDataFormat(wb.createDataFormat().getFormat("0.0##"));
		}

		private static CellStyle boxed(XSSFWorkbook wb, Font font, HorizontalAlignment align) {
			CellStyle s = wb.createCellStyle();
			s.setFont(font);
			s.setAlignment(align);
			s.setVerticalAlignment(VerticalAlignment.CENTER);
			s.setBorderTop(BorderStyle.THIN);
			s.setBorderBottom(BorderStyle.THIN);
			s.setBorderLeft(BorderStyle.THIN);
			s.setBorderRight(BorderStyle.THIN);
			return s;
		}
	}
}
