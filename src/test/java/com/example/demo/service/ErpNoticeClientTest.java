package com.example.demo.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class ErpNoticeClientTest {
	final ErpNoticeClient client = new ErpNoticeClient(WebClient.builder(), new ObjectMapper(), "https://example.invalid/api", 1);

	@Test
	@SuppressWarnings("unchecked")
	void keepsNeededFieldsNewestFirstAndDropsExpired() {
		// 2026-11-09 실제 응답 일부 + 기한 지난 공지 1건
		String body = """
				[{"large_type_nm":"업무표준화","large_type":2,"middle_type_nm":"본사회의록","reg_dt":"2026-06-07 19:44:48","user_id":"dh2","user_name":"민다희",
				  "middle_type":21,"position_name":"매니저","expire_dt":"2099-12-31","file_cnt":1,"idx":3,"title":"2026.06.05 본사 회의록"},
				 {"large_type_nm":"업무표준화","large_type":2,"middle_type_nm":"오리엔테이션","reg_dt":"2026-10-07 10:01:29","user_id":"ww1","user_name":"이원우",
				  "middle_type":23,"position_name":"매니저","expire_dt":"2099-12-31","file_cnt":2,"idx":8,"title":"채움원(근태관리 어플) 사용 가이드"},
				 {"large_type_nm":"회사소식","large_type":1,"reg_dt":"2026-11-01 09:00:00","expire_dt":"2026-11-08","idx":9,"title":"지난 공지"}]""";
		var result = client.parse(body, LocalDate.of(2026, 11, 9));
		assertThat(result).containsEntry("status", "OK");
		var notices = (List<Map<String, Object>>) result.get("notices");
		assertThat(notices).extracting(n -> n.get("idx")).containsExactly(8, 3);
		assertThat(notices.get(0)).containsEntry("title", "채움원(근태관리 어플) 사용 가이드").containsEntry("large_type_nm", "업무표준화")
				.containsEntry("file_cnt", 2).doesNotContainKey("user_id");
	}

	@Test
	void brokenBodyOrUnreachableIsError() {
		assertThat(client.parse("<html>", LocalDate.of(2026, 11, 9))).containsEntry("status", "ERROR");
		assertThat(client.parse("{}", LocalDate.of(2026, 11, 9))).containsEntry("status", "ERROR");
		assertThat(client.list()).containsEntry("status", "ERROR");
	}
}
