package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.example.demo.mapper.AccountMenuRecipeMapper;
import com.example.demo.mapper.AccountMenuRegistrationMapper;
import com.example.demo.mapper.MenuMapper;

/** 실제 로컬 SQL로 거래처 전용 메뉴 등록 → 목록 노출 → 본사 메뉴 가져오기 → 본사 반영. 합성 행만 쓰고 항상 롤백한다. */
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_DB_TESTS", matches = "true")
@SuppressWarnings("unchecked")
class LocalAccountMenuRegistrationDatabaseTest {
    @Test void registerImportAndPromoteAgainstLocalSchema() throws Exception {
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
            var cfg = new Configuration(new Environment("menu-registration-rollback", new JdbcTransactionFactory(), new SingleConnectionDataSource(c, true)));
            for (String name : List.of("MenuMapper", "AccountMenuRecipeMapper", "AccountMenuRegistrationMapper"))
                try (var in = Files.newInputStream(root.resolve("mybatis-mapper/" + name + ".xml"))) {
                    new XMLMapperBuilder(in, cfg, "mybatis-mapper/" + name + ".xml", cfg.getSqlFragments()).parse();
                }
            try (var session = new SqlSessionFactoryBuilder().build(cfg).openSession(c)) {
                try {
                    String id = "REG" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                    String standardMenuId = "S" + id.substring(3);
                    write(c, "INSERT INTO tb_ingredient_master(ingredient_id,ingredient_name_raw,ingredient_name_std,base_unit) VALUES(?,?,?,'ml')", id, "우유" + id, "우유" + id);
                    write(c, "INSERT INTO tb_menu_master(menu_id,menu_name,food_type,calories_per_serving) VALUES(?,?,1,300)", standardMenuId, "본사메뉴" + id);
                    write(c, "INSERT INTO tb_recipe_detail(recipe_id,menu_id,ingredient_id,qty_num,qty_unit,qty_base,base_unit,qty_per_person) VALUES(7,?,?,200,'ml',200,'ml',200)", standardMenuId, id);

                    var recipeMapper = session.getMapper(AccountMenuRecipeMapper.class);
                    var service = new AccountMenuRegistrationService(session.getMapper(AccountMenuRegistrationMapper.class), recipeMapper, new AccountMenuRecipeService(recipeMapper));
                    var body = new HashMap<String, Object>(Map.of("account_id", id, "menu_name", "크림스프" + id, "menu_name_raw", "크림스프",
                        "calories_per_serving", 150, "menu_type", 2, "menu_gubun", 13, "food_type", 6, "user_id", "test",
                        "ingredients", List.of(new HashMap<>(Map.of("ingredient_id", id, "qty_num", 150, "qty_unit", "ml")))));
                    String menuId = service.register(body).get("menu_id").toString();
                    assertThat(menuId).startsWith("AM-");
                    assertThat(one(c, "SELECT qty_per_person FROM tb_account_recipe_detail WHERE account_id=? AND menu_id=?", id, menuId)).isEqualTo("150.000");
                    assertThat(one(c, "SELECT base_unit FROM tb_account_ingredient_master WHERE account_id=? AND ingredient_id=?", id, id)).isEqualTo("ml");

                    // 거래처 메뉴 목록(LEFT JOIN)에 본사 메뉴 없이도 나온다
                    var list = session.getMapper(MenuMapper.class).AccountMenuList(new HashMap<>(Map.of("account_id", id)));
                    assertThat(list).anySatisfy(m -> { assertThat(m.get("menu_id")).isEqualTo(menuId); assertThat(m.get("standard_yn")).isEqualTo("N"); });
                    assertThatThrownBy(() -> service.register(body)).hasMessageContaining("같은 이름");

                    var imported = service.importStandard(Map.of("account_id", id, "menu_id", standardMenuId, "user_id", "test"));
                    assertThat(imported.get("copied_ingredient_count")).isEqualTo(1);
                    assertThat(one(c, "SELECT calories_per_serving FROM tb_account_menu_master WHERE account_id=? AND menu_id=?", id, standardMenuId)).isEqualTo("300.00");

                    // 거래처에서 직접 추가한 식재료 두 개: 본사에 같은 이름·단위가 있는 것(MATCHED)과 없는 것(NEW)
                    write(c, "INSERT INTO tb_ingredient_master(ingredient_id,ingredient_name_raw,ingredient_name_std,base_unit) VALUES(?,?,?,'g')", "B" + id.substring(3), "버터" + id, "버터" + id);
                    String butter = service.registerIngredient(Map.of("account_id", id, "ingredient_name", "버터" + id, "base_unit", "g")).get("ingredient_id").toString();
                    String gungchae = service.registerIngredient(Map.of("account_id", id, "ingredient_name", "궁채" + id, "base_unit", "g", "category_name", "채소")).get("ingredient_id").toString();
                    assertThat(butter).startsWith("AI-");
                    assertThat(one(c, "SELECT COUNT(*) FROM tb_ingredient_master WHERE ingredient_id=?", gungchae)).isEqualTo("0");
                    var mixed = new HashMap<String, Object>(body);
                    mixed.put("menu_name", "버터궁채볶음" + id);
                    mixed.put("ingredients", List.of(new HashMap<>(Map.of("ingredient_id", id, "qty_num", 10, "qty_unit", "ml")),
                        new HashMap<>(Map.of("ingredient_id", butter, "qty_num", 5, "qty_unit", "g")),
                        new HashMap<>(Map.of("ingredient_id", gungchae, "qty_num", 40, "qty_unit", "g"))));
                    String mixedId = service.register(mixed).get("menu_id").toString();
                    var preview = (List<Map<String, Object>>) service.promotePreview(Map.of("account_id", id, "menu_id", mixedId)).get("ingredients");
                    assertThat(preview).extracting(r -> r.get("action")).containsExactly("SAME", "MATCHED", "NEW");
                    service.promote(Map.of("account_id", id, "menu_id", mixedId, "user_id", "test"));
                    assertThat(one(c, "SELECT GROUP_CONCAT(ingredient_id ORDER BY ingredient_seq) FROM tb_recipe_detail WHERE menu_id=?", mixedId))
                        .isEqualTo(id + ",B" + id.substring(3) + "," + gungchae);
                    assertThat(one(c, "SELECT CONCAT(ingredient_name_std,'/',base_unit,'/',category_name) FROM tb_ingredient_master WHERE ingredient_id=?", gungchae)).isEqualTo("궁채" + id + "/g/채소");

                    service.promote(Map.of("account_id", id, "menu_id", menuId, "user_id", "test"));
                    assertThat(one(c, "SELECT menu_name FROM tb_menu_master WHERE menu_id=?", menuId)).isEqualTo("크림스프" + id);
                    assertThat(one(c, "SELECT COUNT(*) FROM tb_recipe_detail WHERE menu_id=?", menuId)).isEqualTo("1");
                    assertThatThrownBy(() -> service.promote(Map.of("account_id", id, "menu_id", menuId))).hasMessageContaining("이미 본사");
                } finally {
                    c.rollback();
                }
            }
        }
    }

    private static void write(Connection c, String sql, Object... values) throws SQLException {
        try (var s = c.prepareStatement(sql)) { for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]); s.executeUpdate(); }
    }

    private static String one(Connection c, String sql, Object... values) throws SQLException {
        try (var s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
            try (var r = s.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }
}
