package com.example.demo.service;

import java.util.LinkedHashSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 공급사 가이드의 원재료 원산지를 우선하고 제조국을 원재료 원산지로 추측하지 않는다. */
public final class ProductOrigins {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ProductOrigins() {}
    public static String describe(Object attributes) {
        if (attributes == null) return "원산지 미확인";
        try {
            JsonNode row = JSON.readTree(attributes.toString());
            LinkedHashSet<String> origins = new LinkedHashSet<>();
            for (String key : new String[]{"rawMaterialOrigin1", "rawMaterialOrigin2", "originPlaceNm"}) {
                String value=row.path(key).asText("").trim();
                if (!value.isEmpty()) origins.add(value);
            }
            if (!origins.isEmpty()) return String.join(" / ", origins);
            String origin=row.path("origin").asText("").trim();
            return origin.isEmpty() ? "원산지 미확인" : origin;
        } catch (Exception e) { return "원산지 미확인"; }
    }
}
