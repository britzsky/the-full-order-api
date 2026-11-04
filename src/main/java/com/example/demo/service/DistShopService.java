package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.mapper.DistAdminMapper;
import com.example.demo.mapper.DistShopMapper;
import com.example.demo.security.WorkspaceUser;

/**
 * 더채움몰(2026-11-02 사용자 결정): 본사·현장 누구나 로그인하면 상품을 보고 주문한다. 주문 조회·결제 안내·배송조회·취소는 주문한 사람 본인만.
 * 구매 유형: 거래처 구매(ACCOUNT, 현장 계정만 — 자기 거래처로 고정, 후불 또는 무통장) / 개인 구매(PERSONAL, 누구나 — 무통장 입금, 카드는 PG 정한 뒤).
 * 가격·배송비·세금은 서버가 다시 계산한다(화면 값은 믿지 않음). 주문 시점 상품·가격·주문자·배송지는 주문에 복사, 개인정보는 DistCrypto로 암호화.
 * 후불: 거래처 유통 설정(tb_dist_account_profile)이 없으면 허용(사용자 결정 '후불도 일단 허용'), 있으면 PREPAID·후불 정지·한도 초과일 때 막는다.
 * 배송비: 협력사(출고지)별로 합포장 상품은 가장 비싼 기본 배송비 1번, 따로 포장 상품은 각각. 협력사별 합계가 무료 배송 기준(가장 낮은 값) 이상이면 합포장분 무료.
 */
@Service
public class DistShopService {
	static final Set<String> BUYER_TYPES = Set.of("ACCOUNT", "PERSONAL");
	static final Set<String> CANCEL_REASONS = Set.of("CHANGE_MIND", "WRONG_ORDER", "DELAY", "ETC");
	static final int MAX_LINES = 50;
	private static final BigDecimal VAT_RATE = new BigDecimal("1.1");
	private static final Pattern REQUEST_KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");
	private static final DateTimeFormatter DB_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final DistShopMapper shop;
	private final DistAdminMapper admin;
	private final DistCrypto crypto;

	public DistShopService(DistShopMapper shop, DistAdminMapper admin, DistCrypto crypto) {
		this.shop = shop;
		this.admin = admin;
		this.crypto = crypto;
	}

	/* ===================== 화면 준비 정보 ===================== */

	/** 이 사용자가 고를 수 있는 구매 유형·거래처·후불 가능 여부·입금 계좌·카테고리 */
	public Map<String, Object> context(WorkspaceUser user) {
		Map<String, Object> result = new LinkedHashMap<>();
		boolean site = !user.headquarters();
		result.put("buyer_types", site ? List.of("ACCOUNT", "PERSONAL") : List.of("PERSONAL"));
		result.put("user_id", user.userId());
		if (site) {
			Map<String, Object> key = Map.of("account_id", user.accountId());
			Map<String, Object> account = shop.account(key);
			result.put("account_id", user.accountId());
			result.put("account_name", account == null ? user.accountId() : account.get("account_name"));
			Map<String, Object> profile = shop.accountProfile(key);
			result.put("credit", creditStatus(user.accountId(), profile, BigDecimal.ZERO));
			if (profile != null) {
				Map<String, Object> defaults = new LinkedHashMap<>();
				for (String field : List.of("default_recipient_name", "default_phone", "default_zipcode", "default_address1", "default_address2"))
					defaults.put(field.replace("default_", ""), profile.get(field));
				result.put("account_defaults", defaults);
				result.put("account_order_enabled", !"N".equals(profile.get("order_enabled_yn")));
			} else {
				result.put("account_order_enabled", true);
			}
		}
		Map<String, Object> seller = admin.seller();
		Map<String, Object> deposit = new LinkedHashMap<>();
		if (seller != null && !text(seller.get("deposit_account_no")).isEmpty()) {
			deposit.put("bank_name", seller.get("deposit_bank_name"));
			deposit.put("account_no", seller.get("deposit_account_no"));
			deposit.put("holder", seller.get("deposit_account_holder"));
			deposit.put("due_days", seller.get("deposit_due_days"));
		}
		result.put("deposit", deposit.isEmpty() ? null : deposit);
		result.put("seller_ready", seller != null);
		result.put("order_ready", crypto.configured());
		result.put("categories", shop.categories());
		return result;
	}

