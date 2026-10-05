package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 질량·부피·개수와 포장 단위를 구분한다. 알 수 없는 차원 간 환산은 추측하지 않는다. */
public final class QuantityUnits {
	private QuantityUnits() {
	}

	public static String unit(Object value) {
		String s = value == null ? "" : value.toString().trim().toUpperCase(Locale.ROOT);
		return switch (s) {
		case "G" -> "g";
		case "KG" -> "kg";
		case "ML" -> "ml";
		case "L" -> "L";
		case "개", "EA" -> "EA";
		case "팩", "봉", "PAC" -> "PAC";
		case "박스", "BOX" -> "BOX";
		default -> s;
		};
	}

	public static BigDecimal convert(BigDecimal qty, Object from, Object to) {
		String a = unit(from), b = unit(to);
		if (a.isBlank() || b.isBlank())
			throw new IllegalArgumentException("단위가 필요합니다.");
		if (a.equals(b))
			return qty;
		if ((a.equals("kg") && b.equals("g")) || (a.equals("L") && b.equals("ml")))
			return qty.multiply(new BigDecimal("1000"));
		if ((a.equals("g") && b.equals("kg")) || (a.equals("ml") && b.equals("L")))
			return qty.divide(new BigDecimal("1000"));
		throw new IllegalArgumentException("환산정보 확인 필요: " + a + " → " + b);
	}

	public record Pack(BigDecimal quantity, String baseUnit) {
	}

	/** 대표 규격. 무게·부피를 우선하고, 없으면 개수, 둘 다 없으면 0(확인 필요)이다. */
	public static Pack pack(String orderUnit, String standard) {
		List<Pack> packs = packs(orderUnit, standard);
		return packs.isEmpty() ? new Pack(BigDecimal.ZERO, unit(orderUnit)) : packs.get(0);
	}

	/**
	 * 주문단위 1개에 대해 읽을 수 있는 규격을 모두 돌려준다 (무게·부피 먼저, 그다음 개수).
	 * 예: "대란,국산,30EA,1560G이상/PAC" → [1560g, 30EA]. 거래처 연결 시 식재료 기준단위에 맞는 쪽을 고른다.
	 * 주문단위에 직접 대응하는 명시 규격만 사용한다. 같은 차원의 개당 규격 설명은 허용하되,
	 * 범위(375~484G)·곱셈만 있는 규격(100G*10EA)은 추측하지 않는다.
	 */
	public static List<Pack> packs(String orderUnit, String standard) {
		String order = unit(orderUnit);
		if (order.equals("kg"))
			return List.of(new Pack(new BigDecimal("1000"), "g"));
		if (order.equals("L"))
			return List.of(new Pack(new BigDecimal("1000"), "ml"));
		if (order.equals("g") || order.equals("ml"))
			return List.of(new Pack(BigDecimal.ONE, order));
		String spec = standard == null ? "" : standard.toUpperCase(Locale.ROOT);
		String perOrder = "\\s*/\\s*" + Pattern.quote(order.toUpperCase(Locale.ROOT));
		List<Pack> result = new ArrayList<>();
		// "1560G이상/PAC", "2KG내외/BOX"처럼 최소·대략 표기가 붙은 무게도 그 값을 규격으로 본다.
		// 1.3KG(28G*약46EA)/PAC: 괄호는 개당 설명이며, 명시된 포장 중량 1.3KG를 사용한다.
		String pieceNote = "(?:\\s*\\(\\s*\\d+(?:\\.\\d+)?\\s*(KG|G|ML|L)\\s*[*×X]\\s*(?:약\\s*)?\\d+(?:\\.\\d+)?\\s*(?:EA|개)\\s*\\))?";
		var weight = Pattern.compile("(?:^|,)\\s*(\\d+(?:\\.\\d+)?)\\s*(KG|G|ML|L)\\s*(?:이상|내외|전후)?" + pieceNote + perOrder
				+ "(?=,|$)").matcher(spec);
		Pack found = null;
		boolean ambiguous = false;
		while (weight.find()) {
			if (found != null)
				ambiguous = true;
			String source = unit(weight.group(2)), base = (source.equals("kg") || source.equals("g")) ? "g" : "ml";
			if (weight.group(3) != null) {
				String pieceUnit = unit(weight.group(3));
				boolean pieceIsWeight = pieceUnit.equals("kg") || pieceUnit.equals("g");
				if (pieceIsWeight != base.equals("g")) {
					ambiguous = true;
					continue;
				}
			}
			found = new Pack(convert(new BigDecimal(weight.group(1)), source, base), base);
		}
		if (found != null && !ambiguous)
			result.add(found);
		if (order.equals("EA")) {
			result.add(new Pack(BigDecimal.ONE, "EA"));
		} else {
			// 쉼표로 구분된 단독 개수 항목만 인정한다: ",30EA," 또는 ",30EA/PAC,"
			var count = Pattern.compile("(?:^|,)\\s*(\\d+)\\s*(?:EA|개)(?:" + perOrder + ")?\\s*(?=,|$)").matcher(spec);
			Pack counted = null;
			boolean many = false;
			while (count.find()) {
				if (counted != null)
					many = true;
				counted = new Pack(new BigDecimal(count.group(1)), "EA");
			}
			if (counted != null && !many && counted.quantity().signum() > 0)
				result.add(counted);
		}
		return result;
	}

