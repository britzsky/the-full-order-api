package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
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
	 * 공급사 2026-10-07 회신: odrConvqty는 G 단일 기준의 환산중량이다.
	 * 규격 문구와 일치할 필요가 없으며, 용량 상품도 이 값을 ml나 개수로 해석하지 않는다.
	 * EA 주문은 기존 대표 규격 1EA를 유지하고 확인된 그램 중량을 두 번째 규격으로 제공한다.
	 */
	public static List<Pack> ourhomePacks(String orderUnit, String goodsz, String convQty) {
		String order = unit(orderUnit);
		BigDecimal conv;
		try {
			conv = new BigDecimal(convQty == null ? "" : convQty.trim());
		} catch (NumberFormatException e) {
			conv = null;
		}
		List<Pack> result = new ArrayList<>();
		Pack volume = ourhomeVolume(goodsz, conv);
		if (order.equals("EA"))
			result.add(new Pack(BigDecimal.ONE, "EA"));
		// 저장 칸은 대표·보조 두 개뿐이다. EA 발주 음료는 1EA + 용량(ml)을, 그 밖에는 환산중량(g) + 용량(ml)을 둔다.
		if (conv != null && conv.signum() > 0 && !(order.equals("EA") && volume != null))
			result.add(new Pack(conv.stripTrailingZeros(), "g"));
		else if (conv == null || conv.signum() <= 0) {
			if (!order.equals("EA"))
				result.addAll(packs(orderUnit, ""));
		}
		if (volume != null && result.size() < 2)
			result.add(volume);
		return result;
	}

	private static final Pattern OURHOME_VOLUME = Pattern.compile(
			"(\\d+(?:\\.\\d+)?)\\s*(㎖|ml|ℓ|l)(?![a-z])(?:\\s*[*×x]\\s*(\\d+)\\s*(?:ea|개|입|병|팩)?)?", Pattern.CASE_INSENSITIVE);

	/**
	 * 아워홈 goodsz의 용량 표기(예: "EA(1.5ℓ)", "BOX(296㎖*12ea_미국)")를 발주단위 1개의 ml로 읽는다.
	 * odrConvqty(g)를 ml로 추정하지 않는다. 공급사가 적은 용량이 환산중량과 액체 수준(비중 0.7~1.43)으로 맞을 때만
	 * 인정해, 낱개 용량을 박스 용량으로 잘못 읽는 경우를 거른다. 환산중량이 없으면 표기만 믿는다.
	 */
	static Pack ourhomeVolume(String goodsz, BigDecimal conv) {
		if (goodsz == null)
			return null;
		Matcher m = OURHOME_VOLUME.matcher(goodsz);
		if (!m.find())
			return null;
		BigDecimal ml = new BigDecimal(m.group(1));
		if (m.group(2).equals("ℓ") || m.group(2).equalsIgnoreCase("l"))
			ml = ml.multiply(new BigDecimal("1000"));
		if (m.group(3) != null)
			ml = ml.multiply(new BigDecimal(m.group(3)));
		if (ml.signum() <= 0)
			return null;
		if (conv != null && conv.signum() > 0) {
			BigDecimal ratio = ml.divide(conv, 4, RoundingMode.HALF_UP);
			if (ratio.compareTo(new BigDecimal("0.7")) < 0 || ratio.compareTo(new BigDecimal("1.43")) > 0)
				return null;
		}
		return new Pack(ml.stripTrailingZeros(), "ml");
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
