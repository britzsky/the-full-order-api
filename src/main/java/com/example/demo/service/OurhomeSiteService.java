package com.example.demo.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;

@Service
public class OurhomeSiteService {
    private static final Logger log = LoggerFactory.getLogger(OurhomeSiteService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private final WebClient client;
    private final ObjectMapper json;
    private final String baseUrl, serviceId, token, seed, taxNo, headCode;
    private final AtomicLong guidCounter = new AtomicLong();
    private final Clock clock = Clock.systemUTC();

    public OurhomeSiteService(WebClient.Builder builder, ObjectMapper json,
            @Value("${ourhome.api.base-url:${OURHOME_BASE_URL:https://devtosapi.ourhome.co.kr}}") String baseUrl,
            @Value("${ourhome.api.service-id:${OURHOME_EXTN_SVC_ID:}}") String serviceId,
            @Value("${ourhome.api.client-token:${OURHOME_CLIENT_TOKEN:}}") String token,
            @Value("${ourhome.api.encrypt-seed:${OURHOME_ENCRYPT_SEED:}}") String seed,
            @Value("${ourhome.api.tax-no:${OURHOME_TAX_NO:}}") String taxNo,
            @Value("${ourhome.api.head-group-code:${OURHOME_HEAD_GROUP_CODE:}}") String headCode) {
        this.client = builder.clone().codecs(config -> config.defaultCodecs().maxInMemorySize(4 * 1024 * 1024)).build();
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.serviceId = serviceId;
        this.token = token;
        this.seed = seed;
        this.taxNo = taxNo;
        this.headCode = headCode;
    }

    public record Site(String siteCode, String siteName, String headCode, String headName,
            String centerCode, String centerName, String representativeCode, String representativeName,
            List<String> deliveryDates) {}
    public record SiteResult(String checkedAt, List<Site> sites) {}

    public SiteResult sites() {
        JsonNode response = call("/api/chaeum/v3/getBusiInfo",
                Map.of("taxNo", taxNo, "headGrpCd", headCode, "busiplcd", ""));
        return mapSites(response);
    }

    private JsonNode call(String path, Map<String, Object> params) {
        // 조회는 업무 오류(rtnCd<0)를 HTTP 4xx 본문으로 돌려주므로 본문을 읽어 사유를 보여준다.
        JsonNode response = exchange(path, params, true);
        if (!response.path("rtnCd").isIntegralNumber() || response.path("rtnCd").asInt() != 0
                || !response.path("result").path("data").isArray()) {
            String reason = providerMessage(response);
            if (!reason.isEmpty()) log.warn("Ourhome {} rtnCd={} rtnMsg={}", path, response.path("rtnCd").asText(), reason);
            throw new IllegalStateException(reason.isEmpty() ? "아워홈에서 조회 결과를 정상 반환하지 않았습니다."
                    : "아워홈 조회 결과: " + reason);
        }
        return response.path("result");
    }

    /** 공급사 업무 메시지. 인증값이 섞여 있거나 비정상적으로 길면 노출하지 않는다. */
    private String providerMessage(JsonNode response) {
        String message = response.path("rtnMsg").asText("").strip();
        if (message.length() > 200) return "";
        for (String secret : List.of(token, seed, serviceId, taxNo))
            if (!secret.isBlank() && message.contains(secret)) return "";
        return message;
    }

    private JsonNode exchange(String path, Map<String, Object> params, boolean readErrorBody) {
        if (token.isBlank() || !serviceId.matches("\\d+") || !seed.matches("-?\\d+")
                || !taxNo.matches("\\d{10}") || !baseUrl.startsWith("https://")) {
            throw new IllegalStateException("아워홈 사업장 조회 설정이 없습니다. 서버 연동 설정을 확인해 주세요.");
        }
        var now = clock.instant().atZone(ZONE);
        String stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        long serial = guidCounter.updateAndGet(previous -> Math.max(previous + 1, clock.millis() * 100));
        String guid = Instant.ofEpochMilli(serial / 100).atZone(ZONE)
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")) + String.format("%02d", serial % 100);
        Map<String, Object> requestParams = new LinkedHashMap<>(params);
        requestParams.put("guid", guid);
        Map<String, Object> request = Map.of("tokenInfo", Map.of("extnSvcId", serviceId,
                "senddttm", stamp, "clientToken", token, "validCd", validCode(stamp, serviceId, seed)),
                "params", requestParams);
        JsonNode response;
        try {
            byte[] bytes = client.post().uri(baseUrl + path)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(request).retrieve()
                    .onStatus(status -> readErrorBody && status.is4xxClientError(), ignored -> Mono.empty())
                    .bodyToMono(byte[].class).block(Duration.ofSeconds(35));
            response = bytes == null ? null : json.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            // Provider errors can echo tokenInfo. Do not expose the raw body or exception message.
            log.warn("Ourhome {} call failed: {}", path, exception.getClass().getSimpleName());
            throw new IllegalStateException("아워홈 조회에 실패했습니다. 연결 상태와 서버 인증 설정을 확인해 주세요.");
        }
        if (response == null || !response.isObject()) {
            throw new IllegalStateException("아워홈에서 조회 결과를 정상 반환하지 않았습니다. 조회 조건과 접근 권한을 확인해 주세요.");
        }
        return response;
    }

    /** 인증값은 주문 스냅샷과 분리한다. 전송 재시도는 호출자가 하지 않는다. */
    public JsonNode submitOrder(JsonNode params) {
        return exchange("/api/chaeum/v1/setOrderList", json.convertValue(params, Map.class), false);
    }

    public Site requireSite(String code) { return site(code); }

    private SiteResult mapSites(JsonNode result) {
        List<Site> sites = new ArrayList<>();
        for (JsonNode row : result.path("data")) {
            String code = row.path("busiplCd").asText("");
            if (code.isBlank()) throw new IllegalStateException("아워홈 사업장 응답에 사업장 코드가 없습니다.");
            List<String> dates = new ArrayList<>();
            for (String date : row.path("depositDt").asText("").split(",")) {
                if (date.isBlank()) continue;
                try {
                    dates.add(java.time.LocalDate.parse(date.trim(), DateTimeFormatter.BASIC_ISO_DATE).toString());
                } catch (java.time.DateTimeException exception) {
                    throw new IllegalStateException("아워홈 발주 가능일 형식을 확인해 주세요.");
                }
            }
            sites.add(new Site(code, row.path("busiplNm").asText(""), row.path("headGrpCd").asText(""),
                    row.path("headGrpNm").asText(""), row.path("busiplcdCtr").asText(""),
                    row.path("busiplcdCtrNm").asText(""), row.path("repBusiplCd").asText(""),
                    row.path("repBusiplNm").asText(""), List.copyOf(dates)));
        }
        return new SiteResult(checkedAt(), List.copyOf(sites));
    }

    public record ProductResult(String checkedAt, String siteCode, String deliveryDate, int totalRow,
            boolean hasNext, String nextKey, List<Map<String, String>> products) {}
    public record OrderResult(String checkedAt, String siteCode, String deliveryDate,
            List<Map<String, String>> orders) {}

    public ProductResult products(String siteCode, String deliveryDate, String keyword, String searchType,
            String nextKey) {
        productDate(deliveryDate, keyword, searchType, nextKey);
        return products(site(siteCode), deliveryDate, keyword, searchType, nextKey, 100);
    }

    ProductResult catalogPage(Site site, String deliveryDate, String nextKey) {
        return products(site, deliveryDate, "", "A", nextKey, 1000);
    }

    private ProductResult products(Site site, String deliveryDate, String keyword, String searchType,
            String nextKey, int pageSize) {
        LocalDate date = productDate(deliveryDate, keyword, searchType, nextKey);
        if (!site.deliveryDates().contains(date.toString()))
            throw new IllegalArgumentException("선택한 사업장의 발주 가능일을 선택해 주세요.");
        Map<String, Object> params = baseParams(site, date);
        params.put("keyWord", keyword.trim());
        params.put("searchType", searchType);
        params.put("queryType", "A");
        params.put("onePageRow", pageSize);
        params.put("nextYn", nextKey.isBlank() ? "N" : "Y");
        params.put("nextKey", nextKey);
        JsonNode result = call("/api/chaeum/v2/getGoodList", params);
        String more = result.path("nextYn").asText("");
        String cursor = result.path("nextKey").asText("");
        if (!List.of("N", "Y").contains(more) || ("Y".equals(more)
                && (cursor.isBlank() || cursor.equals(nextKey) || result.path("data").isEmpty())))
            throw new IllegalStateException("아워홈 상품의 다음 페이지 정보를 확인해 주세요.");
        var products = fields(result, List.of("goodcd", "goodnm", "goodsz", "boxInQty", "originPlaceNm",
                "taxYn", "salsUcost", "salsUcostKg", "orderdtInfo", "ordInfo", "keepTemp", "deliveyType",
                "odrUnit", "odrConvqty", "goodStatus", "goodStatusNm", "vanNm", "unquantityYn",
                "odrupYn", "odrupYnDesc", "decOdrupYn", "ordTime", "img1", "img2", "imageRoute"));
        if (products.stream().anyMatch(row -> row.get("goodcd").isBlank()))
            throw new IllegalStateException("아워홈 상품 응답에 상품 코드가 없습니다.");
        return new ProductResult(checkedAt(), site.siteCode(), date.toString(), result.path("totalRow").asInt(0),
                "Y".equals(more), "Y".equals(more) ? cursor : "", products);
    }

    public OrderResult orders(String siteCode, String deliveryDate) {
        // Historical receipt dates need not occur in the current future delivery calendar.
        LocalDate date = date(deliveryDate);
        Site site = site(siteCode);
        Map<String, Object> params = baseParams(site, date);
        params.put("goodcd", "");
        JsonNode result = call("/api/chaeum/v1/getOrderList", params);
        return new OrderResult(checkedAt(), site.siteCode(), date.toString(), fields(result,
                List.of("ordKeyVal", "ordSeq", "foSeq", "extnOrderKey", "extnOrderNo", "goodcd", "goodnm",
                        "goodsz", "odrUnit", "clhaqty", "salsUcost", "salsAmt", "originPlaceNm",
                        "ordStatYn", "prcsStatYn", "delYn", "idenNo", "expireInfo", "depositDt")));
    }

    private Site site(String code) {
        if (code == null || code.isBlank() || code.length() > 100)
            throw new IllegalArgumentException("사업장을 선택해 주세요.");
        return sites().sites().stream().filter(site -> site.siteCode().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("조회 가능한 아워홈 사업장을 선택해 주세요."));
    }

    private LocalDate date(String value) {
        try { return LocalDate.parse(value); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("날짜는 YYYY-MM-DD 형식으로 입력해 주세요."); }
    }

    private LocalDate productDate(String value, String keyword, String searchType, String nextKey) {
        LocalDate date = date(value);
        if (keyword.length() > 100 || nextKey.length() > 2000 || !List.of("A", "G", "N").contains(searchType))
            throw new IllegalArgumentException("상품 검색 조건을 확인해 주세요.");
        return date;
    }

    private Map<String, Object> baseParams(Site site, LocalDate date) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("busiplcd", site.siteCode());
        params.put("headGrpCd", site.headCode());
        params.put("depositDt", date.format(DateTimeFormatter.BASIC_ISO_DATE));
        return params;
    }

    private List<Map<String, String>> fields(JsonNode result, List<String> names) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode row : result.path("data")) {
            Map<String, String> mapped = new LinkedHashMap<>();
            for (String name : names) mapped.put(name, row.path(name).asText(""));
            rows.add(mapped);
        }
        return rows;
    }

    private String checkedAt() { return clock.instant().atZone(ZONE).toOffsetDateTime().toString(); }

    static String validCode(String stamp, String serviceId, String seed) {
        long sum = (100 - Integer.parseInt(stamp.substring(0, 2)))
                + (100 - Integer.parseInt(stamp.substring(2, 4)))
                + (100 - Integer.parseInt(stamp.substring(4, 6)))
                + (100 - Integer.parseInt(stamp.substring(6, 8)));
        long factor = Long.parseLong("" + stamp.charAt(13) + stamp.charAt(11) + stamp.charAt(9));
        return Long.toString(Math.addExact(Math.addExact(sum * factor, Long.parseLong(serviceId)), Long.parseLong(seed)));
    }
}
