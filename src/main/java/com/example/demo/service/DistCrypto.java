package com.example.demo.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 더채움 유통 개인정보(*_enc 컬럼) 암호화. AES-256-GCM, 저장 형식 "v1:" + Base64(IV 12바이트 + 암호문).
 * 키는 dist.pii.key(Base64 32바이트, 비밀 설정 파일 또는 DIST_PII_KEY). 키가 없으면 개인정보를 받는 기능(주문)을 막는다 — 평문 저장 금지.
 * 키를 잃으면 저장된 개인정보를 읽을 수 없으니 운영 키는 따로 보관한다.
 */
@Component
public class DistCrypto {
	private static final String PREFIX = "v1:";
	private final SecretKeySpec key;
	private final SecureRandom random = new SecureRandom();

	public DistCrypto(@Value("${dist.pii.key:${DIST_PII_KEY:}}") String base64Key) {
		SecretKeySpec parsed = null;
		if (base64Key != null && !base64Key.isBlank()) {
			byte[] bytes = Base64.getDecoder().decode(base64Key.trim());
			if (bytes.length != 32) throw new IllegalStateException("dist.pii.key는 Base64로 된 32바이트(AES-256) 키여야 합니다.");
			parsed = new SecretKeySpec(bytes, "AES");
		}
		this.key = parsed;
	}

	public boolean configured() {
		return key != null;
	}

	private void requireKey() {
		if (key == null) throw new IllegalStateException("개인정보 암호화 키(dist.pii.key)가 설정되지 않아 주문을 받을 수 없습니다. 관리자에게 알려주세요.");
	}

	public String encrypt(String plain) {
		if (plain == null || plain.isEmpty()) return null;
		requireKey();
		try {
			byte[] iv = new byte[12];
			random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
			byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("개인정보를 암호화하지 못했습니다.", e);
		}
	}

	public String decrypt(String stored) {
		if (stored == null || stored.isEmpty()) return "";
		if (!stored.startsWith(PREFIX)) return "";
		requireKey();
		try {
			byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
			return new String(cipher.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new IllegalStateException("개인정보를 읽지 못했습니다(키가 바뀌었을 수 있음).", e);
		}
	}

	/** 검색용 해시(같은 값이면 같은 결과, 키 없이는 되돌릴 수 없음). 휴대폰은 숫자만 남겨서 */
	public String hash(String plain) {
		requireKey();
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("검색 키를 만들지 못했습니다.", e);
		}
	}

	/** 홍길동 → 홍*동, 김철 → 김*, 남궁민수 → 남**수 */
	public static String maskName(String name) {
		String value = name == null ? "" : name.trim();
		if (value.length() <= 1) return value;
		if (value.length() == 2) return value.charAt(0) + "*";
		return value.charAt(0) + "*".repeat(value.length() - 2) + value.charAt(value.length() - 1);
	}

	/** 01012345678 → 010-****-5678 */
	public static String maskPhone(String phone) {
		String digits = phone == null ? "" : phone.replaceAll("[^0-9]", "");
		if (digits.length() < 8) return "*".repeat(digits.length());
		return digits.substring(0, 3) + "-****-" + digits.substring(digits.length() - 4);
	}
}
