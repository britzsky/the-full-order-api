package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class HygieneServiceTest {
    final ErpHygieneClient erp = mock(ErpHygieneClient.class);
    final HygieneService service = new HygieneService(erp);

    @SuppressWarnings("unchecked")
    @Test void newRecordIsPinnedToAccountWithAuthor() {
        service.saveRecords("A", List.of(Map.of("account_id", "OTHER", "problem_note", "후드 청소 미흡", "category_key", "C1")), "worker", false);
        ArgumentCaptor<List<Map<String, Object>>> rows = ArgumentCaptor.forClass(List.class);
        verify(erp).saveRecords(rows.capture());
        Map<String, Object> row = rows.getValue().get(0);
        assertThat(row).containsEntry("account_id", "A").containsEntry("reg_user_id", "worker").containsEntry("mod_user_id", "worker")
                .containsEntry("del_yn", "N").containsEntry("category_key", "C1").containsKey("reg_dt");
        assertThat(row.get("idx")).isNull();
        verify(erp, never()).records(any(), any(), any()); // 새 기록은 소유 확인 조회 없음
    }

    @Test void existingRecordMustBelongToAccount() {
        when(erp.records("A", "", "")).thenReturn(List.of(Map.of("idx", 95)));
        assertThatThrownBy(() -> service.saveRecords("A", List.of(Map.of("idx", 777, "clean_note", "조치")), "worker", false))
                .hasMessageContaining("이 거래처의 점검 기록이 아닙니다");
        verify(erp, never()).saveRecords(anyList());
        service.saveRecords("A", List.of(Map.of("idx", 95, "clean_note", "온도계 교체")), "worker", false);
        verify(erp).saveRecords(argThat(rows -> Long.valueOf(95).equals(rows.get(0).get("idx")) && !rows.get(0).containsKey("reg_dt")));
    }

    @Test void onlyHeadquartersDeletes() {
        when(erp.records("A", "", "")).thenReturn(List.of(Map.of("idx", 95)));
        assertThatThrownBy(() -> service.saveRecords("A", List.of(Map.of("idx", 95, "del_yn", "Y")), "worker", false))
                .hasMessageContaining("본사 담당자만");
        service.saveRecords("A", List.of(Map.of("idx", 95, "del_yn", "Y")), "hq", true);
        verify(erp).saveRecords(argThat(rows -> "Y".equals(rows.get(0).get("del_yn"))));
    }

    @Test void newRecordNeedsProblem() {
        assertThatThrownBy(() -> service.saveRecords("A", List.of(Map.of("note", "비고만")), "worker", false)).hasMessageContaining("부적합");
    }

    @SuppressWarnings("unchecked")
    @Test void categoriesGetKeysOrderAndDefaults() {
        when(erp.categories("A")).thenReturn(List.of());
        service.saveCategories("A", List.of(Map.of("category_key", "OLD", "category_name", "냉장고"), Map.of("category_name", "후드")));
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(erp).saveCategories(body.capture());
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.getValue().get("categories");
        assertThat(rows).extracting(r -> r.get("sort_no")).containsExactly(1, 2);
        assertThat(rows.get(0)).containsEntry("category_key", "OLD").containsEntry("use_yn", "Y").containsEntry("main_yn", "N");
        assertThat((String) rows.get(1).get("category_key")).startsWith("C");
        assertThatThrownBy(() -> service.saveCategories("A", List.of(Map.of("category_name", "후드"), Map.of("category_name", "후드")))).hasMessageContaining("같은 이름");
        assertThatThrownBy(() -> service.saveCategories("A", List.of())).hasMessageContaining("1개 이상");
    }

    @Test void photoMustBeImage() {
        assertThatThrownBy(() -> service.uploadPhoto("A", new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[] { 1 })))
                .hasMessageContaining("이미지");
        when(erp.uploadPhoto(any(), eq("a.jpg"), eq("image/jpeg"), anyString(), eq("A"))).thenReturn("/image/hygiene/2026-10-26/A/x_a.jpg");
        when(erp.baseUrl()).thenReturn("https://thefull.kr/api");
        assertThat(service.uploadPhoto("A", new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[] { 1 })))
                .containsEntry("image_path", "/image/hygiene/2026-10-26/A/x_a.jpg");
    }
}
