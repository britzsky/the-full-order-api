package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.reactive.function.client.WebClient;
import com.example.demo.mapper.AccountMenuRegistrationMapper;
import com.example.demo.mapper.AiUsageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 실제 OpenAI로 미등록 메뉴 레시피 초안 3개를 만들고(비용 수 원), 로컬 DB tb_ai_usage_log에 토큰이 기록되는지 확인한다.
 * 키는 application-secret.properties, 거래처 식재료는 로컬 DB(우리누리). 이력 행은 확인 후 롤백한다.
 * 실행: RUN_OPENAI_LIVE=true (gradle test --tests '*LocalOpenAiRecipeDraftLiveTest')
 */
@EnabledIfEnvironmentVariable(named = "RUN_OPENAI_LIVE", matches = "true")
@SuppressWarnings("unchecked")
class LocalOpenAiRecipeDraftLiveTest {
    static final String ACCOUNT = "20250819193526";

    @Test void draftsRealMenusAndLogsTokens() throws Exception {
        Path root = Path.of("src/main/resources");
        var props = new Properties();
        try (var reader = Files.newBufferedReader(root.resolve("application.properties"))) { props.load(reader); }
        for (String imp : props.getProperty("spring.config.import", "").split(",")) {
            var path = root.resolve(imp.trim().replace("optional:", "").replace("classpath:", "")).normalize();
            if (path.startsWith(root) && Files.isRegularFile(path)) try (var reader = Files.newBufferedReader(path)) { props.load(reader); }
        }
        String url = props.getProperty("spring.datasource.url");
        assertThat(url).startsWith("jdbc:mysql://localhost:3306/the_full_order?");
        try (var c = DriverManager.getConnection(url, props.getProperty("spring.datasource.username"), props.getProperty("spring.datasource.password"))) {
            c.setAutoCommit(false);
            var cfg = new Configuration(new Environment("openai-live", new JdbcTransactionFactory(), new SingleConnectionDataSource(c, true)));
            try (var in = Files.newInputStream(root.resolve("mybatis-mapper/AccountMenuRegistrationMapper.xml"))) {
                new XMLMapperBuilder(in, cfg, "mybatis-mapper/AccountMenuRegistrationMapper.xml", cfg.getSqlFragments()).parse();
            }
            cfg.addMapper(AiUsageMapper.class);
            try (var session = new SqlSessionFactoryBuilder().build(cfg).openSession(c)) {
                try {
                    var usage = session.getMapper(AiUsageMapper.class);
                    var client = new OpenAiClient(props.getProperty("openai.api.key"), props.getProperty("openai.model", "gpt-5.4-mini"), "low",
                        new BigDecimal("0.75"), new BigDecimal("0.075"), new BigDecimal("4.50"), BigDecimal.ZERO, WebClient.builder(), usage);
                    var service = new MenuRecipeDraftService(session.getMapper(AccountMenuRegistrationMapper.class), client, new ObjectMapper());
                    long started = System.currentTimeMillis();
                    var result = service.draft(Map.of("account_id", ACCOUNT, "menus", List.of("들깨미역국(5.6)", "제육볶음", "계란찜")), "live-test");
                    System.out.println("elapsed ms: " + (System.currentTimeMillis() - started));
                    var drafts = (List<Map<String, Object>>) result.get("drafts");
                    drafts.forEach(d -> System.out.println("DRAFT " + d));
                    assertThat(drafts).hasSize(3).allSatisfy(d -> assertThat((List<?>) d.get("ingredients")).isNotEmpty());

                    var rows = usage.list(Map.of("from", java.time.LocalDate.now().toString(), "to", java.time.LocalDate.now().toString(), "account_id", ACCOUNT));
                    var last = rows.get(0);
                    System.out.println("USAGE " + last);
                    assertThat(last).containsEntry("feature", "MENU_RECIPE_DRAFT").containsEntry("status", "SUCCESS");
                    assertThat(((Number) last.get("input_tokens")).intValue()).isPositive();
                    assertThat(((Number) last.get("output_tokens")).intValue()).isPositive();
                } finally {
                    c.rollback();
                }
            }
        }
    }
}
