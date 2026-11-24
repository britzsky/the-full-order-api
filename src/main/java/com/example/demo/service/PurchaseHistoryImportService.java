package com.example.demo.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.example.demo.mapper.PurchaseHistoryMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 공급사 구매 이력 가져오기(2026-11-19). 거래처에 연결된 웰스토리·아워홈 사업장마다 입고일 하루씩 조회해
 * 기존 입고 테이블에 발주서 없이 저장한다. 재고에는 넣지 않는다.
 * <ul>
 * <li>웰스토리 /fdapi/service/payer-receive-detail — 도래미 사이트·엑셀 업로드 주문까지 나오는 정산 기준 입고내역</li>
 * <li>아워홈 /api/chaeum/v1/getOrderList — 사업장 전체 주문 입고 내역(출고완료·삭제 안 된 줄만)</li>
 * </ul>
 * 우리 발주서가 있는 주문(외부 주문번호 = client_ord)은 발주서 입고 흐름이 재고를 맡으므로 입고로 넣지 않고,
 * 공급사 청구 줄(tb_supplier_bill_line)로만 저장해 발주 입고 금액(부가세 추정)과 대조한다(ASP 2026-11-23).
 * 재고 시작일 전 입고 = HISTORY, 후 = UNMATCHED(현장이 공급사 사이트에서 직접 산 것).
 * 같은 기간을 다시 돌려도 공급사 키로 덮어써 중복되지 않는다. 한 번에 한 작업씩(공급사 API 부담) 순서대로 돈다.
 */
@Service
public class PurchaseHistoryImportService {
	private static final Logger log = LoggerFactory.getLogger(PurchaseHistoryImportService.class);
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
	static final int MAX_DAYS = 186;
	private static final int MAX_FAILURES_KEPT = 50;

