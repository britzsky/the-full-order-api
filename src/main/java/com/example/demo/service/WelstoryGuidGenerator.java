package com.example.demo.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

/** Generates the 19-character transaction id required by the Welstory gateway. */
@Component
public class WelstoryGuidGenerator {

	private static final DateTimeFormatter FORMATTER =
			DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

	private final Clock clock;
	private long lastEpochMilli = -1;
	private int sequence;

	public WelstoryGuidGenerator() {
		this(Clock.systemDefaultZone());
	}

	WelstoryGuidGenerator(Clock clock) {
		this.clock = clock;
	}

	public synchronized String next() {
		long epochMilli = clock.millis();
		if (epochMilli == lastEpochMilli) {
			sequence++;
			if (sequence > 99) {
				throw new IllegalStateException("동일 밀리초에 생성할 수 있는 웰스토리 GUID 한도를 초과했습니다.");
			}
		} else {
			lastEpochMilli = epochMilli;
			sequence = 1;
		}

		Instant instant = Instant.ofEpochMilli(epochMilli);
		ZoneId zone = clock.getZone();
		return FORMATTER.format(instant.atZone(zone)) + String.format("%02d", sequence);
	}
}
