package com.example.demo.service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 밤 구매 이력 자동 가져오기(2026-11-27 사용자 요청: 고객사마다 누르지 않아도 저녁 10시에, 9월 1일부터).
 * 처리는 PurchaseHistoryImportService.runNightly — 사업장마다 받은 데까지 기록해 이어서 받는다.
 * 서버가 여러 대면 한 대에서만 켤 것.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "purchase-history.nightly.enabled", havingValue = "true")
public class PurchaseHistoryNightlyScheduler {
	private static final Logger log = LoggerFactory.getLogger(PurchaseHistoryNightlyScheduler.class);
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private final PurchaseHistoryImportService history;
	private final LocalTime stopAt;

	public PurchaseHistoryNightlyScheduler(PurchaseHistoryImportService history,
			@Value("${purchase-history.nightly.stop-at:06:00}") String stopAt) {
		this.history = history;
		this.stopAt = LocalTime.parse(stopAt.trim());
	}

	@Scheduled(cron = "${purchase-history.nightly.cron:0 0 22 * * *}", zone = "Asia/Seoul")
	public void run() {
		LocalDateTime now = LocalDateTime.now(SEOUL);
		log.info("밤 구매 이력 시작");
		log.info("밤 구매 이력 결과: {}", history.runNightly(stopTime(now, stopAt)));
	}

	/** 시작 뒤 처음 오는 stopAt(22시에 시작하면 다음 날 06시) */
	static LocalDateTime stopTime(LocalDateTime now, LocalTime stopAt) {
		LocalDateTime today = LocalDateTime.of(now.toLocalDate(), stopAt);
		return today.isAfter(now) ? today : LocalDateTime.of(now.toLocalDate().plusDays(1), stopAt);
	}
}
