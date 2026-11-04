package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DistHtmlTest {
	@Test void keepsEditorFormatting() {
		String html = "<h2 style=\"text-align: center\">완도 활전복</h2><p><strong>굵게</strong> <u>밑줄</u></p>"
				+ "<ul><li><p>항목</p></li></ul><table><tbody><tr><th>중량</th><td>1kg</td></tr></tbody></table>"
				+ "<img src=\"https://cdn.example.com/a.jpg\" alt=\"\"><p><a href=\"https://thefull.kr\">링크</a></p>";
		String clean = DistHtml.clean(html);
		assertThat(clean).contains("<h2 style=\"text-align: center\">", "<strong>굵게</strong>", "<u>밑줄</u>", "<th>중량</th>",
				"src=\"https://cdn.example.com/a.jpg\"", "rel=\"noopener noreferrer nofollow\"", "target=\"_blank\"");
	}

	@Test void removesScriptsHandlersAndOtherStyles() {
		String clean = DistHtml.clean("<p style=\"position:fixed;top:0;text-align:right\" onclick=\"x()\">글</p><script>alert(1)</script>"
				+ "<a href=\"javascript:alert(1)\">a</a><img src=\"x\" onerror=\"alert(1)\"><img src=\"data:text/html;base64,PHNjcmlwdD4=\">"
				+ "<iframe src=\"https://evil\"></iframe><div style=\"color:red\">div</div>");
		assertThat(clean).doesNotContain("script", "onclick", "onerror", "javascript", "iframe", "position", "data:text", "<div")
				.contains("<p style=\"text-align: right\">글</p>", "div");
	}

	@Test void emptyBecomesNullAndPlainTextStays() {
		assertThat(DistHtml.clean("<p></p><p> </p>")).isNull();
		assertThat(DistHtml.clean("  ")).isNull();
		assertThat(DistHtml.clean("그냥 글")).isEqualTo("그냥 글");
		assertThat(DistHtml.clean("<p><img src=\"data:image/png;base64,iVBORw0KGgo=\"></p>")).contains("data:image/png");
	}

	@Test void tooLongIsRejected() {
		assertThatThrownBy(() -> DistHtml.clean("a".repeat(DistHtml.MAX_LENGTH + 1))).hasMessageContaining("너무 깁니다");
	}
}
