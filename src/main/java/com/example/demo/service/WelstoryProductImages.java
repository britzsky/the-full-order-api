package com.example.demo.service;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;

/** 웰스토리 이미지 목록에서 첫 번째 사용 가능한 사진의 원본과 썸네일을 추출한다. */
final class WelstoryProductImages {
	private WelstoryProductImages() {
	}

	static Map<String, Object> from(JsonNode product) {
		Map<String, Object> result = new LinkedHashMap<>();
		JsonNode images = product.path("imgURLs");
		// 필드 누락/잘못된 형식은 기존 사진 유지, 명시적인 빈 배열은 사진 삭제를 뜻한다.
		result.put("images_provided", images.isArray());
		result.put("image_url", null);
		result.put("thumbnail_url", null);
		if (!images.isArray())
			return result;
		String domain = product.path("imgDomain").asText("").trim();
		for (JsonNode image : images) {
			if (!image.isObject())
				continue;
			String original = resolve(image.path("img"), domain);
			String thumbnail = resolve(image.path("thumb"), domain);
			if (original != null || thumbnail != null) {
				result.put("image_url", original);
				result.put("thumbnail_url", thumbnail);
				break;
			}
		}
		return result;
	}

	private static String resolve(JsonNode value, String domain) {
		if (!value.isTextual() || value.asText().isBlank())
			return null;
		try {
			URI url = URI.create(value.asText().trim());
			if (!url.isAbsolute()) {
				if (domain.isBlank())
					return null;
				url = URI.create(domain.endsWith("/") ? domain : domain + "/").resolve(url);
			}
			if (!("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
					|| url.getHost() == null || url.getUserInfo() != null)
				return null;
			String address = url.toASCIIString();
			return address.length() <= 2048 ? address : null;
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}
}
