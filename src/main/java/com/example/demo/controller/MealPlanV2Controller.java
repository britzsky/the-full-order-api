package com.example.demo.controller;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.service.MealPlanV2Service;
import com.example.demo.service.MealUsageService;

@RestController
@RequestMapping("/v2/meal-plans")
public class MealPlanV2Controller {
	private final MealPlanV2Service s;
	private final MealUsageService usage;

	public MealPlanV2Controller(MealPlanV2Service s, MealUsageService usage) {
		this.s = s;
		this.usage = usage;
	}

	/*
	 * part : 식단관리 method : complete comment : 실제 식수 확정 → 식자재 재고 차감 (재고 부족 등 실패 시 전체 롤백)
	 */
	@PostMapping("/complete")
	public ResponseEntity<?> complete(@RequestBody Map<String,Object> b) { return run(() -> usage.complete(b)); }

	/*
	 * part : 식단관리 method : usageWeek comment : 식단표(주) 식사별 사용 확정 가능 여부·재확정 필요 여부
	 */
	@GetMapping("/usage-week")
	public ResponseEntity<?> usageWeek(@RequestParam Map<String,Object> p) { return run(() -> usage.week(p)); }

	/*
	 * part : 식단관리 method : usagePreview comment : 주간 사용 확정 전 차감 미리보기 (실제 차감 후 롤백, 부족·미입고 발주 안내)
	 */
	@PostMapping("/usage-preview")
	public ResponseEntity<?> usagePreview(@RequestBody Map<String,Object> b) { return run(() -> usage.preview(b)); }

	/*
	 * part : 식단관리 method : completeWeek comment : 주간 사용 확정 → 재고 일괄 차감 (한 식사라도 실패 시 전체 롤백)
	 */
	@PostMapping("/complete-week")
	public ResponseEntity<?> completeWeek(@RequestBody Map<String,Object> b) { return run(() -> usage.completeWeek(b)); }

	/*
	 * part : 식단관리 method : cancel comment : 식단 사용 확정 취소 → 차감했던 재고를 원래 상품·창고·LOT로 복원
	 */
	@PostMapping("/cancel")
	public ResponseEntity<?> cancel(@RequestBody Map<String,Object> b) { return run(() -> usage.cancel(b)); }

	/*
	 * part : 식단관리 method : origins comment : 식단 저장 당시 스냅샷 기준 원산지 조회 (실제 사용 LOT 원산지 구분)
	 */
	@GetMapping("/origins")
	public ResponseEntity<?> origins(@RequestParam Map<String,Object> p) { return run(() -> usage.origins(p)); }

	/*
	 * part : 식단관리 method : plans comment : 식단표 목록 조회 (연/월/주차/식단유형 필터)
	 */
	@GetMapping
	public ResponseEntity<?> plans(@RequestParam Map<String, Object> p) {
		return run(() -> s.plans(p));
	}

	/*
	 * part : 식단관리 method : create comment : 식단표(헤더) 생성
	 */
	@PostMapping
	public ResponseEntity<?> create(@RequestBody Map<String, Object> b) {
		return run(() -> s.createPlan(b));
	}

	/*
	 * part : 식단관리 method : save comment : 식단표 헤더·식사·메뉴·원가를 한 트랜잭션으로 일괄 저장
	 */
	@PostMapping("/save")
	public ResponseEntity<?> save(@RequestBody Map<String, Object> b) {
		return run(() -> s.saveCompletePlan(b));
	}

	/*
	 * part : 식단관리 method : update comment : 식단표(헤더) 수정
	 */
	@PatchMapping
	public ResponseEntity<?> update(@RequestBody Map<String, Object> b) {
		return run(() -> s.updatePlan(b));
	}

	/*
	 * part : 식단관리 method : delete comment : 식단표 삭제
	 */
	@DeleteMapping
	public ResponseEntity<?> delete(@RequestBody Map<String, Object> b) {
		return run(() -> s.deletePlan(b));
	}

	/*
	 * part : 식단관리 method : services comment : 식사(날짜·식사구분) 목록 조회 (1인당 메뉴 원가 합계 포함)
	 */
	@GetMapping("/services")
	public ResponseEntity<?> services(@RequestParam Map<String, Object> p) {
		return run(() -> s.services(p));
	}

	/*
	 * part : 식단관리 method : createService comment : 식사 등록
	 */
	@PostMapping("/services")
	public ResponseEntity<?> createService(@RequestBody Map<String, Object> b) {
		return run(() -> s.createService(b));
	}

	/*
	 * part : 식단관리 method : updateService comment : 식사 수정
	 */
	@PatchMapping("/services")
	public ResponseEntity<?> updateService(@RequestBody Map<String, Object> b) {
		return run(() -> s.updateService(b));
	}

	/*
	 * part : 식단관리 method : deleteService comment : 식사 삭제
	 */
	@DeleteMapping("/services")
	public ResponseEntity<?> deleteService(@RequestBody Map<String, Object> b) {
		return run(() -> s.deleteService(b));
	}

	/*
	 * part : 식단관리 method : details comment : 식사별 메뉴 상세 조회
	 */
	@GetMapping("/details")
	public ResponseEntity<?> details(@RequestParam Map<String, Object> p) {
		return run(() -> s.details(p));
	}

	/*
	 * part : 식단관리 method : createDetail comment : 식사에 메뉴 추가
	 */
	@PostMapping("/details")
	public ResponseEntity<?> createDetail(@RequestBody Map<String, Object> b) {
		return run(() -> s.createDetail(b));
	}

	/*
	 * part : 식단관리 method : updateDetail comment : 식사 메뉴 수정
	 */
	@PatchMapping("/details")
	public ResponseEntity<?> updateDetail(@RequestBody Map<String, Object> b) {
		return run(() -> s.updateDetail(b));
	}

	/*
	 * part : 식단관리 method : deleteDetail comment : 식사 메뉴 삭제
	 */
	@DeleteMapping("/details")
	public ResponseEntity<?> deleteDetail(@RequestBody Map<String, Object> b) {
		return run(() -> s.deleteDetail(b));
	}

	/*
	 * part : 식단관리 method : costs comment : 식사별 메뉴 원가 스냅샷 조회
	 */
	@GetMapping("/costs")
	public ResponseEntity<?> costs(@RequestParam Map<String, Object> p) {
		return run(() -> s.costs(p));
	}

	/*
	 * part : 식단관리 method : requirements comment : 식사별 식자재 필요량 조회
	 */
	@GetMapping("/requirements")
	public ResponseEntity<?> requirements(@RequestParam Map<String, Object> p) {
		return run(() -> s.requirements(p));
	}

	/*
	 * part : 식단관리 method : recalculate comment : 식사의 식자재 필요량·메뉴 원가 재계산. 기존 사용 확정분은 재고 복원 후 DRAFT로 전환
	 */
	@PostMapping("/recalculate")
	public ResponseEntity<?> recalculate(@RequestBody Map<String, Object> b) {
		return run(() -> s.recalculate(b));
	}

	private ResponseEntity<?> run(Supplier<?> a) {
		try {
			return ResponseEntity.ok(a.get());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
		}
	}
}
