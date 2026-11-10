package com.example.demo.service;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class InstagramFeedClientTest {
	final InstagramFeedClient client = new InstagramFeedClient(WebClient.builder(), new ObjectMapper(), "https://example.invalid/instagram-proxy", 1);

	@Test
	@SuppressWarnings("unchecked")
	void keepsPhotoLinkAndFirstCaptionLineUpToLimit() {
		// 2026-11-10 실제 응답 모양(줄임) + 동영상 1건 + 사진 없는 1건
		String body = """
				{"user":{"id":"1","username":"thefull_16"},"data":[
				 {"id":"a","caption":"\\n더채움에서 제공한 학교 급식 모음.Zip입니다!\\n\\n#더채움","media_type":"CAROUSEL_ALBUM",
				  "media_url":"https://cdn.example/a.jpg","thumbnail_url":null,"permalink":"https://www.instagram.com/p/A/","timestamp":"2026-11-09T05:09:49+0000","children":[]},
				 {"id":"b","media_type":"VIDEO","media_url":"https://cdn.example/b.mp4","thumbnail_url":"https://cdn.example/b.jpg",
				  "permalink":"https://www.instagram.com/reel/B/","timestamp":"2026-11-08T01:00:00+0000"},
				 {"id":"c","media_type":"IMAGE","media_url":null,"permalink":"https://www.instagram.com/p/C/"},
				 {"id":"d","media_type":"IMAGE","media_url":"https://cdn.example/d.jpg","permalink":"https://www.instagram.com/p/D/"},
				 {"id":"e","media_type":"IMAGE","media_url":"https://cdn.example/e.jpg","permalink":"https://www.instagram.com/p/E/"}]}""";
		var result = client.parse(body, 3);
		assertThat(result).containsEntry("status", "OK");
		var posts = (List<Map<String, Object>>) result.get("posts");
		assertThat(posts).extracting(p -> p.get("id")).containsExactly("a", "b", "d");
		assertThat(posts.get(0)).containsEntry("caption", "더채움에서 제공한 학교 급식 모음.Zip입니다!").containsEntry("image", "https://cdn.example/a.jpg")
				.doesNotContainKey("children");
		assertThat(posts.get(1)).containsEntry("image", "https://cdn.example/b.jpg");
	}

	@Test
	void brokenBodyOrUnreachableIsError() {
		assertThat(client.parse("<html>", 4)).containsEntry("status", "ERROR");
		assertThat(client.parse("{}", 4)).containsEntry("status", "ERROR");
		assertThat(client.recent(4)).containsEntry("status", "ERROR");
	}
}
