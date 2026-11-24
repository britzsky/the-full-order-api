package com.example.demo.service;

/**
 * 공급사 배송 구분을 공통 구분으로 바꾼다. 같은 발주서도 배송 구분이 다르면 따로 들어온다(직송·택배는 센터 배송과 시간이 다름).
 * <ul>
 * <li>CENTER 센터 배송: 웰스토리 CD, 아워홈 "아워홈배송"</li>
 * <li>DIRECT 직송: 웰스토리 VD, 아워홈 "공급사직송"</li>
 * <li>PARCEL 택배: 아워홈 "공급사택배"</li>
 * </ul>
 * 처음 보는 값은 DIRECT로 둔다 — 늦게 오는 쪽으로 봐야 결품으로 잘못 넘어가지 않는다. 원문은 따로 저장한다.
 * SQL 백필(docs/SHORTAGE_DB_UPDATE_20261119.sql)의 CASE와 같은 규칙이다.
 */
public final class DeliveryTypes {
	public static final String CENTER = "CENTER";
	public static final String DIRECT = "DIRECT";
	public static final String PARCEL = "PARCEL";

	private DeliveryTypes() {
	}

	public static String normalize(Object raw) {
		String value = raw == null ? "" : String.valueOf(raw).trim();
		if (value.isEmpty())
			return null;
		if ("CD".equalsIgnoreCase(value) || value.contains("아워홈") || value.contains("센터"))
			return CENTER;
		if (value.contains("택배"))
			return PARCEL;
		return DIRECT;
	}

	/** 납품 예정일 다음 며칠까지 기다린 뒤 "결품 확인 필요"로 바꿀지. 택배는 하루 이틀 늦을 수 있어 2일 */
	public static int graceDays(String type) {
		return PARCEL.equals(type) ? 2 : 1;
	}

	public static String label(String type) {
		if (type == null)
			return "배송 구분 없음";
		return switch (type) {
		case CENTER -> "센터 배송";
		case PARCEL -> "택배";
		default -> "직송";
		};
	}
}