	/** 후불 가능 여부. extra = 이번 주문 금액(한도 확인) */
	Map<String, Object> creditStatus(String accountId, Map<String, Object> profile, BigDecimal extra) {
		Map<String, Object> credit = new LinkedHashMap<>();
		String reason = null;
		BigDecimal limit = profile == null ? null : (BigDecimal) profile.get("credit_limit");
		BigDecimal outstanding = shop.outstandingCredit(Map.of("account_id", accountId));
		if (profile != null && "PREPAID".equals(profile.get("payment_terms"))) reason = "이 거래처는 선결제(무통장 입금)만 가능합니다.";
		else if (profile != null && "Y".equals(profile.get("credit_hold_yn"))) reason = "후불 주문이 일시 정지된 거래처입니다. 본사에 문의해주세요.";
		else if (limit != null && outstanding.add(extra).compareTo(limit) > 0)
			reason = "후불 한도(" + plain(limit) + "원)를 넘습니다. 아직 결제되지 않은 후불 금액 " + plain(outstanding) + "원.";
		credit.put("allowed", reason == null);
		credit.put("reason", reason);
		credit.put("limit", limit);
		credit.put("outstanding", outstanding);
		return credit;
	}

	/* ===================== 상품 ===================== */

	public Map<String, Object> products(WorkspaceUser user, Map<String, String> query) {
		Map<String, Object> p = scope(user, query.get("buyer_type"));
		long category = longOrZero(query.get("category_id"));
		p.put("category_id", category > 0 ? category : null);
		p.put("keyword", text(query.get("keyword")));
		List<Map<String, Object>> rows = shop.products(p);
		rows.forEach(DistShopService::decorate);
		return Map.of("products", rows, "buyer_type", p.get("buyer_type"));
	}

	public Map<String, Object> product(WorkspaceUser user, Object productId, String buyerType) {
		Map<String, Object> p = scope(user, buyerType);
		long id = longOrZero(productId);
		if (id <= 0) throw new IllegalArgumentException("상품을 골라주세요.");
		p.put("product_id", id);
		List<Map<String, Object>> rows = shop.products(p);
		if (rows.isEmpty()) throw new IllegalArgumentException("지금 판매하지 않는 상품입니다.");
		Map<String, Object> product = decorate(rows.get(0));
		Map<String, Object> key = Map.of("product_id", id);
		Map<String, Object> content = shop.content(key);
		if (content != null) product.putAll(content);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("product", product);
		result.put("images", shop.images(key));
		return result;
	}

	/** 남은 수량·주문 가능 여부를 붙인다 */
	static Map<String, Object> decorate(Map<String, Object> row) {
		String reason = null;
		BigDecimal remaining = remaining(row);
		if ("SOLD_OUT".equals(row.get("sale_status"))) reason = "품절";
		else if ("UNAVAILABLE".equals(row.get("availability_status"))) reason = "지금은 판매하지 않음";
		else if (remaining != null && remaining.signum() <= 0) reason = "준비 수량 소진";
		row.put("remaining_qty", remaining);
		row.put("orderable", reason == null);
		row.put("unavailable_reason", reason);
		return row;
	}

	private static BigDecimal remaining(Map<String, Object> row) {
		if (row.get("available_qty") == null) return null;
		BigDecimal reserved = row.get("reserved_qty") == null ? BigDecimal.ZERO : (BigDecimal) row.get("reserved_qty");
		return ((BigDecimal) row.get("available_qty")).subtract(reserved).max(BigDecimal.ZERO);
	}

	/** 구매 유형 확인: 거래처 구매는 현장 계정만, 거래처는 로그인 거래처로 고정 */
	Map<String, Object> scope(WorkspaceUser user, String buyerType) {
		String type = text(buyerType).toUpperCase();
		if (type.isEmpty()) type = user.headquarters() ? "PERSONAL" : "ACCOUNT";
		if (!BUYER_TYPES.contains(type)) throw new IllegalArgumentException("구매 유형을 확인해주세요: " + type);
		if ("ACCOUNT".equals(type) && user.headquarters())
			throw new IllegalArgumentException("거래처 구매는 거래처(현장) 계정으로 해주세요. 본사 계정은 개인 구매만 할 수 있습니다.");
		Map<String, Object> p = new HashMap<>();
		p.put("buyer_type", type);
		p.put("account_id", "ACCOUNT".equals(type) ? user.accountId() : null);
		return p;
	}

	/* ===================== 견적·주문 ===================== */

