package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * 운영 스키마(the_full_order_real)용 SQL이 정본과 같은지 확인한다.
 * 실패하면 정본을 고친 뒤 운영용을 다시 만들지 않은 것 → py -I scripts/real_db/build_real_sql.py
 */
class RealSchemaSqlTest {
	private static final Pattern SCHEMA_WORD = Pattern.compile("\\bthe_full_order\\b");
	private static final Pattern USE = Pattern.compile("(?m)^USE\\s+(\\w+)\\s*;");

	private static String read(String name) throws Exception {
		return Files.readString(Path.of("ddl-source", name), StandardCharsets.UTF_8);
	}

	private static String toReal(String text) {
		return SCHEMA_WORD.matcher(text).replaceAll("the_full_order_real");
	}

	private static void assertOnlyRealSchema(String text) {
		assertThat(SCHEMA_WORD.matcher(text).find()).as("the_full_order 가 남아 있음").isFalse();
		Matcher m = USE.matcher(text);
		int count = 0;
		while (m.find()) {
			assertThat(m.group(1)).isEqualTo("the_full_order_real");
			count++;
		}
		assertThat(count).isPositive();
	}

	@Test
	void realDdlMatchesCanonical() throws Exception {
		String real = read("the_full_order_real_ddl.sql");
		assertThat(real).endsWith(toReal(read("the_full_order_schema_fixed_collation_test.sql")));
		assertOnlyRealSchema(real);
	}

	@Test
	void realDistributionDdlMatchesCanonical() throws Exception {
		String real = read("the_full_order_real_distribution_ddl.sql");
		assertThat(real).endsWith(toReal(read("the_full_order_distribution_ddl.sql")));
		assertOnlyRealSchema(real);
	}

	@Test
	void realSeedHasBackdataOnly() throws Exception {
		String seed = read("the_full_order_seed_testdata_fixed_utf8mb4_real.sql");
		int start = seed.indexOf("/* === BACKDATA START");
		int end = seed.indexOf("/* 테스트 거래처 시나리오용 목록");
		String backdata = toReal(seed.substring(start, end).stripTrailing());

		String real = read("the_full_order_real_seed.sql");
		assertThat(real).contains(backdata).doesNotContain("20250819193455", "TEST_", "tmp_seed");
		assertOnlyRealSchema(real);
	}
}
