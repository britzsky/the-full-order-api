package com.example.demo.service;

import java.time.LocalTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.example.demo.mapper.SupplierIntegrationMapper;

/** 검증 후 설정으로 활성화하는 상품·미입고 대사 배치. 이미 연결된 사업장과 순기만 처리한다. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "supplier.sync.scheduled.enabled", havingValue = "true")
public class SupplierSyncScheduler {
	private static final Logger log = LoggerFactory.getLogger(SupplierSyncScheduler.class);
	private final SupplierIntegrationMapper mapper;
	private final WelstoryCatalogSyncService sync;
	private CatalogPreparationService preparation;
	@org.springframework.beans.factory.annotation.Autowired
	public void setPreparation(CatalogPreparationService preparation) { this.preparation=preparation; }

	public SupplierSyncScheduler(SupplierIntegrationMapper mapper, WelstoryCatalogSyncService sync) {
		this.mapper = mapper;
		this.sync = sync;
	}

	@Scheduled(cron = "${supplier.sync.catalog.cron:0 10 3 * * *}", zone = "Asia/Seoul")
	public void catalogs() {
		if (maintenance())
			return;
		preparation.scheduled();
	}

	@Scheduled(cron = "${supplier.sync.receipts.cron:0 0 8-20 * * *}", zone = "Asia/Seoul")
	public void receipts() {
		if (maintenance())
			return;
		for (var target : mapper.scheduledReceiptTargets())
			try {
				sync.syncReceipts(target);
			} catch (RuntimeException e) {
				log.warn("입고 대사 실패. 수동입고 중복·정정 여부를 확인하세요.");
			}
	}

	private boolean maintenance() {
		return LocalTime.now(ZoneId.of("Asia/Seoul")).isBefore(LocalTime.of(0, 30));
	}
}
