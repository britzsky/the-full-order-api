package com.example.demo.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.service.TableMealsService;
import com.google.gson.Gson;

@RestController
public class TableMealsController {

	private final TableMealsService tableMealsService;
	private final String uploadDir;

	@Autowired
	public TableMealsController(
			TableMealsService tableMealsService,
			@Value("${file.upload-dir}") String uploadDir) {
		this.tableMealsService = tableMealsService;
		this.uploadDir = uploadDir;
	}

	/*
	 * method : TableMealsList
	 * comment : 식단표 조회
	 */
	@GetMapping("/Table/TableMealsList")
	public String TableMealsList(@RequestParam Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = tableMealsService.TableMealsList(paramMap);

		return new Gson().toJson(resultList);
	}
	/*
	 * method : TableMealsDetailList
	 * comment : 식단표 상세 조회
	 */
	@GetMapping("/Table/TableMealsDetailList")
	public String TableMealsDetailList(@RequestParam Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = tableMealsService.TableMealsDetailList(paramMap);

		return new Gson().toJson(resultList);
	}
	/*
	 * method : DetailList
	 * comment : 식단표 저장
	 */
	@PostMapping(value = "/Table/TableMealsSave", consumes = MediaType.APPLICATION_JSON_VALUE)
	public String TableMealsSave(@RequestBody Map<String, Object> payload) {
		Map<String, Object> resultMap = tableMealsService.TableMealsSave(payload);

		return new Gson().toJson(resultMap);
	}
	
	@PostMapping(value = "/Table/TableMealsSave", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
	public String TableMealsSaveForm(@RequestParam Map<String, Object> paramMap) {
		Map<String, Object> resultMap = tableMealsService.TableMealsSave(paramMap);

		return new Gson().toJson(resultMap);
	}
}