	/**
	 * 아워홈 규격. goodsz는 "EA(450g)", "PK.(20kg)", "PK.(개당20g*20)", "KG(국내산)"처럼 단위(설명) 형식이고,
	 * odrConvqty는 주문단위 1개의 환산수량이지만 단위(g/ml/개)가 따로 오지 않는다.
	 * 규격에서 읽은 중량·용량(곱셈 포함)이 odrConvqty와 정확히 같을 때만 인정한다. 한쪽만으로는 단위를 추측하지 않는다.
	 * 주문단위가 EA면 [1EA, 중량]; 그 외는 [중량, 개수] 순서다 (기존 EA 상품의 대표 규격 1EA를 유지하기 위함).
	 */
	public static List<Pack> ourhomePacks(String orderUnit, String goodsz, String convQty) {
		String order = unit(orderUnit);
		if (order.equals("kg") || order.equals("L") || order.equals("g") || order.equals("ml"))
			return packs(orderUnit, "");
		BigDecimal conv;
		try {
			conv = new BigDecimal(convQty == null ? "" : convQty.trim());
		} catch (NumberFormatException e) {
			conv = null;
		}
		String spec = (goodsz == null ? "" : goodsz).toUpperCase(Locale.ROOT).replace("㎖", "ML").replace("㎏", "KG")
				.replace("ℓ", "L").replace("ｇ", "G");
		Pack weight = null, count = null;
		boolean ambiguous = false;
		if (conv != null && conv.signum() > 0) {
			List<BigDecimal[]> candidates = new ArrayList<>(); // {값, 0=g 1=ml}
			// "KG(국내산)"처럼 숫자 없이 앞에 온 단위는 1단위다.
			var lead = Pattern.compile("^\\s*(KG|ML|G|L)(?![A-Z])").matcher(spec);
			if (lead.find())
				candidates.add(amount(BigDecimal.ONE, lead.group(1)));
			// 12G4EA, 20G*20, 28G*약46EA처럼 뒤에 붙은 개수는 곱한 값도 후보로 본다.
			var m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(KG|ML|G|L)(?![A-Z])(?:\\s*[*×X]?\\s*(?:약\\s*)?(\\d+)\\s*(?:EA|개|입)?)?")
					.matcher(spec);
			while (m.find()) {
				BigDecimal[] single = amount(new BigDecimal(m.group(1)), m.group(2));
				candidates.add(single);
				if (m.group(3) != null)
					candidates.add(new BigDecimal[] { single[0].multiply(new BigDecimal(m.group(3))), single[1] });
			}
			for (BigDecimal[] c : candidates) {
				if (c[0].compareTo(conv) != 0)
					continue;
				String base = c[1].signum() == 0 ? "g" : "ml";
				if (weight != null && !weight.baseUnit().equals(base))
					ambiguous = true;
				weight = new Pack(conv.stripTrailingZeros(), base);
			}
			var n = Pattern.compile("(\\d+)\\s*(?:EA|개|입)").matcher(spec);
			while (weight == null && n.find())
				if (new BigDecimal(n.group(1)).compareTo(conv) == 0)
					count = new Pack(conv.stripTrailingZeros(), "EA");
		}
		List<Pack> result = new ArrayList<>();
		if (order.equals("EA"))
			result.add(new Pack(BigDecimal.ONE, "EA"));
		if (weight != null && !ambiguous)
			result.add(weight);
		if (!order.equals("EA") && count != null)
			result.add(count);
		return result;
	}

	private static BigDecimal[] amount(BigDecimal qty, String unitText) {
		String u = unit(unitText);
		boolean mass = u.equals("kg") || u.equals("g");
		return new BigDecimal[] { convert(qty, u, mass ? "g" : "ml"), mass ? BigDecimal.ZERO : BigDecimal.ONE };
	}

	/** 식재료 기준단위로 환산 가능한 첫 규격. 없으면 null. */
	public static Pack packFor(List<Pack> packs, Object ingredientUnit) {
		for (Pack pack : packs) {
			if (pack.quantity() == null || pack.quantity().signum() <= 0)
				continue;
			try {
				if (convert(pack.quantity(), pack.baseUnit(), ingredientUnit).signum() > 0)
					return pack;
			} catch (IllegalArgumentException ignored) {
				// 다른 차원(g↔EA 등)은 다음 규격을 본다.
			}
		}
		return null;
	}

	/** 클라이언트 계산 결과 대신 원수량과 저장된 기준단위로 재계산한다. */
	public static void recipe(Map<String, Object> row, String base) {
		BigDecimal qty = new BigDecimal(String.valueOf(row.getOrDefault("qty_num", 0)));
		BigDecimal servings = new BigDecimal(String.valueOf(row.getOrDefault("recipe_yield_servings", 1)));
		if (qty.signum() < 0 || servings.signum() <= 0)
			throw new IllegalArgumentException("수량은 0 이상, 인분은 0보다 커야 합니다.");
		row.put("base_unit", unit(base));
		row.put("recipe_yield_servings", servings);
		try {
			BigDecimal converted = convert(qty, row.get("qty_unit"), base);
			row.put("qty_base", converted);
			row.put("qty_per_person", converted.divide(servings, 3, RoundingMode.HALF_UP));
			row.put("review_flag", "0");
		} catch (IllegalArgumentException ex) {
			row.put("qty_base", BigDecimal.ZERO);
			row.put("qty_per_person", BigDecimal.ZERO);
			row.put("review_flag", "1");
		}
	}
}
