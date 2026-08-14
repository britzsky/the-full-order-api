package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class WelstoryGuidGeneratorTest {

	@Test
	void createsNineteenCharacterUniqueGuid() {
		Clock clock = Clock.fixed(Instant.parse("2024-01-15T06:01:01.001Z"), ZoneId.of("Asia/Seoul"));
		WelstoryGuidGenerator generator = new WelstoryGuidGenerator(clock);

		assertThat(generator.next()).isEqualTo("2024011515010100101");
		assertThat(generator.next()).isEqualTo("2024011515010100102");
	}

	@Test
	void rejectsMoreThanNinetyNineRequestsInSameMillisecond() {
		Clock clock = Clock.fixed(Instant.parse("2024-01-15T06:01:01.001Z"), ZoneId.of("Asia/Seoul"));
		WelstoryGuidGenerator generator = new WelstoryGuidGenerator(clock);
		for (int index = 0; index < 99; index++) generator.next();

		assertThatThrownBy(generator::next)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("GUID 한도");
	}
}
