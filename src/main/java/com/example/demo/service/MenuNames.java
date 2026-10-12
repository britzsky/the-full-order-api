package com.example.demo.service;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * 식단표 메뉴명 매칭 키. 프런트 TableMealsManager.normalizeMenuText와 같은 규칙이어야 한다.
 * 알레르기 번호((5.6.16)·미역국5.6.16)와 장식 기호(★·※ 등)는 메뉴가 아니므로 지운다.
 */
public final class MenuNames {
	private static final Pattern ALLERGEN_GROUP = Pattern.compile("[(\\[]\\s*\\d+(?:\\s*[.,]\\s*\\d+)*\\s*[)\\]]");
	private static final Pattern ALLERGEN_TAIL = Pattern.compile("(?<=\\D)\\d+(?:[.,]\\d+)+$");
	private static final Pattern REMOVED = Pattern.compile("[\\s()\\[\\]{}<>/\\\\|,.:;\"'`~!@#$%^&*+=?_·•ㆍ\\-★☆※◎○●◇◆□■▶▷♥♡]");

	private MenuNames() {
	}

	public static String key(String value) {
		if (value == null)
			return "";
		String s = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
		s = ALLERGEN_GROUP.matcher(s).replaceAll("");
		s = ALLERGEN_TAIL.matcher(s.strip()).replaceAll("");
		return REMOVED.matcher(s).replaceAll("").toLowerCase(java.util.Locale.ROOT);
	}
}