	private final PurchaseHistoryMapper mapper;
	private final WelstoryItemLookupService welstory;
	private final OurhomeSiteService ourhome;
	private final ObjectMapper json;
	private final long callIntervalMs;
	private PurchaseLedgerService ledger;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "purchase-history-import");
		t.setDaemon(true);
		return t;
	});
	private final Map<String, Job> jobs = new ConcurrentHashMap<>();

	@Autowired
	public PurchaseHistoryImportService(PurchaseHistoryMapper mapper, WelstoryItemLookupService welstory,
			OurhomeSiteService ourhome, ObjectMapper json,
			@Value("${purchase-history.call-interval-ms:300}") long callIntervalMs) {
		this.mapper = mapper;
		this.welstory = welstory;
		this.ourhome = ourhome;
		this.json = json;
		this.callIntervalMs = Math.max(0, callIntervalMs);
	}

	/** 청구 줄을 받은 발주 품목의 입고 금액 대조(없으면 저장만 — 단위 테스트) */
	@Autowired(required = false)
	public void setLedger(PurchaseLedgerService ledger) {
		this.ledger = ledger;
	}

	/** 작업을 줄 세우고 바로 돌려준다. 진행 상황은 job(jobId)로 본다. */
	public Map<String, Object> start(Map<String, Object> command) {
		Job job = prepare(command);
		synchronized (jobs) {
			boolean running = jobs.values().stream().anyMatch(j -> j.accountId.equals(job.accountId) && j.active());
			if (running)
				throw new IllegalArgumentException("이 거래처의 구매 이력 가져오기가 이미 진행 중입니다.");
			jobs.put(job.id, job);
		}
		executor.submit(() -> run(job));
		return job.snapshot();
	}

	/** 같은 스레드에서 끝까지 돈다(테스트·진단용). */
	public Map<String, Object> runNow(Map<String, Object> command) {
		Job job = prepare(command);
		jobs.put(job.id, job);
		run(job);
		return job.snapshot();
	}

	public Map<String, Object> job(String jobId) {
		Job job = jobs.get(jobId);
		if (job == null)
			throw new IllegalArgumentException("가져오기 작업을 찾을 수 없습니다(서버를 다시 시작했으면 다시 실행하세요. 이미 받은 줄은 중복되지 않습니다).");
		return job.snapshot();
	}

	public List<Map<String, Object>> jobs(String accountId) {
		return jobs.values().stream().filter(j -> accountId == null || accountId.isBlank() || j.accountId.equals(accountId))
				.sorted(Comparator.comparing((Job j) -> j.createdAt).reversed()).limit(20).map(Job::snapshot).toList();
	}

	public Map<String, Object> stop(String jobId) {
		Job job = jobs.get(jobId);
		if (job == null)
			throw new IllegalArgumentException("가져오기 작업을 찾을 수 없습니다.");
		job.cancel = true;
		return job.snapshot();
	}

	Job prepare(Map<String, Object> command) {
		String accountId = required(command, "account_id");
		LocalDate from = date(required(command, "from_date"), "from_date");
		LocalDate to = date(required(command, "to_date"), "to_date");
		LocalDate today = LocalDate.now(SEOUL);
		if (to.isAfter(today))
			throw new IllegalArgumentException("끝 날짜는 오늘 이전이어야 합니다.");
		if (from.isAfter(to))
			throw new IllegalArgumentException("시작 날짜가 끝 날짜보다 늦습니다.");
		long days = ChronoUnit.DAYS.between(from, to) + 1;
		if (days > MAX_DAYS)
			throw new IllegalArgumentException("한 번에 " + MAX_DAYS + "일까지 가져올 수 있습니다.");
		Map<String, Object> q = new HashMap<>();
		q.put("account_id", accountId);
		q.put("supplier_code", text(command.get("supplier_code")).toUpperCase(Locale.ROOT));
		List<Map<String, Object>> sites = mapper.importSites(q);
		if (sites.isEmpty())
			throw new IllegalArgumentException("이 거래처에 연결된 웰스토리·아워홈 사업장이 없습니다. 사업장 연결을 먼저 하세요.");
		String start = mapper.inventoryStartDate(Map.of("account_id", accountId));
		return new Job(accountId, from, to, sites, start == null ? null : LocalDate.parse(start),
				text(command.get("user_id")));
	}

	void run(Job job) {
		job.status = "RUNNING";
		job.startedAt = LocalDateTime.now(SEOUL);
		try {
			for (Map<String, Object> site : job.sites) {
				String supplier = text(site.get("supplier_code"));
				String siteCode = text(site.get("external_site_code"));
				OurhomeSiteService.Site ourhomeSite = null;
				if ("OURHOME".equals(supplier)) {
					try {
						waitForSupplierWindow();
						ourhomeSite = ourhome.requireSite(siteCode);
					} catch (RuntimeException e) {
						job.fail(supplier, siteCode, null, "아워홈 사업장 확인 실패: " + message(e));
						job.doneSteps += job.days();
						continue;
					}
				}
				for (LocalDate day = job.from; !day.isAfter(job.to); day = day.plusDays(1)) {
					if (job.cancel) {
						job.status = "STOPPED";
						return;
					}
					job.current = supplier + " " + siteCode + " " + day;
					try {
						waitForSupplierWindow();
						List<Line> lines = "WELSTORY".equals(supplier) ? welstoryLines(job, site, day)
								: ourhomeLines(job, site, ourhomeSite, day);
						save(job, lines);
					} catch (RuntimeException e) {
						job.fail(supplier, siteCode, day, message(e));
					}
					job.doneSteps++;
					pause();
				}
			}
			job.linkedLines = applyKnownLinks(job.accountId);
			if (ledger != null && !job.billedOrderItems.isEmpty()) {
				var checked = ledger.reconcile(job.billedOrderItems);
				job.checkedOrderItems = checked.getOrDefault("checked", 0);
				job.amountDiffItems = checked.getOrDefault("price_diff", 0) + checked.getOrDefault("qty_diff", 0);
			}
			job.status = job.failures.isEmpty() ? "DONE" : "DONE_WITH_ERRORS";
		} catch (RuntimeException e) {
			log.warn("구매 이력 가져오기 중단: account={} {}", job.accountId, e.toString());
			job.status = "FAILED";
			job.fail(null, null, null, message(e));
		} finally {
			job.current = null;
			job.finishedAt = LocalDateTime.now(SEOUL);
		}
	}

	/** 이미 식재료를 고른 상품은 새로 받은 줄에도 같은 식재료·환산을 붙인다. */
	int applyKnownLinks(String accountId) {
		int updated = 0;
		for (Map<String, Object> link : mapper.knownLinks(Map.of("account_id", accountId))) {
			Map<String, Object> p = new HashMap<>(link);
			p.put("account_id", accountId);
			p.put("only_unlinked", true);
			updated += mapper.linkProductLines(p);
		}
		return updated;
	}

	List<Line> welstoryLines(Job job, Map<String, Object> site, LocalDate day) {
		String soldTo = text(site.get("external_site_code"));
		ObjectNode request = json.createObjectNode();
		request.putObject("dataHeader").put("soldTo", soldTo).put("reqDeliveryDate", day.format(BASIC));
		request.putObject("dataBody");
		JsonNode body = welstory.call("/fdapi/service/payer-receive-detail", request).path("dataBody");
		String code = body.path("resCd").asText("");
		String message = body.path("resMsg").asText("");
		if ("EE120".equals(code) || "E1103".equals(code) || message.contains("존재하지 않습니다")
				|| message.contains("조회 결과가 없습니다"))
			return List.of();
		if (!"S0000".equals(code))
			throw new IllegalStateException("웰스토리 " + code + " " + abbreviate(message, 150));
		List<Line> lines = new ArrayList<>();
		Map<String, Integer> seen = new HashMap<>();
		for (JsonNode row : body.path("data")) {
			BigDecimal qty = decimal(first(row, "billQty"));
			if (qty.signum() == 0) {
				job.zeroLines++;
				continue;
			}
			String clientOrd = first(row, "clientOrd");
			boolean ours = ourOrder(job, site, clientOrd);
			LocalDate receiptDate = basicDate(first(row, "billDate"), day);
			String receiptKey = "EXT|" + job.accountId + "|" + soldTo + "|" + receiptDate.format(BASIC) + "|"
					+ (clientOrd.isBlank() ? "-" : clientOrd);
			String item = first(row, "itemCode");
			String base = receiptKey + "|" + first(row, "clientOrdItem") + "|" + item;
			int n = seen.merge(base, 1, Integer::sum);
			Line line = new Line();
			line.supplierCode = "WELSTORY";
			line.site = site;
			line.receiptDate = receiptDate;
			line.receiptKey = receiptKey;
			line.lineKey = abbreviate("W|" + first(row, "clientOrdItem") + "|" + item + "|" + n, 100);
			line.itemCode = item;
			line.itemName = first(row, "itemName", "itemCodeNm");
			line.spec = first(row, "standard");
			line.unit = valueOr(first(row, "unit"), "EA");
			line.orderedQty = decimal(first(row, "ordQty"));
			line.receivedQty = qty;
			line.unitPrice = decimal(first(row, "unitPrice"));
			line.supplyAmount = decimal(first(row, "supplyPrice"));
			if (line.supplyAmount.signum() == 0)
				line.supplyAmount = line.unitPrice.multiply(qty);
			line.vatAmount = decimal(first(row, "vat"));
			line.totalAmount = decimal(first(row, "totAmt"));
			if (line.totalAmount.signum() == 0)
				line.totalAmount = line.supplyAmount.add(line.vatAmount);
			line.taxType = first(row, "taxCode");
			line.traceabilityNo = abbreviate(first(row, "impHisNo"), 100);
			line.traceabilityType = abbreviate(first(row, "foodKind"), 30);
			line.orderType = first(row, "orderType");
			line.clientOrd = clientOrd;
			line.clientOrdItem = first(row, "clientOrdItem");
			line.ourOrder = ours;
			line.raw = row;
			lines.add(line);
		}
		return lines;
	}

	List<Line> ourhomeLines(Job job, Map<String, Object> site, OurhomeSiteService.Site ourhomeSite, LocalDate day) {
		List<JsonNode> rows;
		try {
			rows = ourhome.orderRows(ourhomeSite, day);
		} catch (IllegalStateException e) {
			if (text(e.getMessage()).contains("없습니다"))
				return List.of();
			throw e;
		}
		String siteCode = text(site.get("external_site_code"));
		List<Line> lines = new ArrayList<>();
		Map<String, Integer> seen = new HashMap<>();
		for (JsonNode row : rows) {
			// 삭제된 주문, 아직 출고되지 않은 주문은 산 것이 아니다.
			if ("Y".equalsIgnoreCase(first(row, "delYn")) || !"Y".equalsIgnoreCase(first(row, "prcsStatYn")))
				continue;
			BigDecimal qty = decimal(first(row, "clhaqty"));
			if (qty.signum() == 0) {
				job.zeroLines++;
				continue;
			}
			String extnOrder = first(row, "extnOrderKey");
			boolean ours = ourOrder(job, site, extnOrder);
			LocalDate receiptDate = basicDate(first(row, "depositDt"), day);
			String receiptKey = "EXT|" + job.accountId + "|" + siteCode + "|" + receiptDate.format(BASIC) + "|"
					+ valueOr(first(row, "ordKeyVal"), "-");
			String item = first(row, "goodcd");
			String base = receiptKey + "|" + first(row, "ordSeq") + "|" + first(row, "foSeq") + "|" + item;
			int n = seen.merge(base, 1, Integer::sum);
			Line line = new Line();
			line.supplierCode = "OURHOME";
			line.site = site;
			line.receiptDate = receiptDate;
			line.receiptKey = receiptKey;
			line.lineKey = abbreviate("O|" + first(row, "ordSeq") + "|" + first(row, "foSeq") + "|" + item + "|" + n, 100);
			line.itemCode = item;
			line.itemName = first(row, "goodnm");
			line.spec = first(row, "goodsz");
			line.unit = valueOr(first(row, "odrUnit"), "EA");
			line.orderedQty = qty;
			line.receivedQty = qty;
			line.unitPrice = decimal(first(row, "salsUcost"));
			BigDecimal taxable = decimal(first(row, "txnSplamt")), free = decimal(first(row, "dfSplamt"));
			line.vatAmount = decimal(first(row, "txtnTxamt"));
			line.totalAmount = decimal(first(row, "salsAmt"));
			line.supplyAmount = taxable.add(free);
			if (line.supplyAmount.signum() == 0)
				line.supplyAmount = line.totalAmount.signum() != 0 ? line.totalAmount.subtract(line.vatAmount)
						: line.unitPrice.multiply(qty);
			if (line.totalAmount.signum() == 0)
				line.totalAmount = line.supplyAmount.add(line.vatAmount);
			line.taxType = taxable.signum() != 0 ? "Y" : free.signum() != 0 ? "N" : "";
			line.traceabilityNo = abbreviate(first(row, "idenNo"), 100);
			line.expirationDate = expiration(first(row, "expireInfo"));
			line.orderType = "";
			line.clientOrd = extnOrder;
			line.clientOrdItem = first(row, "extnOrderNo");
			line.ourOrder = ours;
			line.raw = row;
			lines.add(line);
		}
		return lines;
	}

	private boolean ourOrder(Job job, Map<String, Object> site, String clientOrd) {
		if (clientOrd.isBlank())
			return false;
		Map<String, Object> p = new HashMap<>();
		p.put("account_id", job.accountId);
		p.put("supplier_id", site.get("supplier_id"));
		p.put("client_ord", clientOrd);
		if (mapper.purchaseOrderCount(p) == 0)
			return false;
		job.orderLines++;
		return true;
	}

	void save(Job job, List<Line> lines) {
		Set<String> receipts = new LinkedHashSet<>();
		for (Line line : lines) {
			if (line.ourOrder) {
				saveBillLine(job, line);
				continue;
			}
			Map<String, Object> product = product(job, line);
			String receiptId = UUID.nameUUIDFromBytes((job.accountId + "|" + line.site.get("supplier_id") + "|"
					+ line.receiptKey).getBytes(StandardCharsets.UTF_8)).toString();
			Map<String, Object> p = new HashMap<>();
			p.put("goods_receipt_id", receiptId);
			p.put("account_id", job.accountId);
			p.put("supplier_id", line.site.get("supplier_id"));
			p.put("supplier_account_site_id", line.site.get("supplier_account_site_id"));
			p.put("provider_receipt_key", line.receiptKey);
			p.put("receipt_date", line.receiptDate.toString());
			p.put("status", job.statusFor(line.receiptDate));
			p.put("receipt_attributes", json(Map.of("source", "PURCHASE_HISTORY", "supplier_code", line.supplierCode,
					"site_code", text(line.site.get("external_site_code")), "client_ord", line.clientOrd,
					"order_type", line.orderType)));
			if (receipts.add(receiptId))
				mapper.upsertHistoryReceipt(p);
			BigDecimal baseQty = decimal(text(product.get("base_qty")));
			p.put("supplier_product_id", product.get("supplier_product_id"));
			p.put("provider_line_key", line.lineKey);
			p.put("ordered_qty", line.orderedQty);
			p.put("received_qty", line.receivedQty);
			p.put("order_unit", abbreviate(line.unit, 30));
			p.put("base_received_qty", baseQty.signum() > 0 ? line.receivedQty.multiply(baseQty) : line.receivedQty);
			p.put("base_unit", baseQty.signum() > 0 ? text(product.get("base_unit")) : abbreviate(line.unit, 30));
			p.put("unit_price", line.unitPrice);
			p.put("supply_amount", line.supplyAmount);
			p.put("vat_amount", line.vatAmount);
			p.put("total_amount", line.totalAmount);
			p.put("tax_type", taxType(line));
			p.put("traceability_no", blankToNull(line.traceabilityNo));
			p.put("traceability_type", blankToNull(line.traceabilityType));
			p.put("expiration_date", line.expirationDate == null ? null : line.expirationDate.toString());
			p.put("provider_attributes", json(line.raw));
			mapper.upsertHistoryReceiptItem(p);
			job.savedLines++;
		}
		for (String receiptId : receipts)
			mapper.refreshHistoryReceiptTotals(Map.of("goods_receipt_id", receiptId));
	}

	/** 우리 발주 주문의 공급사 청구 줄. 재고·입고에는 넣지 않고 발주 입고 금액 대조에만 쓴다. */
	private void saveBillLine(Job job, Line line) {
		Map<String, Object> p = new HashMap<>();
		p.put("account_id", job.accountId);
		p.put("supplier_id", line.site.get("supplier_id"));
		p.put("supplier_account_site_id", line.site.get("supplier_account_site_id"));
		p.put("client_ord", abbreviate(line.clientOrd, 60));
		p.put("client_ord_item", abbreviate(line.clientOrdItem, 30));
		Map<String, Object> item = line.clientOrdItem.isBlank() ? null : mapper.orderItemForBill(p);
		p.put("purchase_order_id", item == null ? null : item.get("purchase_order_id"));
		p.put("purchase_order_item_id", item == null ? null : item.get("purchase_order_item_id"));
		p.put("provider_line_key", abbreviate(line.receiptKey + "|" + line.lineKey, 150));
		p.put("bill_date", line.receiptDate.toString());
		p.put("supplier_item_code", abbreviate(line.itemCode, 60));
		p.put("billed_qty", line.receivedQty);
		p.put("unit_price", line.unitPrice);
		p.put("supply_amount", line.supplyAmount);
		p.put("vat_amount", line.vatAmount);
		p.put("total_amount", line.totalAmount);
		p.put("tax_type", taxType(line));
		p.put("provider_attributes", json(line.raw));
		mapper.upsertBillLine(p);
		job.billLines++;
		if (item != null && item.get("purchase_order_item_id") != null)
			job.billedOrderItems.add(Long.valueOf(item.get("purchase_order_item_id").toString()));
		else
			job.unlinkedBillLines++;
	}

	/** 공급사 과세 구분, 없으면 부가세가 있으면 과세 */
	static String taxType(Line line) {
		String t = PurchaseAmounts.taxType(line.taxType);
		if (t == null && line.vatAmount != null && line.vatAmount.signum() != 0)
			t = PurchaseAmounts.TAXABLE;
		return t;
	}

	/** 공급사 상품 마스터에서 찾고, 없으면(단종 등) 입고 내역의 이름·규격으로 만든다. */
	private Map<String, Object> product(Job job, Line line) {
		String cacheKey = line.supplierCode + "|" + line.itemCode;
		Map<String, Object> cached = job.products.get(cacheKey);
		if (cached != null)
			return cached;
		if (line.itemCode.isBlank())
			throw new IllegalStateException("상품코드가 없는 입고 줄이 있습니다: " + line.itemName);
		Map<String, Object> key = new HashMap<>();
		key.put("supplier_id", line.site.get("supplier_id"));
		key.put("supplier_item_code", line.itemCode);
		Map<String, Object> product = mapper.productForHistory(key);
		if (product == null) {
			List<QuantityUnits.Pack> packs = "OURHOME".equals(line.supplierCode)
					? QuantityUnits.ourhomePacks(line.unit, line.spec, "")
					: QuantityUnits.packs(line.unit, line.spec);
			if (packs.isEmpty())
				packs = QuantityUnits.packs(line.unit, line.itemName);
			QuantityUnits.Pack main = packs.isEmpty() ? new QuantityUnits.Pack(BigDecimal.ZERO, QuantityUnits.unit(line.unit))
					: packs.get(0);
			QuantityUnits.Pack alt = packs.size() > 1 ? packs.get(1) : null;
			Map<String, Object> p = new HashMap<>(key);
			p.put("product_name", abbreviate(valueOr(line.itemName, line.itemCode), 255));
			p.put("order_unit", abbreviate(line.unit, 30));
			p.put("base_qty", main.quantity());
			p.put("base_unit", abbreviate(main.baseUnit(), 30));
			p.put("alt_base_qty", alt == null ? null : alt.quantity());
			p.put("alt_base_unit", alt == null ? null : alt.baseUnit());
			p.put("tax_type", blankToNull(abbreviate(line.taxType, 20)));
			p.put("user_id", job.userId);
			mapper.insertHistoryProduct(p);
			product = mapper.productForHistory(key);
			if (product == null)
				throw new IllegalStateException("상품을 등록하지 못했습니다: " + line.itemCode);
			job.createdProducts++;
		}
		job.products.put(cacheKey, product);
		return product;
	}

	/** 웰스토리 가이드: 00:00~00:30 호출 불가. 그 시간이면 00:31까지 기다린다. */
	private void waitForSupplierWindow() {
		LocalTime now = LocalTime.now(SEOUL);
		if (now.isBefore(LocalTime.of(0, 31)))
			sleep(java.time.Duration.between(now, LocalTime.of(0, 31)).toMillis());
	}

	private void pause() {
		if (callIntervalMs > 0)
			sleep(callIntervalMs);
	}

	private void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("가져오기가 중단되었습니다.");
		}
	}

	static LocalDate expiration(String value) {
		String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
		if (digits.length() < 8)
			return null;
		try {
			return LocalDate.parse(digits.substring(0, 8), BASIC);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static LocalDate basicDate(String value, LocalDate fallback) {
		String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
		try {
			return digits.length() == 8 ? LocalDate.parse(digits, BASIC) : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	private static LocalDate date(String value, String name) {
		try {
			return LocalDate.parse(value);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException(name + "는 YYYY-MM-DD 형식이어야 합니다.");
		}
	}

	private String json(Object value) {
		try {
			return json.writeValueAsString(value);
		} catch (Exception e) {
			return "{}";
		}
	}

	private static String first(JsonNode node, String... fields) {
		for (String field : fields) {
			String v = node.path(field).asText("").trim();
			if (!v.isBlank())
				return v;
		}
		return "";
	}

	static BigDecimal decimal(String value) {
		try {
			String v = value == null ? "" : value.replaceAll("[^0-9.-]", "");
			return v.isBlank() ? BigDecimal.ZERO : new BigDecimal(v);
		} catch (RuntimeException e) {
			return BigDecimal.ZERO;
		}
	}

	private static String required(Map<String, Object> map, String key) {
		String v = text(map.get(key));
		if (v.isBlank())
			throw new IllegalArgumentException(key + "은 필수입니다.");
		return v;
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString().trim();
	}

	private static String valueOr(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static String abbreviate(String value, int max) {
		String v = value == null ? "" : value;
		return v.length() <= max ? v : v.substring(0, max);
	}

	private static String message(RuntimeException e) {
		return abbreviate(valueOr(e.getMessage(), e.getClass().getSimpleName()), 300);
	}

	/** 공급사 응답 한 줄을 입고 테이블 칸으로 맞춘 것 */
	static final class Line {
		String supplierCode, receiptKey, lineKey, itemCode, itemName, spec, unit, taxType, traceabilityNo,
				traceabilityType, orderType, clientOrd, clientOrdItem = "";
		boolean ourOrder;
		Map<String, Object> site;
		LocalDate receiptDate, expirationDate;
		BigDecimal orderedQty, receivedQty, unitPrice, supplyAmount, vatAmount, totalAmount;
		JsonNode raw;
	}

	static final class Job {
		final String id = UUID.randomUUID().toString();
		final String accountId, userId;
		final LocalDate from, to, inventoryStartDate;
		final List<Map<String, Object>> sites;
		final LocalDateTime createdAt = LocalDateTime.now(SEOUL);
		final Map<String, Map<String, Object>> products = new HashMap<>();
		final Set<Long> billedOrderItems = Collections.synchronizedSet(new LinkedHashSet<>());
		final List<Map<String, Object>> failures = Collections.synchronizedList(new ArrayList<>());
		volatile String status = "QUEUED", current;
		volatile boolean cancel;
		volatile LocalDateTime startedAt, finishedAt;
		volatile int doneSteps, savedLines, orderLines, zeroLines, createdProducts, linkedLines, failureCount, billLines,
				unlinkedBillLines, checkedOrderItems, amountDiffItems;

		Job(String accountId, LocalDate from, LocalDate to, List<Map<String, Object>> sites, LocalDate inventoryStartDate,
				String userId) {
			this.accountId = accountId;
			this.from = from;
			this.to = to;
			this.sites = sites;
			this.inventoryStartDate = inventoryStartDate;
			this.userId = userId;
		}

		int days() {
			return (int) ChronoUnit.DAYS.between(from, to) + 1;
		}

		boolean active() {
			return "QUEUED".equals(status) || "RUNNING".equals(status);
		}

		String statusFor(LocalDate receiptDate) {
			return inventoryStartDate == null || receiptDate.isBefore(inventoryStartDate) ? "HISTORY" : "UNMATCHED";
		}

		void fail(String supplier, String site, LocalDate day, String message) {
			failureCount++;
			if (failures.size() >= MAX_FAILURES_KEPT)
				return;
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("supplier_code", supplier);
			f.put("site_code", site);
			f.put("date", day == null ? null : day.toString());
			f.put("message", message);
			failures.add(f);
		}

		Map<String, Object> snapshot() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("job_id", id);
			m.put("account_id", accountId);
			m.put("from_date", from.toString());
			m.put("to_date", to.toString());
			m.put("inventory_start_date", inventoryStartDate == null ? null : inventoryStartDate.toString());
			m.put("status", status);
			m.put("current", current);
			m.put("site_count", sites.size());
			m.put("total_steps", sites.size() * days());
			m.put("done_steps", doneSteps);
			m.put("saved_line_count", savedLines);
			m.put("skipped_order_line_count", orderLines);
			// ASP: 우리 발주 주문 줄은 입고 대신 공급사 청구 줄로 저장 → 발주 입고 금액 대조
			m.put("bill_line_count", billLines);
			m.put("unlinked_bill_line_count", unlinkedBillLines);
			m.put("checked_order_item_count", checkedOrderItems);
			m.put("amount_diff_item_count", amountDiffItems);
			m.put("zero_qty_line_count", zeroLines);
			m.put("created_product_count", createdProducts);
			m.put("auto_linked_line_count", linkedLines);
			m.put("failure_count", failureCount);
			synchronized (failures) {
				m.put("failures", List.copyOf(failures));
			}
			m.put("created_at", createdAt.toString());
			m.put("started_at", startedAt == null ? null : startedAt.toString());
			m.put("finished_at", finishedAt == null ? null : finishedAt.toString());
			return m;
		}
	}
}
