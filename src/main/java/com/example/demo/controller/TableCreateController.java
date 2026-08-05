package com.example.demo.controller;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.springframework.web.bind.annotation.RestController;

@RestController
public class TableCreateController {
	public static String MenuTableCreator(String accountId) {
		
        String url = "jdbc:mysql://localhost:3306/the_full_order?serverTimezone=UTC&characterEncoding=UTF-8";
        String user = "root";
        String password = "Sk20150115!";
        String prefix = "tb_";
        String prefix2 = "_menu_master";   // 상황에 따라 바뀌는 값
        String tableName = prefix + accountId + prefix2;

        // 테이블명 검증
        validateTableName(tableName);

        String sql = """
            CREATE TABLE IF NOT EXISTS `%s` (
        	  account_id varchar(20) NOT NULL,
              menu_id varchar(20) NOT NULL,
			  source_row int DEFAULT NULL,
			  menu_name_raw varchar(255) DEFAULT NULL,
			  menu_name varchar(255) NOT NULL,
			  menu_type_raw varchar(100) DEFAULT NULL,
			  month_raw varchar(20) DEFAULT NULL,
			  month_num tinyint DEFAULT NULL,
			  food_type tinyint NOT NULL,
			  food_type_reason varchar(300) DEFAULT NULL,
			  food_type_confidence varchar(20) DEFAULT NULL,
			  diners decimal(10,2) DEFAULT NULL,
			  per_person_total_raw decimal(12,3) DEFAULT NULL,
			  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
			  del_yn` varchar(1) DEFAULT 'N' COMMENT '삭제여부',
			  PRIMARY KEY (menu_id),
			  KEY fk_menu_food_type (food_type),
			  CONSTRAINT fk_menu_food_type FOREIGN KEY (food_type) REFERENCES code_food_type (food_type)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
            """.formatted(tableName);
        
        String result = "";

        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement()) {

            boolean existedBefore = existsTable(conn, tableName);

            stmt.execute(sql);

            if (existedBefore) {
            	result = "400";
            } else {
            	result = "200";
            }
            
            return result;

        } catch (SQLException e) {
            e.printStackTrace();
            return result;
        }
    }
	
	public static String MenuDetailTableCreator(String accountId) {
		
        String url = "jdbc:mysql://localhost:3306/the_full_order?serverTimezone=UTC&characterEncoding=UTF-8";
        String user = "root";
        String password = "Sk20150115!";
        String prefix = "tb_";
        String prefix2 = "_recipe_detail";   // 상황에 따라 바뀌는 값
        String tableName = prefix + accountId + prefix2;

        // 테이블명 검증
        validateTableName(tableName);

        String sql = """
            CREATE TABLE IF NOT EXISTS `%s` (
        	  recipe_id bigint NOT NULL,
			  menu_id varchar(20) NOT NULL,
			  ingredient_id varchar(20) NOT NULL,
			  ingredient_seq int NOT NULL,
			  ingredient_name_raw varchar(255) NOT NULL,
			  qty_raw varchar(50) DEFAULT NULL,
			  qty_num decimal(12,3) DEFAULT NULL,
			  qty_unit varchar(30) DEFAULT NULL,
			  review_flag varchar(50) DEFAULT NULL,
			  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
			  PRIMARY KEY (recipe_id),
			  KEY fk_recipe_menu (menu_id),
			  KEY fk_recipe_ingredient (ingredient_id),
			  CONSTRAINT fk_recipe_ingredient FOREIGN KEY (ingredient_id) REFERENCES ingredient_master (ingredient_id),
			  CONSTRAINT fk_recipe_menu FOREIGN KEY (menu_id) REFERENCES menu_master (menu_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
            """.formatted(tableName);
        
        String result = "";

        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement()) {

            boolean existedBefore = existsTable(conn, tableName);

            stmt.execute(sql);

            if (existedBefore) {
            	result = "400";
            } else {
            	result = "200";
            }
            
            return result;

        } catch (SQLException e) {
            e.printStackTrace();
            return result;
        }
    }


    private static boolean existsTable(Connection conn, String tableName) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getTables(conn.getCatalog(), null, tableName, new String[]{"TABLE"})) {
            return rs.next();
        }
    }
    
    private static void validateTableName(String tableName) {
        // 영문, 숫자, 언더바만 허용
        if (tableName == null || !tableName.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("잘못된 테이블명: " + tableName);
        }
    }
}
