package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.demo.mapper.PurchaseHistoryMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 매일 밤 구매 이력: 사업장마다 받은 데까지 기록하고 이어서 받는다(받은 적 없으면 시작일부터, 받았으면 2일 겹쳐서). */
class PurchaseHistoryNightlyTest {
	final ObjectMapper json = new ObjectMapper();
	final PurchaseHistoryMapper mapper = mock(PurchaseHistoryMapper.class);
	final WelstoryItemLookupService welstory = mock(WelstoryItemLookupService.class);
	final PurchaseHistoryImportService service = new PurchaseHistoryImportService(mapper, welstory, mock(OurhomeSiteService.class), json, 0);
	final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
	final LocalDate start = today.minusDays(9);

	PurchaseHistoryNightlyTest() {
		service.setHistoryStart(start.toString());
	}

	static Map<String, Object> target(String account, String site, String syncedThrough) {
		Map<String, Object> t = new HashMap<>(Map.of("account_id", account, "supplier_account_site_id", 7L, "supplier_id", 1L,
				"external_site_code", site, "supplier_code", "WELSTORY"));
		t.put("synced_through", syncedThrough);
		return t;
	}

	JsonNode noData() throws Exception {
		return json.readTree("{\"dataBody\":{\"resCd\":\"E1103\",\"resMsg\":\"조회 데이터가 존재하지 않습니다.\"}}");
	}

	/** 요청한 입고일(reqDeliveryDate) 목록 */
	List<String> requestedDays() {
		var captor = ArgumentCaptor.forClass(JsonNode.class);
		verify(welstory, atLeast(0)).call(eq("/fdapi/service/payer-receive-detail"), captor.capture());
		return captor.getAllValues().stream().map(r -> r.path("dataHeader").path("soldTo").asText() + ":" + r.path("dataHeader").path("reqDeliveryDate").asText()).toList();
	}

	static String basic(LocalDate d) {
		return d.toString().replace("-", "");
	}

	@Test
	void newSiteFromStartAndSyncedSiteWithOverlapThenRecordsThroughToday() throws Exception {
		when(mapper.historySyncTargets()).thenReturn(List.of(target("A", "NEW", null), target("B", "OLD", today.minusDays(3).toString())));
		when(mapper.historySyncedThrough(argThat(k -> "B".equals(k.get("account_id"))))).thenReturn(today.minusDays(3).toString());
		when(welstory.call(anyString(), any())).thenReturn(noData());

		var result = service.runNightly(LocalDateTime.now().plusHours(1));

		assertThat(result).containsEntry("done", 2).containsEntry("left_for_next_night", 0);
		List<String> days = requestedDays();
		assertThat(days.stream().filter(d -> d.startsWith("NEW:"))).hasSize(10).first().isEqualTo("NEW:" + basic(start));
		// 받은 날(오늘-3) 2일 전부터 오늘까지 = 6일
		assertThat(days.stream().filter(d -> d.startsWith("OLD:"))).hasSize(6).first().isEqualTo("OLD:" + basic(today.minusDays(5)));
		verify(mapper).saveHistorySync(argThat(p -> "A".equals(p.get("account_id")) && today.toString().equals(p.get("synced_through"))
				&& "DONE".equals(p.get("status")) && Boolean.FALSE.equals(p.get("failed"))));
		verify(mapper).saveHistorySync(argThat(p -> "B".equals(p.get("account_id")) && today.toString().equals(p.get("synced_through"))));
	}

	@Test
	void failedDayStopsRecordedProgressThereForRetryNextNight() throws Exception {
		LocalDate bad = start.plusDays(4);
		when(mapper.historySyncTargets()).thenReturn(List.of(target("A", "S1", null)));
		when(welstory.call(anyString(), any())).thenAnswer(inv -> {
			JsonNode req = inv.getArgument(1);
			if (basic(bad).equals(req.path("dataHeader").path("reqDeliveryDate").asText()))
				throw new IllegalStateException("웰스토리 응답 없음");
			return noData();
		});

		var result = service.runNightly(LocalDateTime.now().plusHours(1));

		assertThat(result).containsEntry("with_errors", 1);
		verify(mapper).saveHistorySync(argThat(p -> bad.minusDays(1).toString().equals(p.get("synced_through"))
				&& "DONE_WITH_ERRORS".equals(p.get("status")) && Boolean.TRUE.equals(p.get("failed"))));
	}

	@Test
	void stopsAfterStopTimeAndLeavesRestForNextNight() throws Exception {
		when(mapper.historySyncTargets()).thenReturn(List.of(target("A", "S1", null), target("B", "S2", null)));
		var result = service.runNightly(LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusMinutes(1));
		assertThat(result).containsEntry("done", 0).containsEntry("left_for_next_night", 2);
		verifyNoInteractions(welstory);
	}

	@Test
	void manualImportExtendsProgressOnlyWhenContiguous() throws Exception {
		when(welstory.call(anyString(), any())).thenReturn(noData());
		when(mapper.importSites(anyMap())).thenReturn(List.of(target("A", "S1", null)));
		// 받은 기록 없음: 시작일부터 이어지는 직접 가져오기는 기록한다
		service.runNow(new HashMap<>(Map.of("account_id", "A", "from_date", start.toString(), "to_date", start.plusDays(2).toString())));
		verify(mapper).saveHistorySync(argThat(p -> start.plusDays(2).toString().equals(p.get("synced_through"))));
		// 중간 날짜만 다시 받으면(시작일과 이어지지 않음) 기록을 건드리지 않는다
		clearInvocations(mapper);
		service.runNow(new HashMap<>(Map.of("account_id", "A", "from_date", start.plusDays(5).toString(), "to_date", start.plusDays(6).toString())));
		verify(mapper, never()).saveHistorySync(anyMap());
	}

	@Test
	void stopTimeIsNextMorning() {
		var at22 = LocalDateTime.of(2026, 11, 27, 22, 0);
		assertThat(PurchaseHistoryNightlyScheduler.stopTime(at22, LocalTime.of(6, 0))).isEqualTo(LocalDateTime.of(2026, 11, 28, 6, 0));
		assertThat(PurchaseHistoryNightlyScheduler.stopTime(LocalDateTime.of(2026, 11, 28, 1, 0), LocalTime.of(6, 0)))
				.isEqualTo(LocalDateTime.of(2026, 11, 28, 6, 0));
	}
}
