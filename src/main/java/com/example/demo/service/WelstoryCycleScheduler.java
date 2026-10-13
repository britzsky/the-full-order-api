package com.example.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 웰스토리 순기 판가 새벽 배치. 가이드 권장 03시 이후(00:00~00:30 API 불가).
 * 입고 대사 배치(supplier.sync.scheduled.enabled)와 따로 켠다 — 이 배치는 판가만 받고 재고를 바꾸지 않는다.
 * 서버가 여러 대면 한 대에서만 켤 것.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "welstory.cycle-sync.enabled", havingValue = "true")
public class WelstoryCycleScheduler {
	private static final Logger log = LoggerFactory.getLogger(WelstoryCycleScheduler.class);
	private final WelstoryCycleSyncService cycles;

	public WelstoryCycleScheduler(WelstoryCycleSyncService cycles) {
		this.cycles = cycles;
	}

	@Scheduled(cron = "${welstory.cycle-sync.cron:0 20 3 * * *}", zone = "Asia/Seoul")
	public void run() {
		log.info("웰스토리 순기 판가 배치 결과: {}", cycles.syncAll("SYSTEM"));
	}
}
