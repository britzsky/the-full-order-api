package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 주간 사용 확정 미리보기가 실제 SQL로 차감을 계산한 뒤 DB에 아무것도 남기지 않는지(롤백) 확인한다.
 * 부족량 계산의 '지난 미확정 식사' 조회도 실제 SQL로 실행한다. 쓰기 없음(미리보기는 자체 롤백).
 */
@EnabledIfEnvironmentVariable(named="RUN_LOCAL_DB_TESTS", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
class LocalMealWeekPreviewDatabaseTest {
    @Autowired MealUsageService usage;
    @Autowired ShortageProcurementService shortages;
    @Autowired JdbcTemplate jdbc;

    @SuppressWarnings("unchecked")
    @Test void previewCalculatesDeductionsAndLeavesDatabaseUnchanged() {
        var target=jdbc.queryForList("""
                SELECT ms.account_id,ms.table_id FROM the_full_order.tb_account_meal_service ms
                JOIN the_full_order.tb_account_table_meals t ON t.table_id=ms.table_id AND t.account_id=ms.account_id AND t.status<>'CANCELED'
                WHERE ms.status NOT IN ('COMPLETED','CANCELED') AND ms.meal_date<=CURRENT_DATE
                  AND EXISTS(SELECT 1 FROM the_full_order.tb_account_meal_ingredient_requirement r WHERE r.meal_service_id=ms.meal_service_id)
                ORDER BY ms.meal_date DESC LIMIT 1""");
        assertThat(target).as("지난 날짜의 미확정 식단 필요").isNotEmpty();
        String account=target.get(0).get("account_id").toString(), table=target.get(0).get("table_id").toString();
        String before=fingerprint(account,table);

        var week=usage.week(Map.of("account_id",account,"table_id",table));
        var meals=(List<Map<String,Object>>)week.get("meals");
        assertThat(meals).anyMatch(m->Boolean.TRUE.equals(m.get("confirmable")));

        var preview=usage.preview(new HashMap<>(Map.of("account_id",account,"table_id",table,"user_id","preview-test")));
        var ingredients=(List<Map<String,Object>>)preview.get("ingredients");
        var problems=(List<Map<String,Object>>)preview.get("problems");
        assertThat(ingredients).as("식재료별 차감 요약").isNotEmpty();
        assertThat(ingredients).allSatisfy(r->assertThat(r).containsKeys("ingredient_name","required_qty","stock_before","stock_after","shortage_qty"));
        System.out.printf("MEAL_PREVIEW account=%s table=%s meals=%d ingredients=%d problems=%d unreceived=%d can_confirm=%s%n",account,table,
                ((List<?>)preview.get("meals")).size(),ingredients.size(),problems.size(),((List<?>)preview.get("unreceived_orders")).size(),preview.get("can_confirm"));
        problems.stream().limit(3).forEach(p->System.out.println("MEAL_PREVIEW_PROBLEM "+p.get("meal")+" "+p.get("message")));

        assertThat(fingerprint(account,table)).as("미리보기 후 재고·사용 이력·식사 상태 그대로").isEqualTo(before);

        // 부족량 계산: 지난 미확정 식사 사용량 조회 SQL 실행(값은 데이터에 따라 0일 수 있음)
        var result=shortages.shortages(new HashMap<>(Map.of("account_id",account,"source","STOCK","include_sufficient",true)));
        var rows=(List<Map<String,Object>>)result.get("ingredients");
        assertThat(rows).allSatisfy(r->assertThat(r).containsKeys("unconfirmed_usage_qty","available_qty"));
        System.out.println("MEAL_PREVIEW_SHORTAGE rows="+rows.size()+" with_unconfirmed="+rows.stream().filter(r->new java.math.BigDecimal(r.get("unconfirmed_usage_qty").toString()).signum()>0).count());
    }

    private String fingerprint(String account,String table) {
        return jdbc.queryForObject("""
                SELECT CONCAT_WS('|',
                  (SELECT COALESCE(SUM(current_base_qty),0) FROM the_full_order.tb_account_inventory_balance WHERE account_id=?),
                  (SELECT COALESCE(SUM(remaining_base_qty),0) FROM the_full_order.tb_account_inventory_lot WHERE account_id=?),
                  (SELECT COUNT(*) FROM the_full_order.tb_account_meal_stock_usage WHERE account_id=?),
                  (SELECT COUNT(*) FROM the_full_order.tb_account_inventory_movement WHERE account_id=?),
                  (SELECT GROUP_CONCAT(status ORDER BY meal_service_id) FROM the_full_order.tb_account_meal_service WHERE account_id=? AND table_id=?))""",
                String.class,account,account,account,account,account,table);
    }
}
