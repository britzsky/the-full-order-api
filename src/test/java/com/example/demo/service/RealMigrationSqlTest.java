package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 운영 반영 SQL(ddl-source/real_migrations/*.sql)이 정본 CREATE와 같은 결과를 내는지 확인한다.
 * <ul>
 * <li>항상: 반영 SQL의 CREATE TABLE·ADD COLUMN·ADD KEY·MODIFY가 정본에 똑같이 있는지(정본 갱신 누락을 잡는다).</li>
 * <li>RUN_REAL_MIGRATION_DB=true: 로컬 MySQL 임시 스키마 두 개(정본 완성본 / 반영 전 상태)를 만들고, 반영 전 상태에 모든 반영 SQL을
 * 두 번씩 실행한 뒤 테이블·칸·인덱스·FK가 완성본과 같은지 비교하고 지운다. 로컬 the_full_order·운영 RDS는 건드리지 않는다.</li>
 * </ul>
 */
class RealMigrationSqlTest {
	static final Path DIR = Path.of("ddl-source", "real_migrations");
	static final Pattern CREATE = Pattern.compile("CREATE TABLE\\s+(?:IF NOT EXISTS\\s+)?(?:\\w+\\.)?(\\w+)\\s*\\(.*?\\)\\s*ENGINE[^;]*;", Pattern.DOTALL);
	static final Pattern ALTER = Pattern.compile("ALTER TABLE\\s+(\\w+)\\s+(.*?)(?:\"\\);|;)", Pattern.DOTALL);
	static final Pattern ADD_COLUMN = Pattern.compile("ADD COLUMN\\s+(\\w+)\\s+(.*?)\\s+AFTER\\s+\\w+");
	static final Pattern MODIFY = Pattern.compile("MODIFY\\s+(?:COLUMN\\s+)?(\\w+)\\s+(.*)", Pattern.DOTALL);
	static final Pattern ADD_KEY = Pattern.compile("ADD (UNIQUE )?KEY\\s+(\\w+)\\s*(\\([^)]*\\))");

	static List<Path> migrations() throws Exception {
		try (Stream<Path> s = Files.list(DIR)) {
			return s.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
		}
	}

	static String read(Path p) throws Exception {
		return Files.readString(p, StandardCharsets.UTF_8);
	}

	static String norm(String s) {
		return s.replace("IF NOT EXISTS ", "").replaceAll("\\b(the_full_order_real|the_full_order)\\.", "")
				.replaceAll("\\s+", " ").replaceAll("\\s*,\\s*$", "").trim();
	}

	/** 정본 CREATE 블록(테이블 이름 → 문장) */
	static Map<String, String> canonicalTables() throws Exception {
		Map<String, String> out = new LinkedHashMap<>();
		Matcher m = CREATE.matcher(read(Path.of("ddl-source", "the_full_order_schema_fixed_collation_test.sql")));
		while (m.find())
			out.put(m.group(1), m.group());
		return out;
	}

	static String canonicalColumn(Map<String, String> tables, String table, String column) {
		String create = tables.get(table);
		assertThat(create).as("정본에 테이블 %s 없음", table).isNotNull();
		Matcher m = Pattern.compile("(?m)^\\s*" + column + "\\s+(.*?),?\\s*$").matcher(create);
		assertThat(m.find()).as("정본 %s에 칸 %s 없음", table, column).isTrue();
		return norm(m.group(1));
	}

	@Test
	void migrationsMatchCanonical() throws Exception {
		Map<String, String> tables = canonicalTables();
		List<Path> files = migrations();
		assertThat(files).isNotEmpty();
		for (Path f : files) {
			String sql = read(f);
			assertThat(sql).as(f + ": 운영 스키마 USE").contains("USE the_full_order_real;");
			assertThat(Pattern.compile("\\bthe_full_order\\b(?!_real)").matcher(sql.replaceAll("(?m)^--.*$", "")).find())
					.as(f + ": the_full_order 남음").isFalse();
			Matcher c = CREATE.matcher(sql);
			while (c.find()) {
				assertThat(c.group()).as(f + ": 다시 실행 안전(IF NOT EXISTS)").contains("IF NOT EXISTS");
				assertThat(tables).as(f + ": 정본에 " + c.group(1) + " 없음").containsKey(c.group(1));
				assertThat(norm(c.group())).as(f + ": " + c.group(1) + " 정의가 정본과 다름").isEqualTo(norm(tables.get(c.group(1))));
			}
			Matcher a = ALTER.matcher(sql);
			while (a.find()) {
				String table = a.group(1), body = a.group(2);
				Matcher col = ADD_COLUMN.matcher(body);
				while (col.find())
					assertThat(norm(col.group(2))).as(f + ": " + table + "." + col.group(1) + " 정의가 정본과 다름")
							.isEqualTo(canonicalColumn(tables, table, col.group(1)));
				Matcher key = ADD_KEY.matcher(body);
				while (key.find())
					assertThat(norm(tables.get(table))).as(f + ": " + table + " 인덱스 " + key.group(2) + "가 정본에 없음")
							.contains(norm((key.group(1) == null ? "" : key.group(1)) + "KEY " + key.group(2) + " " + key.group(3)));
				Matcher mod = MODIFY.matcher(body);
				if (mod.find())
					assertThat(norm(mod.group(1) + " " + mod.group(2))).as(f + ": " + table + " MODIFY가 정본과 다름")
							.isEqualTo(mod.group(1) + " " + canonicalColumn(tables, table, mod.group(1)));
				if (body.contains("ADD COLUMN") || body.contains("ADD KEY") || body.contains("ADD UNIQUE"))
					assertThat(sql.substring(Math.max(0, a.start() - 400), a.start())).as(f + ": " + table + " ADD는 information_schema로 있을 때 건너뛰어야 함")
							.contains("information_schema");
			}
		}
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "RUN_REAL_MIGRATION_DB", matches = "true")
	void migrationsReachCanonicalOnLocalMysql() throws Exception {
		Path root = Path.of("src/main/resources");
		Properties props = new Properties();
		try (var r = Files.newBufferedReader(root.resolve("application.properties_local"))) { props.load(r); }
		try (var r = Files.newBufferedReader(root.resolve("application-secret.properties"))) { props.load(r); }
		String url = props.getProperty("spring.datasource.url");
		assertThat(url).startsWith("jdbc:mysql://localhost:3306/the_full_order?");
		String server = url.replace("/the_full_order?", "/?allowMultiQueries=true&");
		String user = props.getProperty("spring.datasource.username"), pw = props.getProperty("spring.datasource.password");
		String ddl = read(Path.of("ddl-source", "the_full_order_real_ddl.sql"));
		String full = "tfo_mig_full", old = "tfo_mig_old";
		try (Connection c = DriverManager.getConnection(server, user, pw); Statement st = c.createStatement()) {
			try {
				for (String s : List.of(full, old)) {
					st.execute("DROP DATABASE IF EXISTS " + s);
					st.execute("CREATE DATABASE " + s + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
					exec(st, ddl.replace("the_full_order_real", s));
				}
				exec(st, "USE " + old + "; SET FOREIGN_KEY_CHECKS=0;" + String.join(";", rollback()) + "; SET FOREIGN_KEY_CHECKS=1;");
				assertThat(shape(st, old)).as("반영 전 상태가 완성본과 같으면 검사가 의미 없음").isNotEqualTo(shape(st, full));
				for (int round = 0; round < 2; round++)
					for (Path f : migrations())
						exec(st, read(f).replace("the_full_order_real", old));
				List<String> a = shape(st, full), b = shape(st, old);
				List<String> missing = new ArrayList<>(a), extra = new ArrayList<>(b);
				missing.removeAll(b);
				extra.removeAll(a);
				assertThat(missing).as("반영 SQL로 만들어지지 않은 것").isEmpty();
				assertThat(extra).as("정본과 다르게 만들어진 것").isEmpty();
			} finally {
				st.execute("DROP DATABASE IF EXISTS " + full);
				st.execute("DROP DATABASE IF EXISTS " + old);
			}
		}
	}

	/** 반영 SQL이 만드는 것을 거꾸로 지워 '반영 전' 상태를 만든다(MODIFY는 되돌리지 않음). */
	static List<String> rollback() throws Exception {
		List<String> tables = new ArrayList<>(), alters = new ArrayList<>();
		for (Path f : migrations()) {
			String sql = read(f);
			Matcher c = CREATE.matcher(sql);
			while (c.find())
				tables.add("DROP TABLE IF EXISTS " + c.group(1));
			Matcher a = ALTER.matcher(sql);
			while (a.find()) {
				List<String> parts = new ArrayList<>();
				Matcher col = ADD_COLUMN.matcher(a.group(2));
				while (col.find())
					parts.add("DROP COLUMN " + col.group(1));
				Matcher key = ADD_KEY.matcher(a.group(2));
				while (key.find())
					parts.add(0, "DROP INDEX " + key.group(2));
				if (!parts.isEmpty())
					alters.add("ALTER TABLE " + a.group(1) + " " + String.join(", ", parts));
			}
		}
		Collections.reverse(tables);
		Collections.reverse(alters);
		List<String> out = new ArrayList<>(tables);
		out.addAll(alters);
		return out;
	}

	static void exec(Statement st, String sql) throws Exception {
		st.execute(sql);
		while (st.getMoreResults() || st.getUpdateCount() != -1) { /* 다중 문장 결과 소비 */ }
	}

	/** 테이블·칸·인덱스·FK를 비교용 문자열로 */
	static List<String> shape(Statement st, String schema) throws Exception {
		TreeSet<String> out = new TreeSet<>();
		String[] queries = {
				"SELECT CONCAT_WS('|','T',table_name,table_comment) FROM information_schema.tables WHERE table_schema='%s'",
				"SELECT CONCAT_WS('|','C',table_name,ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'<null>'),extra,column_comment) FROM information_schema.columns WHERE table_schema='%s'",
				"SELECT CONCAT_WS('|','I',table_name,index_name,non_unique,seq_in_index,column_name) FROM information_schema.statistics WHERE table_schema='%s'",
				"SELECT CONCAT_WS('|','F',table_name,constraint_name,referenced_table_name,delete_rule,update_rule) FROM information_schema.referential_constraints WHERE constraint_schema='%s'" };
		for (String q : queries)
			try (var rs = st.executeQuery(String.format(q, schema))) {
				while (rs.next())
					out.add(rs.getString(1));
			}
		return new ArrayList<>(out);
	}
}
