package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 로컬 DB + 실제 ERP(조회만): 저장된 식단표로 서류 세 가지를 만들고, 거래처들의 ERP 끼니 시간 해석 결과를 출력한다.
 * 결과 파일은 build/meal-documents/local-*. 실행: RUN_LOCAL_DB_TESTS=true
 */
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LocalMealDocumentDatabaseTest {
	@Autowired JdbcTemplate jdbc;
	@Autowired MealDocumentService documents;
	@Autowired ErpAccountClient erp;

	@Test
	void buildsDocumentsFromSavedPlan() throws Exception {
		List<Map<String, Object>> plans = jdbc.queryForList("""
				SELECT t.account_id,t.table_id FROM the_full_order.tb_account_table_meals t
				 WHERE EXISTS(SELECT 1 FROM the_full_order.tb_account_meal_service s WHERE s.table_id=t.table_id)
				 ORDER BY (SELECT COUNT(*) FROM the_full_order.tb_account_table_meals_detail d WHERE d.table_id=t.table_id) DESC LIMIT 1""");
		if (plans.isEmpty()) return;
		String account = plans.get(0).get("account_id").toString(), table = plans.get(0).get("table_id").toString();
		Path dir = Path.of("build", "meal-documents");
		Files.createDirectories(dir);
		for (String type : List.of("cooking-plan", "operation-log", "retention-label")) {
			MealDocumentService.Document doc = documents.build(type, account, table);
			assertTrue(doc.bytes().length > 0);
			Files.write(dir.resolve("local-" + doc.fileName()), doc.bytes());
			System.out.println("[서류] " + doc.fileName() + " " + doc.bytes().length + " bytes");
		}
	}

	@Test
	void printsErpMealTimes() {
		for (String account : jdbc.queryForList("SELECT DISTINCT account_id FROM the_full_order.tb_account_meal_slot_setting ORDER BY account_id LIMIT 40", String.class))
			System.out.println("[ERP 끼니 시간] " + account + " " + erp.mealTimes(account));
	}
}
