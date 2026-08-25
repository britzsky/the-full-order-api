package com.example.demo.controller;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.service.MealPlanV2Service;

@RestController
@RequestMapping("/v2/meal-plans")
public class MealPlanV2Controller {
	private final MealPlanV2Service s;

	public MealPlanV2Controller(MealPlanV2Service s) {
		this.s = s;
	}

	@GetMapping
	public ResponseEntity<?> plans(@RequestParam Map<String, Object> p) {
		return run(() -> s.plans(p));
	}

	@PostMapping
	public ResponseEntity<?> create(@RequestBody Map<String, Object> b) {
		return run(() -> s.createPlan(b));
	}

	@PatchMapping
	public ResponseEntity<?> update(@RequestBody Map<String, Object> b) {
		return run(() -> s.updatePlan(b));
	}

	@DeleteMapping
	public ResponseEntity<?> delete(@RequestBody Map<String, Object> b) {
		return run(() -> s.deletePlan(b));
	}

	@GetMapping("/services")
	public ResponseEntity<?> services(@RequestParam Map<String, Object> p) {
		return run(() -> s.services(p));
	}

	@PostMapping("/services")
	public ResponseEntity<?> createService(@RequestBody Map<String, Object> b) {
		return run(() -> s.createService(b));
	}

	@PatchMapping("/services")
	public ResponseEntity<?> updateService(@RequestBody Map<String, Object> b) {
		return run(() -> s.updateService(b));
	}

	@DeleteMapping("/services")
	public ResponseEntity<?> deleteService(@RequestBody Map<String, Object> b) {
		return run(() -> s.deleteService(b));
	}

	@GetMapping("/details")
	public ResponseEntity<?> details(@RequestParam Map<String, Object> p) {
		return run(() -> s.details(p));
	}

	@PostMapping("/details")
	public ResponseEntity<?> createDetail(@RequestBody Map<String, Object> b) {
		return run(() -> s.createDetail(b));
	}

	@PatchMapping("/details")
	public ResponseEntity<?> updateDetail(@RequestBody Map<String, Object> b) {
		return run(() -> s.updateDetail(b));
	}

	@DeleteMapping("/details")
	public ResponseEntity<?> deleteDetail(@RequestBody Map<String, Object> b) {
		return run(() -> s.deleteDetail(b));
	}

	@GetMapping("/costs")
	public ResponseEntity<?> costs(@RequestParam Map<String, Object> p) {
		return run(() -> s.costs(p));
	}

	@GetMapping("/requirements")
	public ResponseEntity<?> requirements(@RequestParam Map<String, Object> p) {
		return run(() -> s.requirements(p));
	}

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
