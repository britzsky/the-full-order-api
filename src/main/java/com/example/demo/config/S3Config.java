package com.example.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * aws.s3.enabled=true(운영)일 때만 S3 클라이언트를 만든다. 로컬(false)은 AWS 자격 증명·리전 없이 부팅되고
 * S3FileStorageService가 디스크에 저장한다. 자격 증명은 AWS 기본 체인(EC2 IAM 역할 → 환경변수 등).
 */
@Configuration
@ConditionalOnProperty(prefix = "aws.s3", name = "enabled", havingValue = "true")
public class S3Config {

	@Bean
	S3Client s3Client(@Value("${aws.s3.region}") String region) {
		return S3Client.builder().region(Region.of(region)).build();
	}
}
