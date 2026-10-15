package com.example.demo.service;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KakaoNotificationServiceTest {
	final ObjectMapper json = new ObjectMapper();

	/** 발송 이력 유일키(같은 내용·같은 수신자)를 흉내 내는 DB. */
	static class Database extends JdbcTemplate {
		final Set<String> logged = new HashSet<>();
		final List<Object[]> results = new ArrayList<>();

		@Override
		public List<Map<String, Object>> queryForList(String sql, Object... args) {
			if (sql.contains("FROM the_full_order.tb_account_kakao_recipient WHERE account_id=?"))
				return List.of(Map.of("kakao_recipient_id", 7L, "phone", "01012345678"));
			if (sql.contains("the_full.tb_account"))
				return List.of(Map.of("account_name", "테스트 식당"));
			return List.of();
		}

		@Override
		public int update(String sql, Object... args) {
			if (sql.contains("INSERT IGNORE INTO the_full_order.tb_kakao_message_log"))
				return logged.add(args[0] + "|" + args[1] + "|" + args[2] + "|" + args[3]) ? 1 : 0;
			if (sql.contains("UPDATE the_full_order.tb_kakao_message_log"))
				results.add(args);
			return 1;
		}
	}

	KakaoAlimtalkClient client(boolean configured) {
		var client = mock(KakaoAlimtalkClient.class);
		when(client.configured()).thenReturn(configured);
		when(client.send(any(), any(), any())).thenReturn(new KakaoAlimtalkClient.Result(true, "M1", "2000", "정상 접수"));
		return client;
	}

	@Test
	void sameContentIsSentOnceAndChangedContentAgain() {
		var db = new Database();
		var client = client(true);
		var service = new KakaoNotificationService(db, client, json, true, "TPL_SUPPLIER", "");
		assertThat(service.supplierStatus("A", "PO1", "fp1", "아워홈", "2026-10-16", "출고완료 · 2품목 입고 처리 요망")).isEqualTo(1);
		assertThat(service.supplierStatus("A", "PO1", "fp1", "아워홈", "2026-10-16", "출고완료 · 2품목 입고 처리 요망")).isEqualTo(0);
		assertThat(service.supplierStatus("A", "PO1", "fp2", "아워홈", "2026-10-16", "출고완료 · 출고 수량 다름 1품목")).isEqualTo(1);
		verify(client).send(eq("01012345678"), eq("TPL_SUPPLIER"),
				argThat(v -> "테스트 식당".equals(v.get("업장명")) && "출고완료 · 2품목 입고 처리 요망".equals(v.get("상태"))));
		assertThat(db.results).hasSize(2).allSatisfy(args -> assertThat(args[0]).isEqualTo("SENT"));
	}

	@Test
	void disabledOrUnconfiguredDoesNothing() {
		var db = mock(JdbcTemplate.class);
		var off = new KakaoNotificationService(db, client(true), json, false, "TPL", "TPL");
		var noKeys = new KakaoNotificationService(db, client(false), json, true, "TPL", "TPL");
		var noTemplate = new KakaoNotificationService(db, client(true), json, true, "", "");
		for (var service : List.of(off, noKeys, noTemplate))
			assertThat(service.supplierStatus("A", "PO1", "fp", "아워홈", "2026-10-16", "x")).isZero();
		assertThat(off.deadlines()).containsEntry("sent", 0);
		verifyNoInteractions(db);
	}

	@Test
	void phoneAndDeadlineLabels() {
		assertThat(KakaoNotificationService.phone("010-1234-5678")).isEqualTo("01012345678");
		assertThatThrownBy(() -> KakaoNotificationService.phone("02-123-4567")).hasMessageContaining("휴대폰");
		assertThat(KakaoNotificationService.deadlineLabel("2026-10-16T11:00")).isEqualTo("10/16(금) 11:00");
	}

	@Test
	void solapiSignatureIsHexHmacOfDatePlusSalt() {
		assertThat(KakaoAlimtalkClient.signature("secret", "2026-10-15T04:00:00Z", "abc123salt456"))
				.isEqualTo("c40dfea8b25db743349a56b2a8ac6235060fc6fabbf35c0dd3006a0471e86b6f");
		var client = new KakaoAlimtalkClient(WebClient.builder(), json, "https://api.solapi.com", "KEY", "SECRET", "PF", "010-0000-0000", false, 5);
		assertThat(client.authorization(Instant.parse("2026-10-15T04:00:00.123Z")))
				.matches("HMAC-SHA256 apiKey=KEY, date=2026-10-15T04:00:00Z, salt=[0-9a-f]{32}, signature=[0-9a-f]{64}");
	}

	@Test
	void solapiResponseFailedListMeansNotAccepted() throws Exception {
		var client = new KakaoAlimtalkClient(WebClient.builder(), json, "", "", "", "", "", false, 5);
		var failed = client.result(json.readTree("""
				{"groupInfo":{"groupId":"G1"},"failedMessageList":[{"statusCode":"3040","statusMessage":"등록되지 않은 발신번호입니다."}]}"""));
		assertThat(failed.accepted()).isFalse();
		assertThat(failed.code()).isEqualTo("3040");
		var ok = client.result(json.readTree("""
				{"groupInfo":{"groupId":"G1"},"failedMessageList":[],"messageList":[{"messageId":"M9","statusCode":"2000","statusMessage":"정상 접수"}]}"""));
		assertThat(ok.accepted()).isTrue();
		assertThat(ok.messageId()).isEqualTo("M9");
		assertThat(client.configured()).isFalse();
		assertThat(client.send("01012345678", "T", Map.of()).code()).isEqualTo("NOT_CONFIGURED");
	}
}
