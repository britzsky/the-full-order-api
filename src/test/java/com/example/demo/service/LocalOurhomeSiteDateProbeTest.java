package com.example.demo.service;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 테스트할 아워홈 사업장 찾기(조회 전용, DB 쓰기·주문 없음): 사업장 목록 API(getBusiInfo)로 받은 사업장마다
 * 발주 가능일별로 상품 목록 API(getGoodList) 첫 페이지를 물어 상품이 오는 사업장·날짜만 찍는다.
 * Gradle은 환경변수만 바꾸면 테스트를 건너뛰니 cleanTest를 같이 줄 것.
 */
@EnabledIfEnvironmentVariable(named = "RUN_OURHOME_SITE_PROBE", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LocalOurhomeSiteDateProbeTest {
	@Autowired OurhomeSiteService api;

	@Test void probe() {
		var sites = api.sites().sites();
		System.out.println("OH_SITES " + sites.size());
		int found = 0;
		for (var site : sites) {
			List<String> hits = new ArrayList<>(), misses = new ArrayList<>();
			for (String date : site.deliveryDates()) {
				try {
					var page = api.catalogPage(site, date, "");
					if (page.totalRow() > 0) hits.add(date + "(" + page.totalRow() + "건)");
					else misses.add(date + "=0건");
				} catch (RuntimeException e) {
					misses.add(date + "=" + e.getMessage());
				}
			}
			if (!hits.isEmpty()) found++;
			System.out.println((hits.isEmpty() ? "OH_MISS " : "OH_HIT ") + site.siteCode() + " | " + site.siteName()
					+ " | 발주가능일 " + site.deliveryDates().size() + " | " + String.join(", ", hits)
					+ (misses.isEmpty() ? "" : " | 없음: " + String.join(", ", misses)));
		}
		System.out.println("OH_DONE sites=" + sites.size() + " hit=" + found);
	}
}
