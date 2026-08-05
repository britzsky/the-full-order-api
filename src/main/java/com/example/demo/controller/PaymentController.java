package com.example.demo.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.service.PaymentService;
import com.google.gson.Gson;

@RestController
public class PaymentController {

	private final PaymentService paymentService;
	private final String uploadDir;

	@Autowired
	public PaymentController(
			PaymentService paymentService,
			@Value("${file.upload-dir}") String uploadDir) {
		this.paymentService = paymentService;
		this.uploadDir = uploadDir;
	}
	
	/*
	 * method : SubscriptionStatus
	 * comment : 구독상태 조회
	 */
	@GetMapping("/Payment/SubscriptionStatus")
	public String DetailList(@RequestParam Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = paymentService.SubscriptionStatus(paramMap);

		return new Gson().toJson(resultList);
	}
}
