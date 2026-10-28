package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import com.example.demo.service.MealDocumentService.*;

/** DB·ERP 없이 서류 세 가지를 만들어 본다. 결과 파일은 build/meal-documents에 남겨 눈으로 확인할 수 있게 한다 */
class MealDocumentServiceTest {
	private final MealDocumentService service = new MealDocumentService(null, null);

	private Plan plan() {
		LocalDate mon = LocalDate.of(2026, 10, 26);
		List<Slot> slots = List.of(new Slot(0, "조식", true), new Slot(1, "오전간식", false), new Slot(2, "중식", true),
				new Slot(3, "오후간식", false), new Slot(5, "석식", true));
		Map<String, Meal> meals = new LinkedHashMap<>();
		Map<String, List<Item>> items = new LinkedHashMap<>();
		long id = 1;
		for (int d = 0; d < 2; d++) {
			LocalDate date = mon.plusDays(d);
			meals.put(date + "|0", new Meal(id, date, 0, new BigDecimal("65"), List.of(new Menu("M1", "백미밥/흰죽"),
					new Menu("M2", "순두부백탕"), new Menu("M3", "산적야채조림"), new Menu("M4", "깻잎순나물볶음"), new Menu("M5", "배추김치"))));
			items.put(id + "|M1", List.of(new Item("[C][W]쌀(국산/정성/20kg)", "20kg/EA", new BigDecimal("6500"), "g")));
			items.put(id + "|M3", List.of(new Item("언양불고기산적", "1kg/PK", new BigDecimal("3900"), "g"),
					new Item("피양파(상)", "", new BigDecimal("650"), "g"), new Item("계란", "30EA/판", new BigDecimal("12"), "EA")));
			id++;
			meals.put(date + "|1", new Meal(id++, date, 1, new BigDecimal("65"), List.of(new Menu("S1", "배쥬스"))));
			meals.put(date + "|2", new Meal(id++, date, 2, new BigDecimal("65"), List.of(new Menu("L1", "백미밥/맛살죽"),
					new Menu("L2", "꽃게된장국"), new Menu("L3", "탕수육&소스"), new Menu("L4", "비트채볶음"), new Menu("L5", "열무나물무침"),
					new Menu("L6", "배추김치"), new Menu("L7", "요구르트"))));
			meals.put(date + "|3", new Meal(id++, date, 3, new BigDecimal("65"), List.of(new Menu("S2", "쌀과자/미숫가루"))));
			meals.put(date + "|5", new Meal(id++, date, 5, new BigDecimal("65"), List.of(new Menu("D1", "백미밥/모듬야채죽"),
					new Menu("D2", "맑은미역국"), new Menu("D3", "베이컨그린빈볶음"))));
		}
		Map<Integer, MealTimeParser.SlotTime> times = MealTimeParser.resolve("07:00",
				"*죽,갈찬11:30 /*주간보호11:40  /*요양원11:45", "16:30", "*오전간식(조식때 같이제공) 7:20/ *오후간식14:00");
		return new Plan("A1", "테스트요양원", "테스트요양원_2026년10월4주", slots, List.of(mon, mon.plusDays(1)), meals, items, times);
	}

	@Test
	void buildsCookingPlan() throws Exception {
		byte[] bytes = service.cookingPlan(plan());
		save("조리계획표.xlsx", bytes);
		try (var wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
			Sheet sh = wb.getSheetAt(0);
			assertEquals("2026-10-26(월)", sh.getRow(0).getCell(0).getStringCellValue());
			assertEquals("조식 · 식수 65명", sh.getRow(0).getCell(2).getStringCellValue());
			assertEquals("백미밥/흰죽", sh.getRow(2).getCell(0).getStringCellValue());
			assertEquals(6.5, sh.getRow(2).getCell(3).getNumericCellValue(), 1e-9);
		}
	}

	@Test
	void buildsOperationLogWithAccountSlots() throws Exception {
		byte[] bytes = service.operationLog(plan());
		save("급식운영일지.xlsx", bytes);
		try (var wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
			assertEquals(2, wb.getNumberOfSheets());
			Sheet sh = wb.getSheetAt(0);
			assertEquals("10-26(월)", sh.getSheetName());
			// 끼니 칸은 거래처 식사 구성 순서: 조식·오전간식·중식·오후간식·석식
			assertEquals("오전간식", sh.getRow(3).getCell(3 + 6).getStringCellValue());
			int timeRow = 3 + 2 + 7; // 머리 2줄 + 메뉴 7줄(중식 7개)
			assertEquals("보존식 반입시간", sh.getRow(timeRow).getCell(0).getStringCellValue());
			assertEquals("am 07:00", sh.getRow(timeRow).getCell(3).getStringCellValue());
			assertEquals("am 07:20", sh.getRow(timeRow).getCell(9).getStringCellValue());
			assertEquals("am 11:45", sh.getRow(timeRow).getCell(15).getStringCellValue());
			assertEquals("pm 14:00", sh.getRow(timeRow).getCell(21).getStringCellValue());
			assertEquals("2026-11-02(월)", sh.getRow(timeRow + 1).getCell(3).getStringCellValue());
		}
	}

	@Test
	void buildsRetentionLabelsForMainMealsOnly() throws Exception {
		byte[] bytes = service.retentionLabels(plan());
		save("보존식라벨.pdf", bytes);
		try (PDDocument doc = Loader.loadPDF(bytes)) {
			assertEquals(1, doc.getNumberOfPages()); // 2일 × 조·중·석 = 6장
			String text = new PDFTextStripper().getText(doc);
			assertTrue(text.contains("11:45"));
			assertTrue(text.contains("2026-11-02(월)"));
			assertFalse(text.contains("배쥬스"), "간식 라벨은 만들지 않는다");
		}
		assertEquals(LocalTime.of(11, 45), plan().times().get(MealTimeParser.LUNCH).time());
	}

	private static void save(String name, byte[] bytes) throws Exception {
		Path dir = Path.of("build", "meal-documents");
		Files.createDirectories(dir);
		Files.write(dir.resolve(name), bytes);
	}
}
