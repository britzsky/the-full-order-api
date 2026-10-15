package com.example.demo.service;

import java.time.LocalTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.example.demo.mapper.SupplierIntegrationMapper;

/**
 * 공급사 자동 배치. 기능마다 스위치를 따로 둔다 — 재고를 바꾸는 웰스토리 입고 대사는 검증 후 따로 켠다.
 * 예전 스위치 supplier.sync.scheduled.enabled=true는 이전처럼 상품 준비·웰스토리 입고 대사를 함께 켠다.
 * 서버가 여러 대면 한 대에서만 켤 것.
 */
@Component
@EnableScheduling
public class SupplierSyncScheduler {
	private static final Logger log = LoggerFactory.getLogger(SupplierSyncScheduler.class);
	private final SupplierIntegrationMapper mapper;
	private final WelstoryCatalogSyncService sync;
	private final CatalogPreparationService preparation;
	private final OurhomeCatalogSyncService ourhome;
	private final SupplierOrderMonitorService monitor;
	private KakaoNotificationService kakao;

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	public void setKakao(KakaoNotificationService kakao) { this.kakao = kakao; }
	private final boolean catalogEnabled, receiptsEnabled, ourhomeSitesEnabled, ourhomeShipmentsEnabled, reconcileEnabled;

	public SupplierSyncScheduler(SupplierIntegrationMapper mapper, WelstoryCatalogSyncService sync,
			CatalogPreparationService preparation, OurhomeCatalogSyncService ourhome, SupplierOrderMonitorService monitor,
			@Value("${supplier.sync.scheduled.enabled:false}") boolean legacy,
			@Value("${supplier.sync.catalog.enabled:false}") boolean catalog,
			@Value("${supplier.sync.receipts.enabled:false}") boolean receipts,
			@Value("${ourhome.site-sync.enabled:false}") boolean ourhomeSites,
			@Value("${ourhome.shipment-check.enabled:false}") boolean ourhomeShipments,
			@Value("${supplier.order-reconcile.enabled:false}") boolean reconcile) {
		this.mapper = mapper;
		this.sync = sync;
		this.preparation = preparation;
		this.ourhome = ourhome;
		this.monitor = monitor;
		this.catalogEnabled = legacy || catalog;
		this.receiptsEnabled = legacy || receipts;
		this.ourhomeSitesEnabled = ourhomeSites;
		this.ourhomeShipmentsEnabled = ourhomeShipments;
		this.reconcileEnabled = reconcile;
	}

	/** 납품일 상품 준비(아워홈 납품일별 판가 + 웰스토리 순기 확인). 00:00~00:30 공급사 API 점검 시간은 피한다. */
	@Scheduled(cron = "${supplier.sync.catalog.cron:0 10 3 * * *}", zone = "Asia/Seoul")
	public void catalogs() {
		if (!catalogEnabled || maintenance())
			return;
		log.info("납품일 상품 준비 배치 결과: {}", preparation.scheduled());
		log.info("지난 상품 준비 체크포인트 정리: {}건", preparation.cleanup());
	}

	/** 웰스토리 입고 대사 — 재고에 입고를 반영한다. */
	@Scheduled(cron = "${supplier.sync.receipts.cron:0 0 8-20 * * *}", zone = "Asia/Seoul")
	public void receipts() {
		if (!receiptsEnabled || maintenance())
			return;
		for (var target : mapper.scheduledReceiptTargets())
			try {
				sync.syncReceipts(target);
			} catch (RuntimeException e) {
				log.warn("입고 대사 실패. 수동입고 중복·정정 여부를 확인하세요.");
			}
	}

	/** 아워홈 사업장 마스터(사업장 목록만 — 거래처 연결은 건드리지 않음). */
	@Scheduled(cron = "${ourhome.site-sync.cron:0 0 3 * * MON}", zone = "Asia/Seoul")
	public void ourhomeSites() {
		if (!ourhomeSitesEnabled || maintenance())
			return;
		try {
			log.info("아워홈 사업장 마스터 배치 결과: {}", ourhome.syncSites("SYSTEM"));
		} catch (RuntimeException e) {
			log.warn("아워홈 사업장 마스터 배치 실패 — 기존 목록을 유지합니다: {}", e.getMessage());
		}
	}

	/** 아워홈 출고·삭제 상태 확인 → 업무 알림. 재고는 바꾸지 않는다. */
	@Scheduled(cron = "${ourhome.shipment-check.cron:0 40 7-20 * * *}", zone = "Asia/Seoul")
	public void ourhomeShipments() {
		if (!ourhomeShipmentsEnabled || maintenance())
			return;
		log.info("아워홈 출고 상태 확인 결과: {}", monitor.checkOurhomeShipments());
	}

	/** 접수 불명확 주문 자동 대사(화면의 '주문 대사'와 같은 처리). */
	@Scheduled(cron = "${supplier.order-reconcile.cron:0 5/20 6-22 * * *}", zone = "Asia/Seoul")
	public void reconcileUnresolved() {
		if (!reconcileEnabled || maintenance())
			return;
		var result = monitor.reconcileUnresolved();
		if (!Integer.valueOf(0).equals(result.get("checked")))
			log.info("접수 불명확 주문 자동 대사 결과: {}", result);
	}

	/** 발주 마감 3시간 이내 알림톡(kakao.alimtalk.enabled와 마감 템플릿 ID가 있을 때만). */
	@Scheduled(cron = "${kakao.alimtalk.deadline.cron:0 15 7-20 * * *}", zone = "Asia/Seoul")
	public void kakaoDeadlines() {
		if (kakao == null || !kakao.active() || maintenance())
			return;
		log.info("마감 임박 알림톡 결과: {}", kakao.deadlines());
	}

	private boolean maintenance() {
		return LocalTime.now(ZoneId.of("Asia/Seoul")).isBefore(LocalTime.of(0, 30));
	}
}
