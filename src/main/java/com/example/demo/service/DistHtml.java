package com.example.demo.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

/**
 * 더채움 유통 상품 상세 설명(협력사 관리 편집기에서 만든 HTML)을 저장 전에 정리한다(2026-11-04).
 * 편집기가 만드는 태그(문단·제목·굵게·목록·인용·표·이미지·링크)만 남기고 script·이벤트 속성·javascript: 주소는 지운다.
 * style은 문단 정렬(text-align)만 남긴다. 더채움몰 화면도 보여줄 때 한 번 더 거른다(DOMPurify).
 */
final class DistHtml {
	/** 상세 설명 최대 길이(글자). 이미지는 주소로 넣으므로 이 정도면 충분하다 */
	static final int MAX_LENGTH = 1_000_000;
	private static final Pattern ALIGN = Pattern.compile("text-align\\s*:\\s*(left|center|right|justify)", Pattern.CASE_INSENSITIVE);
	private static final String[] ALIGNABLE = { "p", "h2", "h3", "h4", "th", "td" };
	private static final Safelist SAFE = new Safelist()
			.addTags("p", "br", "h2", "h3", "h4", "strong", "b", "em", "i", "u", "s", "ul", "ol", "li", "blockquote", "hr",
					"table", "thead", "tbody", "tr", "th", "td", "colgroup", "col", "img", "a")
			.addAttributes("p", "style").addAttributes("h2", "style").addAttributes("h3", "style").addAttributes("h4", "style")
			.addAttributes("th", "style", "colspan", "rowspan").addAttributes("td", "style", "colspan", "rowspan")
			.addAttributes("img", "src", "alt", "title", "width", "height")
			.addAttributes("a", "href", "title")
			.addProtocols("img", "src", "http", "https", "data")
			.addProtocols("a", "href", "http", "https", "mailto", "tel")
			.addEnforcedAttribute("a", "rel", "noopener noreferrer nofollow")
			.addEnforcedAttribute("a", "target", "_blank");

	private DistHtml() {
	}

	/** 정리한 HTML. 글자도 이미지도 없으면 null */
	static String clean(String html) {
		if (html == null || html.isBlank()) return null;
		if (html.length() > MAX_LENGTH) throw new IllegalArgumentException("상세 설명이 너무 깁니다. 이미지는 붙여넣지 말고 주소로 넣어주세요.");
		Document.OutputSettings output = new Document.OutputSettings().prettyPrint(false);
		String safe = Jsoup.clean(html, "", SAFE, output);
		Document doc = Jsoup.parseBodyFragment(safe);
		doc.outputSettings(output);
		for (String tag : ALIGNABLE) {
			for (Element element : doc.select(tag + "[style]")) {
				Matcher align = ALIGN.matcher(element.attr("style"));
				if (align.find()) element.attr("style", "text-align: " + align.group(1).toLowerCase());
				else element.removeAttr("style");
			}
		}
		// data: 주소는 그림(data:image/…)만
		for (Element image : doc.select("img[src]")) {
			String src = image.attr("src").trim().toLowerCase();
			if (src.startsWith("data:") && !src.startsWith("data:image/")) image.remove();
		}
		if (doc.body().text().isBlank() && doc.select("img, hr, table").isEmpty()) return null;
		return doc.body().html();
	}
}
