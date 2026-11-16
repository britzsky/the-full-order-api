package com.example.demo;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

	final static String REAL_HANDLE = "/image/**";
	final static String REAL_PATH = "file:///opt/thefull/uploads/image/";

	final static String DEV_HANDLE = "/image/**";
	final static String DEV_PATH = "file:///C:/Users/user/Desktop/image/";

	final static String LOCAL_HANDLE = "/image/**";
	final static String LOCAL_PATH = "file:///C:/Users/손경원/eclipse-workspace/the-full-order-api/src/main/resources/static/image/";
	// final static String LOCAL_PATH = "file:///C:/Users/wonu/git/the-full-api/src/main/resources/static/image/";
 
	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/**") // ★ context-path(/api)는 빼고!
				.allowedOrigins(
						"http://localhost:5173",
						"http://172.30.1.48:9000",
						"http://52.64.151.137",
						"http://52.64.151.137:9000",
						"http://52.64.151.137:19090",
						"http://52.64.151.137:8092",
						"http://13.236.127.178:8080",
						"http://thefull.kr",
						"http://thefull.kr:9000",
						"http://localhost:8081",
						"http://localhost:19090",
						"http://localhost:8092",
						"http://172.30.1.48:8081",
						"http://13.236.127.178:8080",
						"http://13.236.127.178:8090")
				.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
				.allowedHeaders("Authorization", "x-refresh-token", "Content-Type", "guid", "X-Workspace-Request")
				.exposedHeaders("Authorization", "x-refresh-token")
				.allowCredentials(true)
				.maxAge(3600);
	}

	// /image/** 는 S3ImageController가 보여 준다(2026-11-16: 운영 S3·로컬 디스크를 S3FileStorageService가 고름).
	// 예전 정적 파일 연결(REAL_PATH /opt/thefull/uploads/image)은 file.upload-dir 설정으로 대신한다.
}