	/** 장바구니 금액 계산(저장하지 않음). body: buyer_type, items[{product_id, qty}] */
	public Map<String, Object> quote(WorkspaceUser user, Map<String, Object> body) {
		Map<String, Object> p = scope(user, text(body.get("buyer_type")));
		Quote quote = price(p, body.get("items"), false);
		Map<String, Object> result = quote.toMap();
		if ("ACCOUNT".equals(p.get("buyer_type")))
			result.put("credit", creditStatus(user.accountId(), shop.accountProfile(Map.of("account_id", user.accountId())), quote.total));
		return result;
	}

	/**
	 * 주문 저장. body: client_request_key, buyer_type, payment_method(BANK_TRANSFER/CREDIT), items, orderer{name,phone,email},
	 * recipient{name,phone,zipcode,address1,address2,delivery_request}, depositor_name, request_note, privacy_agreed(개인 구매)
	 */
	@Transactional
	public Map<String, Object> placeOrder(WorkspaceUser user, Map<String, Object> body) {
		String requestKey = text(body.get("client_request_key"));
		if (!REQUEST_KEY.matcher(requestKey).matches()) throw new IllegalArgumentException("주문 요청 번호가 올바르지 않습니다. 새로고침 후 다시 주문해주세요.");
		Map<String, Object> existing = shop.orderByRequestKey(Map.of("client_request_key", requestKey));
		if (existing != null) {
			if (!user.userId().equals(existing.get("orderer_user_id"))) throw new IllegalArgumentException("이미 사용된 주문 요청 번호입니다. 새로고침 후 다시 주문해주세요.");
			return result("이미 접수된 주문입니다.", existing.get("order_id"), null, true);
		}
		Map<String, Object> p = scope(user, text(body.get("buyer_type")));
		String buyerType = (String) p.get("buyer_type");
		String method = text(body.get("payment_method")).toUpperCase();
		if (method.isEmpty()) method = "BANK_TRANSFER";
		if (!Set.of("BANK_TRANSFER", "CREDIT").contains(method)) throw new IllegalArgumentException("결제 방법을 확인해주세요(카드 결제는 아직 준비 중입니다).");
		if ("CREDIT".equals(method) && !"ACCOUNT".equals(buyerType)) throw new IllegalArgumentException("후불은 거래처 구매에서만 가능합니다.");

		Map<String, Object> seller = admin.seller();
		if (seller == null) throw new IllegalArgumentException("판매 준비가 아직 끝나지 않았습니다(판매 주체 미등록). 본사에 문의해주세요.");
		if ("BANK_TRANSFER".equals(method) && text(seller.get("deposit_account_no")).isEmpty())
			throw new IllegalArgumentException("입금 계좌가 아직 등록되지 않아 무통장 주문을 받을 수 없습니다. 본사에 문의해주세요.");
		if (!crypto.configured()) throw new IllegalStateException("개인정보 암호화 키가 설정되지 않아 주문을 받을 수 없습니다. 관리자에게 알려주세요.");

		Map<String, Object> profile = null;
		if ("ACCOUNT".equals(buyerType)) {
			profile = shop.accountProfile(Map.of("account_id", user.accountId()));
			if (profile != null && "N".equals(profile.get("order_enabled_yn"))) throw new IllegalArgumentException("이 거래처는 더채움몰 주문이 막혀 있습니다. 본사에 문의해주세요.");
		}

		Map<String, Object> orderer = map(body.get("orderer"));
		Map<String, Object> recipient = map(body.get("recipient"));
		String ordererName = required(orderer, "name", "주문자 이름", 50);
		String ordererPhone = phone(required(orderer, "phone", "주문자 연락처", 30), "주문자 연락처");
		String ordererEmail = optional(orderer, "email", 100);
		String recipientName = required(recipient, "name", "받는 분", 50);
		String recipientPhone = phone(required(recipient, "phone", "받는 분 연락처", 30), "받는 분 연락처");
		String zipcode = required(recipient, "zipcode", "우편번호", 10);
		String address1 = required(recipient, "address1", "주소", 255);
		String address2 = optional(recipient, "address2", 255);
		String deliveryRequest = optional(recipient, "delivery_request", 500);
		String depositor = "BANK_TRANSFER".equals(method) ? Objects.requireNonNullElse(optional(body, "depositor_name", 50), ordererName) : null;
		if ("PERSONAL".equals(buyerType) && !Boolean.TRUE.equals(body.get("privacy_agreed")) && !"Y".equals(text(body.get("privacy_agreed"))))
			throw new IllegalArgumentException("개인 구매는 개인정보 수집·이용에 동의해야 주문할 수 있습니다.");

		Quote quote = price(p, body.get("items"), true);
		if ("CREDIT".equals(method)) {
			Map<String, Object> credit = creditStatus(user.accountId(), profile, quote.total);
			if (!Boolean.TRUE.equals(credit.get("allowed"))) throw new IllegalArgumentException((String) credit.get("reason"));
		}

		Long customerId = null;
		if ("PERSONAL".equals(buyerType)) {
			Map<String, Object> customer = new HashMap<>();
			customer.put("login_user_id", user.userId());
			customer.put("account_id", user.headquarters() ? null : user.accountId());
			customer.put("name_enc", crypto.encrypt(ordererName));
			customer.put("name_masked", DistCrypto.maskName(ordererName));
			customer.put("phone_enc", crypto.encrypt(ordererPhone));
			customer.put("phone_hash", crypto.hash(ordererPhone));
			customer.put("phone_masked", DistCrypto.maskPhone(ordererPhone));
			customer.put("email_enc", crypto.encrypt(ordererEmail));
			customerId = shop.customerId(customer);
			if (customerId == null) {
				shop.insertCustomer(customer);
				customerId = ((Number) customer.get("customer_id")).longValue();
			} else {
				customer.put("customer_id", customerId);
				shop.updateCustomer(customer);
			}
		}

		LocalDateTime now = LocalDateTime.parse(admin.dbNow(), DB_TIME);
		Map<String, Object> seq = Map.of("seq_name", "ORDER");
		admin.nextSequence(seq);
		String orderNo = now.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%04d", admin.currentSequence(seq));
		boolean credit = "CREDIT".equals(method);
		String orderStatus = credit ? "PAID" : "PENDING_PAYMENT";
		String paymentStatus = credit ? "CREDIT" : "WAITING";

		Map<String, Object> order = new HashMap<>();
		order.put("order_no", orderNo);
		order.put("client_request_key", requestKey);
		order.put("seller_id", seller.get("seller_id"));
		order.put("buyer_type", buyerType);
		order.put("account_id", "ACCOUNT".equals(buyerType) ? user.accountId() : null);
		order.put("customer_id", customerId);
		order.put("orderer_user_id", user.userId());
		order.put("orderer_name_enc", crypto.encrypt(ordererName));
		order.put("orderer_phone_enc", crypto.encrypt(ordererPhone));
		order.put("orderer_email_enc", crypto.encrypt(ordererEmail));
		order.put("order_status", orderStatus);
		order.put("payment_status", paymentStatus);
		order.put("item_amount", quote.itemAmount);
		order.put("shipping_fee", quote.shippingFee);
		order.put("total_amount", quote.total);
		order.put("taxable_amount", quote.taxable);
		order.put("vat_amount", quote.vat);
		order.put("tax_free_amount", quote.taxFree);
		order.put("receipt_type", "ACCOUNT".equals(buyerType) ? "TAX_INVOICE" : "NONE");
		order.put("request_note", optional(body, "request_note", 1000));
		shop.insertOrder(order);
		long orderId = ((Number) order.get("order_id")).longValue();

		int lineNo = 0;
		for (Line line : quote.lines) {
			Map<String, Object> item = new HashMap<>(line.product);
			item.put("order_id", orderId);
			item.put("line_no", ++lineNo);
			item.put("qty", line.qty);
			item.put("amount", line.amount);
			item.put("unit_cost", shop.currentCost(Map.of("product_id", line.productId)));
			shop.insertItem(item);
			if (line.availabilityId != null) shop.reserve(Map.of("availability_id", line.availabilityId, "qty", line.qty));
		}

		Map<String, Object> rcpt = new HashMap<>();
		rcpt.put("order_id", orderId);
		rcpt.put("recipient_name_enc", crypto.encrypt(recipientName));
		rcpt.put("recipient_phone_enc", crypto.encrypt(recipientPhone));
		rcpt.put("zipcode", zipcode);
		rcpt.put("address1_enc", crypto.encrypt(address1));
		rcpt.put("address2_enc", crypto.encrypt(address2));
		rcpt.put("delivery_request", deliveryRequest);
		rcpt.put("user_id", user.userId());
		shop.insertRecipient(rcpt);

		Map<String, Object> payment = new HashMap<>();
		payment.put("order_id", orderId);
		payment.put("payment_method", method);
		payment.put("status", credit ? "READY" : "WAITING_DEPOSIT");
		payment.put("amount", quote.total);
		payment.put("bank_name", credit ? null : truncate(text(seller.get("deposit_bank_name")), 10));
		payment.put("deposit_account_no", credit ? null : seller.get("deposit_account_no"));
		payment.put("depositor_name", depositor);
		int dueDays = seller.get("deposit_due_days") == null ? 3 : ((Number) seller.get("deposit_due_days")).intValue();
		payment.put("deposit_due_at", credit ? null : now.plusDays(dueDays).withHour(23).withMinute(59).withSecond(59));
		payment.put("user_id", user.userId());
		shop.insertPayment(payment);

		history(orderId, "ORDER", null, orderStatus, credit ? "후불 주문" : "무통장 입금 대기", user.userId());
		return result(credit ? "주문했습니다. 후불로 접수되어 월말 청구서에 포함됩니다." : "주문했습니다. 입금 기한 안에 안내된 계좌로 입금해주세요.", orderId, orderNo, false);
	}

