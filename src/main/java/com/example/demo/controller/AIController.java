package com.example.demo.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.security.WorkspaceAccess;
import com.example.demo.service.OpenAiClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

@RestController
public class AIController {

	private final OpenAiClient openAi;
	private final ObjectMapper objectMapper;

	public AIController(OpenAiClient openAi, ObjectMapper objectMapper) {
		this.openAi = openAi;
		this.objectMapper = objectMapper;
	}

	/*
	 * part : AI 레시피 method : RecipeGenerate comment : 메뉴명과 식재료를 OpenAI에 전달해 단체급식용 레시피(JSON)를 생성. 토큰 사용량은 tb_ai_usage_log(RECIPE_GENERATE)
	 */
	@PostMapping("/AI/RecipeGenerate")
	public ResponseEntity<Map<String, Object>> RecipeGenerate(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
		if (!openAi.configured()) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("code", 500, "message", "OpenAI API key is not configured."));
		}

		Object menuName = payload.get("menu_name");
		Object ingredients = payload.get("ingredients");

		if (menuName == null || menuName.toString().isBlank()) {
			return ResponseEntity.badRequest()
					.body(Map.of("code", 400, "message", "menu_name is required."));
		}

		try {
			Object accountId = payload.get("account_id");
			OpenAiClient.Caller caller = new OpenAiClient.Caller("RECIPE_GENERATE", accountId == null ? null : accountId.toString(),
					WorkspaceAccess.user(request).userId(), 1);
			String outputText = openAi.respond(caller,
					"너는 단체급식 메뉴 레시피를 작성하는 조리 전문가다. 반드시 JSON으로만 답한다.",
					toJson(createRecipeContent(menuName, ingredients)), null, null);
			Map<String, Object> recipe = objectMapper.readValue(outputText, new TypeReference<Map<String, Object>>() {
			});

			return ResponseEntity.ok(Map.of("recipe", recipe));
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
					.body(Map.of("code", 502, "message", e.getMessage()));
		} catch (JsonProcessingException e) {
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
					.body(Map.of("code", 502, "message", "OpenAI response was not valid JSON."));
		}
	}

	private Map<String, Object> createRecipeContent(Object menuName, Object ingredients) {
		return Map.of(
				"menu_name", menuName,
				"ingredients", ingredients == null ? List.of() : ingredients,
				"output_format", Map.of(
						"title", "string",
						"summary", "string",
						"servings_note", "string",
						"ingredients", List.of("string"),
						"steps", List.of("string"),
						"tips", List.of("string"),
						"storage", List.of("string"),
						"allergens", List.of("string")));
	}

	private String toJson(Map<String, Object> value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("Request body could not be converted to JSON.", e);
		}
	}
}
