package com.example.demo.service;

import com.fasterxml.jackson.databind.JsonNode;

public record WelstoryAlarmEvent(
		String messageKey,
		String type,
		String text,
		String emergencySequence,
		JsonNode rawMessage,
		JsonNode emergencyPriceResponse) {
}
