package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.demo.mapper.SupplierIntegrationMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 웰스토리 판가는 지급처 대표 사업장(repSoldtoYn=Y) 코드로만 조회된다(2026-11-27 운영 확인: 대표가 아닌 사업장은 모든 순기 E1103).
 * 사업장 목록에서 대표 사업장을 채우고, 판가 동기화는 대표 사업장으로 조회·저장한다.
 */
class WelstoryRepresentativeSiteTest {
	private final ObjectMapper json = new ObjectMapper();

	@Test
	void siteSyncFillsRepresentativeOfSamePayer() throws Exception {
		var mapper = mock(SupplierIntegrationMapper.class);
		var api = mock(WelstoryItemLookupService.class);
		var siteService = mock(SupplierSiteService.class);
		when(api.clientKeys()).thenReturn(List.of("corp"));
		when(api.callWith(eq("corp"), eq("/fdapi/service/payer-rep-soldto"), any())).thenReturn(json.readTree("""
				{"dataHeader":{"contYn":"N","nextKey":""},"dataBody":{"resCd":"S0000","data":[
				 {"soldTo":"A0374032","soldToNm":"고은요양원","payerCode":"A0275453","repSoldtoYn":"N","pricingCode":"Y015621"},
				 {"soldTo":"A0275536","soldToNm":"칼빈메니토바","payerCode":"A0275453","repSoldtoYn":"Y","pricingCode":"Y015621"},
				 {"soldTo":"A0374231","soldToNm":"사랑의요양원","payerCode":"A0195993","repSoldtoYn":"Y","pricingCode":"Y015621"},
				 {"soldTo":"A0374099","soldToNm":"대표없는지급처","payerCode":"A0999999","repSoldtoYn":"N","pricingCode":"Y015621"}]}}
				"""));
		var service = new WelstoryCatalogSyncService(mapper, api, json, mock(ReceiptPostingService.class), siteService);

		service.syncSites(Map.of("user_id", "u"));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Map<String, Object>>> saved = ArgumentCaptor.forClass(List.class);
		verify(siteService).saveProviderSites(eq("WELSTORY"), saved.capture(), eq("u"));
		Map<String, Object> rep = new HashMap<>();
		saved.getValue().forEach(site -> rep.put(site.get("external_site_code").toString(), site.get("representative_site_code")));
		assertThat(rep).containsEntry("A0374032", "A0275536").containsEntry("A0275536", "A0275536")
				.containsEntry("A0374231", "A0374231").containsEntry("A0374099", "");
	}

	@Test
	void catalogSyncQueriesAndStoresUnderRepresentativeSite() throws Exception {
		var mapper = mock(SupplierIntegrationMapper.class);
		var api = mock(WelstoryItemLookupService.class);
		when(mapper.supplierIdByCode(anyMap())).thenReturn(1L);
		// 거래처는 A0374032(일반)만 연결돼 있지만 판가는 대표 A0275536(사업장 마스터 99)으로
		when(mapper.priceSite(argThat(q -> "A0374032".equals(q.get("sold_to")))))
				.thenReturn(new HashMap<>(Map.of("external_site_code", "A0275536", "supplier_site_id", 99L)));
		when(mapper.supplierProductId(anyMap())).thenReturn(3L);
		when(api.call(anyString(), any())).thenReturn(json.readTree("""
				{"dataHeader":{"contYn":"N","nextKey":""},"dataBody":{"resCd":"S0000","data":[{"itemCode":"T1","itemName":"상품","unit":"EA","price":"1000"}]}}
				"""));
		var service = new WelstoryCatalogSyncService(mapper, api, json, mock(ReceiptPostingService.class), mock(SupplierSiteService.class));

		var result = service.syncCatalog(Map.of("account_id", "A", "sold_to", "A0374032", "period_group_year", "2026", "period_group", "16"));

		assertThat(result).containsEntry("sold_to", "A0275536").containsEntry("saved_count", 1);
		var request = ArgumentCaptor.forClass(JsonNode.class);
		verify(api).call(eq("/fdapi/service/payer-allitem-price"), request.capture());
		assertThat(request.getValue().path("dataBody").path("soldTo").asText()).isEqualTo("A0275536");
		verify(mapper).upsertOffer(argThat(row -> Long.valueOf(99L).equals(row.get("supplier_site_id"))));
	}

	@Test
	void unknownSiteIsRejected() {
		var mapper = mock(SupplierIntegrationMapper.class);
		when(mapper.supplierIdByCode(anyMap())).thenReturn(1L);
		var service = new WelstoryCatalogSyncService(mapper, mock(WelstoryItemLookupService.class), json,
				mock(ReceiptPostingService.class), mock(SupplierSiteService.class));
		assertThatThrownBy(() -> service.syncCatalog(Map.of("account_id", "A", "sold_to", "X", "period_group_year", "2026", "period_group", "16")))
				.hasMessageContaining("이 거래처에 지정된 웰스토리 사업장이 아닙니다");
	}

	static final Map<String, Object> SITE = Map.of("supplier_account_site_id", 3L, "supplier_site_id", 17L, "supplier_id", 1L,
			"account_id", "A", "external_site_code", "A0374032");

	JdbcTemplate dbWithRepresentative() {
		var jdbc = mock(JdbcTemplate.class);
		when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
		when(jdbc.queryForList(contains("m.representative_site_code"), eq(17L)))
				.thenReturn(List.of(Map.of("supplier_site_id", 99L, "external_site_code", "A0275536")));
		when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class))).thenReturn(List.of());
		return jdbc;
	}

	@Test
	void preparationLooksUpCycleAndFullSyncWithRepresentative() {
		var jdbc = dbWithRepresentative();
		var sync = mock(WelstoryCatalogSyncService.class);
		LocalDate from = LocalDate.of(2026, 11, 23), to = LocalDate.of(2026, 12, 13);
		when(sync.periodDates(eq("A0275536"), eq("2026"), eq("16"))).thenReturn(new LocalDate[] { from, to });
		when(sync.syncCatalog(any())).thenReturn(Map.of("saved_count", 10));
		var service = new WelstoryCycleSyncService(jdbc, sync);

		var cycle = service.ensureCycle(SITE, LocalDate.of(2026, 11, 30), "SYSTEM");

		assertThat(cycle.revision()).isEqualTo("2026-16");
		verify(sync, never()).periodDates(eq("A0374032"), any(), any());
		// 권한 확인은 원래 거래처로, 조회는 대표 사업장 코드로
		verify(sync).syncCatalog(argThat(c -> "A".equals(c.get("account_id")) && "A0275536".equals(c.get("sold_to"))));
		// 순기 기록도 대표 사업장(99)에
		verify(jdbc).update(contains("INSERT INTO tb_supplier_sync_checkpoint"), eq(1L), eq(99L), eq("CYCLE_2026-16"));
	}

	@Test
	void siteWithoutRepresentativeStaysAsIs() {
		var jdbc = mock(JdbcTemplate.class);
		when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
		var service = new WelstoryCycleSyncService(jdbc, mock(WelstoryCatalogSyncService.class));
		assertThat(service.priceSite(SITE)).isSameAs(SITE);
	}
}
