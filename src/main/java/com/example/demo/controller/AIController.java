package com.example.demo.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
public class AIController {

	private static final String OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses";

	private final String openAiApiKey;
	private final String openAiModel;
	private final WebClient webClient;
	private final ObjectMapper objectMapper;

	public AIController(
			@Value("${openai.api.key}") String openAiApiKey,
			@Value("${openai.model:gpt-5.4-mini}") String openAiModel,
			WebClient.Builder webClientBuilder,
			ObjectMapper objectMapper) {
		this.openAiApiKey = openAiApiKey;
		this.openAiModel = openAiModel;
		this.webClient = webClientBuilder.build();
		this.objectMapper = objectMapper;
	}

	@PostMapping("/AI/RecipeGenerate")
	public ResponseEntity<Map<String, Object>> RecipeGenerate(@RequestBody Map<String, Object> payload) {
		if (openAiApiKey == null || openAiApiKey.isBlank()) {
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
			Map<String, Object> openAiRequest = createRecipeRequest(menuName, ingredients);
			JsonNode openAiResponse = webClient.post()
					.uri(OPENAI_RESPONSES_URL)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + openAiApiKey)
					.contentType(MediaType.APPLICATION_JSON)
					.bodyValue(openAiRequest)
					.retrieve()
					.bodyToMono(JsonNode.class)
					.block();

			String outputText = extractOutputText(openAiResponse);
			Map<String, Object> recipe = objectMapper.readValue(outputText, new TypeReference<Map<String, Object>>() {
			});

			return ResponseEntity.ok(Map.of("recipe", recipe));
		} catch (WebClientResponseException e) {
			return ResponseEntity.status(e.getStatusCode())
					.body(Map.of(
							"code", e.getStatusCode().value(),
							"message", "OpenAI API request failed.",
							"detail", e.getResponseBodyAsString()));
		} catch (JsonProcessingException e) {
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
					.body(Map.of("code", 502, "message", "OpenAI response was not valid JSON."));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("code", 500, "message", e.getMessage()));
		}
	}

	private Map<String, Object> createRecipeRequest(Object menuName, Object ingredients) {
		Map<String, Object> userContent = Map.of(
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

		return Map.of(
				"model", openAiModel,
				"input", List.of(
						Map.of(
								"role", "system",
								"content", "\uB108\uB294 \uB2E8\uCCB4\uAE09\uC2DD \uBA54\uB274 \uB808\uC2DC\uD53C\uB97C \uC791\uC131\uD558\uB294 \uC870\uB9AC \uC804\uBB38\uAC00\uB2E4. \uBC18\uB4DC\uC2DC JSON\uC73C\uB85C\uB9CC \uB2F5\uD55C\uB2E4."),
						Map.of(
								"role", "user",
								"content", toJson(userContent))));
	}

	private String toJson(Map<String, Object> value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("Request body could not be converted to JSON.", e);
		}
	}

	private String extractOutputText(JsonNode response) {
		if (response == null || response.path("output").isMissingNode()) {
			throw new IllegalStateException("OpenAI response output is empty.");
		}

		for (JsonNode output : response.path("output")) {
			for (JsonNode content : output.path("content")) {
				if ("output_text".equals(content.path("type").asText())) {
					String text = content.path("text").asText();
					if (!text.isBlank()) {
						return text;
					}
				}
			}
		}

		throw new IllegalStateException("OpenAI response output_text is empty.");
	}
}