	private static Map<String, Object> result(String message, Object orderId, String orderNo, boolean duplicate) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("code", 200);
		result.put("message", message);
		result.put("order_id", orderId);
		if (orderNo != null) result.put("order_no", orderNo);
		result.put("duplicate", duplicate);
		return result;
	}

	/* ---------- 가격 계산 ---------- */

	static final class Line {
		long productId;
		Map<String, Object> product;
		BigDecimal qty, unitPrice, amount;
		Long availabilityId;
	}

	static final class Quote {
		final List<Line> lines = new ArrayList<>();
		final List<Map<String, Object>> groups = new ArrayList<>();
		BigDecimal itemAmount = BigDecimal.ZERO, shippingFee = BigDecimal.ZERO, total = BigDecimal.ZERO;
		BigDecimal taxable = BigDecimal.ZERO, vat = BigDecimal.ZERO, taxFree = BigDecimal.ZERO;

		Map<String, Object> toMap() {
			List<Map<String, Object>> rows = new ArrayList<>();
			for (Line line : lines) {
				Map<String, Object> row = new LinkedHashMap<>();
				for (String key : List.of("product_id", "product_name", "option_name", "partner_name", "image_url", "sale_unit", "tax_type", "price_type", "min_order_qty", "max_order_qty", "remaining_qty"))
					row.put(key, line.product.get(key));
				row.put("qty", line.qty);
				row.put("unit_price", line.unitPrice);
				row.put("amount", line.amount);
				rows.add(row);
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("lines", rows);
			result.put("groups", groups);
			result.put("item_amount", itemAmount);
			result.put("shipping_fee", shippingFee);
			result.put("total_amount", total);
			result.put("taxable_amount", taxable);
			result.put("vat_amount", vat);
			result.put("tax_free_amount", taxFree);
			return result;
		}
	}

	/** 장바구니 → 상품·가격·수량 확인 + 배송비·세금. lock이면 판매 가능 수량 행을 잠그고 다시 확인 */
	Quote price(Map<String, Object> scope, Object rawItems, boolean lock) {
		if (!(rawItems instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException("주문할 상품을 담아주세요.");
		Map<Long, BigDecimal> wanted = new LinkedHashMap<>();
		for (Object raw : list) {
			Map<String, Object> item = map(raw);
			long id = longOrZero(item.get("product_id"));
			if (id <= 0) throw new IllegalArgumentException("상품 정보를 확인해주세요.");
			BigDecimal qty = decimal(item.get("qty"), "수량");
			if (qty.signum() <= 0 || qty.stripTrailingZeros().scale() > 0) throw new IllegalArgumentException("수량은 1 이상의 정수로 넣어주세요.");
			wanted.merge(id, qty, BigDecimal::add);
		}
		if (wanted.size() > MAX_LINES) throw new IllegalArgumentException("한 번에 " + MAX_LINES + "가지 상품까지 주문할 수 있습니다.");
		Map<String, Object> p = new HashMap<>(scope);
		p.put("product_ids", new ArrayList<>(wanted.keySet()));
		Map<Long, Map<String, Object>> found = new HashMap<>();
		for (Map<String, Object> row : shop.products(p)) found.put(((Number) row.get("product_id")).longValue(), row);

		Quote quote = new Quote();
		for (Map.Entry<Long, BigDecimal> entry : wanted.entrySet()) {
			Map<String, Object> product = found.get(entry.getKey());
			if (product == null) throw new IllegalArgumentException("지금 판매하지 않는 상품이 있습니다. 장바구니를 확인해주세요.");
			String name = text(product.get("product_name")) + (product.get("option_name") == null ? "" : " " + product.get("option_name"));
			if (!"ON_SALE".equals(product.get("sale_status"))) throw new IllegalArgumentException(name + ": 품절입니다.");
			BigDecimal qty = entry.getValue();
			BigDecimal min = (BigDecimal) product.get("min_order_qty"), max = (BigDecimal) product.get("max_order_qty");
			if (min != null && qty.compareTo(min) < 0) throw new IllegalArgumentException(name + ": 최소 " + plain(min) + "개부터 주문할 수 있습니다.");
			if (max != null && qty.compareTo(max) > 0) throw new IllegalArgumentException(name + ": 한 번에 " + plain(max) + "개까지 주문할 수 있습니다.");
			Line line = new Line();
			line.productId = entry.getKey();
			if (lock) {
				Map<String, Object> availability = shop.lockAvailability(Map.of("product_id", line.productId));
				if (availability != null) {
					product.put("availability_status", availability.get("status"));
					product.put("available_qty", availability.get("available_qty"));
					product.put("reserved_qty", availability.get("reserved_qty"));
					if (availability.get("available_qty") != null) line.availabilityId = ((Number) availability.get("availability_id")).longValue();
				}
			}
			decorate(product);
			if ("UNAVAILABLE".equals(product.get("availability_status"))) throw new IllegalArgumentException(name + ": 지금은 판매하지 않습니다.");
			BigDecimal remaining = (BigDecimal) product.get("remaining_qty");
			if (remaining != null && qty.compareTo(remaining) > 0)
				throw new IllegalArgumentException(name + ": 남은 수량이 " + plain(remaining) + "개입니다.");
			line.product = product;
			line.qty = qty;
			line.unitPrice = (BigDecimal) product.get("unit_price");
			line.amount = line.unitPrice.multiply(qty).setScale(0, RoundingMode.HALF_UP);
			quote.lines.add(line);
		}
		shipping(quote);
		for (Line line : quote.lines) {
			if ("TAX_FREE".equals(line.product.get("tax_type"))) quote.taxFree = quote.taxFree.add(line.amount);
			else addTaxable(quote, line.amount);
		}
		addTaxable(quote, quote.shippingFee);
		quote.total = quote.itemAmount.add(quote.shippingFee);
		return quote;
	}

	private static void addTaxable(Quote quote, BigDecimal grossAmount) {
		if (grossAmount.signum() == 0) return;
		BigDecimal supply = grossAmount.divide(VAT_RATE, 0, RoundingMode.HALF_UP);
		quote.taxable = quote.taxable.add(supply);
		quote.vat = quote.vat.add(grossAmount.subtract(supply));
	}

	/** 협력사(출고지)별 배송비. 같은 협력사 합포장 상품은 가장 비싼 배송비 1번, 따로 포장은 각각, 무료 기준 넘으면 합포장분 0 */
	static void shipping(Quote quote) {
		Map<Object, List<Line>> byPartner = new LinkedHashMap<>();
		for (Line line : quote.lines) byPartner.computeIfAbsent(line.product.get("partner_id"), key -> new ArrayList<>()).add(line);
		for (List<Line> group : byPartner.values()) {
			BigDecimal subtotal = BigDecimal.ZERO, bundled = BigDecimal.ZERO, separate = BigDecimal.ZERO, freeAt = null;
			for (Line line : group) {
				subtotal = subtotal.add(line.amount);
				BigDecimal fee = line.product.get("shipping_fee") == null ? BigDecimal.ZERO : (BigDecimal) line.product.get("shipping_fee");
				if ("N".equals(line.product.get("bundle_ship_yn"))) separate = separate.add(fee.multiply(line.qty));
				else bundled = bundled.max(fee);
				BigDecimal free = (BigDecimal) line.product.get("free_shipping_amount");
				if (free != null && (freeAt == null || free.compareTo(freeAt) < 0)) freeAt = free;
			}
			boolean free = freeAt != null && subtotal.compareTo(freeAt) >= 0;
			BigDecimal fee = (free ? BigDecimal.ZERO : bundled).add(separate);
			quote.itemAmount = quote.itemAmount.add(subtotal);
			quote.shippingFee = quote.shippingFee.add(fee);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("partner_id", group.get(0).product.get("partner_id"));
			row.put("partner_name", group.get(0).product.get("partner_name"));
			row.put("subtotal", subtotal);
			row.put("shipping_fee", fee);
			row.put("free_shipping_amount", freeAt);
			row.put("free_shipping_left", freeAt == null || free || bundled.signum() == 0 ? null : freeAt.subtract(subtotal));
			quote.groups.add(row);
		}
	}

	/* ===================== 내 주문 ===================== */

	public Map<String, Object> myOrders(WorkspaceUser user, String status) {
		Map<String, Object> p = new HashMap<>();
		p.put("user_id", user.userId());
		p.put("status", text(status).toUpperCase());
		return Map.of("orders", shop.myOrders(p));
	}

	public Map<String, Object> myOrder(WorkspaceUser user, Object orderId) {
		Map<String, Object> key = new HashMap<>();
		key.put("order_id", longOrZero(orderId));
		key.put("user_id", user.userId());
		Map<String, Object> order = shop.myOrder(key);
		if (order == null) throw new IllegalArgumentException("주문을 찾을 수 없습니다(본인이 주문한 것만 볼 수 있습니다).");
		Map<String, Object> view = new LinkedHashMap<>();
		for (String field : List.of("order_id", "order_no", "buyer_type", "account_id", "account_name", "order_status", "payment_status", "item_amount",
				"shipping_fee", "discount_amount", "total_amount", "taxable_amount", "vat_amount", "tax_free_amount", "paid_amount", "refunded_amount",
				"receipt_type", "request_note", "ordered_at", "paid_at", "canceled_at", "cancel_reason"))
			view.put(field, order.get(field));
		view.put("orderer_name", crypto.decrypt((String) order.get("orderer_name_enc")));
		view.put("orderer_phone", crypto.decrypt((String) order.get("orderer_phone_enc")));
		view.put("orderer_email", crypto.decrypt((String) order.get("orderer_email_enc")));
		Map<String, Object> rcpt = shop.recipient(key);
		Map<String, Object> recipient = new LinkedHashMap<>();
		if (rcpt != null) {
			recipient.put("name", crypto.decrypt((String) rcpt.get("recipient_name_enc")));
			recipient.put("phone", crypto.decrypt((String) rcpt.get("recipient_phone_enc")));
			recipient.put("zipcode", rcpt.get("zipcode"));
			recipient.put("address1", crypto.decrypt((String) rcpt.get("address1_enc")));
			recipient.put("address2", crypto.decrypt((String) rcpt.get("address2_enc")));
			recipient.put("delivery_request", rcpt.get("delivery_request"));
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("order", view);
		result.put("recipient", recipient);
		result.put("items", shop.items(key));
		result.put("payments", shop.payments(key));
		result.put("shipments", shop.shipments(key));
		result.put("tracking_events", shop.trackingEvents(key));
		result.put("history", shop.history(key));
		result.put("claims", shop.claims(key));
		result.put("cancel", cancelPolicy(order, shop.progressedItems(key) > 0, shop.openCancelClaims(key) > 0));
		return result;
	}

	/** 화면에 보여 줄 취소 가능 여부: IMMEDIATE 바로 취소 / REQUEST 취소 요청(입금 확인된 주문 → 본사 환불) / NONE */
	static Map<String, Object> cancelPolicy(Map<String, Object> order, boolean progressed, boolean openClaim) {
		String status = text(order.get("order_status")), payment = text(order.get("payment_status"));
		String mode, reason = null;
		if ("CANCELED".equals(status)) { mode = "NONE"; reason = "이미 취소된 주문입니다."; }
		else if (openClaim) { mode = "NONE"; reason = "취소 요청을 본사가 처리하고 있습니다."; }
		else if (progressed || !Set.of("PENDING_PAYMENT", "PAID").contains(status)) { mode = "NONE"; reason = "상품 준비가 시작돼 바로 취소할 수 없습니다. 본사에 문의해주세요."; }
		else if (Set.of("WAITING", "CREDIT", "NONE").contains(payment)) mode = "IMMEDIATE";
		else mode = "REQUEST";
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("mode", mode);
		result.put("reason", reason);
		return result;
	}

	/** 주문 취소(본인). 돈이 오가지 않은 주문(입금 대기·후불)은 바로 취소, 입금 확인된 주문은 취소 요청(본사가 환불 후 처리) */
	@Transactional
	public Map<String, Object> cancel(WorkspaceUser user, Object orderId, Map<String, Object> body) {
		Map<String, Object> key = new HashMap<>();
		key.put("order_id", longOrZero(orderId));
		key.put("user_id", user.userId());
		Map<String, Object> order = shop.lockMyOrder(key);
		if (order == null) throw new IllegalArgumentException("주문을 찾을 수 없습니다(본인이 주문한 것만 취소할 수 있습니다).");
		Map<String, Object> policy = cancelPolicy(order, shop.progressedItems(key) > 0, shop.openCancelClaims(key) > 0);
		String mode = (String) policy.get("mode");
		if ("NONE".equals(mode)) throw new IllegalArgumentException((String) policy.get("reason"));
		String reasonCode = text(body.get("reason_code")).toUpperCase();
		if (!CANCEL_REASONS.contains(reasonCode)) reasonCode = "ETC";
		String detail = optional(body, "reason", 500);
		boolean immediate = "IMMEDIATE".equals(mode);

		Map<String, Object> seq = Map.of("seq_name", "CLAIM");
		admin.nextSequence(seq);
		String claimNo = "C" + LocalDateTime.parse(admin.dbNow(), DB_TIME).format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%04d", admin.currentSequence(seq));
		Map<String, Object> claim = new HashMap<>(key);
		claim.put("claim_no", claimNo);
		claim.put("reason_code", reasonCode);
		claim.put("reason_detail", detail);
		claim.put("status", immediate ? "COMPLETED" : "REQUESTED");
		claim.put("refund_amount", immediate ? BigDecimal.ZERO : order.get("paid_amount"));
		shop.insertCancelClaim(claim);
		claim.put("order_id", key.get("order_id"));
		shop.insertCancelClaimItems(claim);

		if (!immediate) {
			history(((Number) key.get("order_id")).longValue(), "ORDER", text(order.get("order_status")), "CANCEL_REQUESTED", detail, user.userId());
			return Map.of("code", 200, "message", "취소를 요청했습니다. 입금하신 금액은 본사가 확인 후 환불해 드립니다.", "mode", mode);
		}
		for (Map<String, Object> item : shop.items(key)) {
			if ("CANCELED".equals(item.get("item_status"))) continue;
			Map<String, Object> release = new HashMap<>();
			release.put("product_id", item.get("product_id"));
			release.put("qty", item.get("qty"));
			release.put("order_date", order.get("ordered_at") instanceof LocalDateTime t ? t.toLocalDate() : text(order.get("ordered_at")).substring(0, 10));
			shop.release(release);
		}
		shop.cancelItems(key);
		shop.cancelPayments(key);
		Map<String, Object> update = new HashMap<>(key);
		update.put("payment_status", "NONE");
		update.put("reason", detail == null ? reasonCode : detail);
		shop.cancelOrder(update);
		history(((Number) key.get("order_id")).longValue(), "ORDER", text(order.get("order_status")), "CANCELED", detail, user.userId());
		return Map.of("code", 200, "message", "주문을 취소했습니다.", "mode", mode);
	}

	/* ===================== 공통 ===================== */

	private void history(long orderId, String kind, String from, String to, String reason, String userId) {
		Map<String, Object> row = new HashMap<>();
		row.put("order_id", orderId);
		row.put("status_kind", kind);
		row.put("from_status", from);
		row.put("to_status", to);
		row.put("reason", reason);
		row.put("user_id", userId);
		shop.insertHistory(row);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
		return Map.of();
	}

	static String text(Object value) {
		return value == null ? "" : value.toString().trim();
	}

	private static String required(Map<String, Object> body, String key, String label, int max) {
		String value = text(body.get(key));
		if (value.isEmpty()) throw new IllegalArgumentException(label + "을(를) 입력해주세요.");
		if (value.length() > max) throw new IllegalArgumentException(label + "은(는) " + max + "자까지입니다.");
		return value;
	}

	private static String optional(Map<String, Object> body, String key, int max) {
		String value = text(body.get(key));
		if (value.length() > max) value = value.substring(0, max);
		return value.isEmpty() ? null : value;
	}

	private static String phone(String value, String label) {
		String digits = value.replaceAll("[^0-9]", "");
		if (digits.length() < 9 || digits.length() > 11) throw new IllegalArgumentException(label + "를 확인해주세요(숫자 9~11자리).");
		return digits;
	}

	private static String truncate(String value, int max) {
		return value.length() > max ? value.substring(0, max) : value;
	}

	private static long longOrZero(Object value) {
		try {
			return text(value).isEmpty() ? 0 : Long.parseLong(text(value));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("번호 형식을 확인해주세요: " + value);
		}
	}

	private static BigDecimal decimal(Object value, String label) {
		try {
			return new BigDecimal(text(value).replace(",", ""));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(label + "을(를) 숫자로 넣어주세요.");
		}
	}

	private static String plain(BigDecimal value) {
		return value == null ? "0" : value.stripTrailingZeros().toPlainString();
	}
}
