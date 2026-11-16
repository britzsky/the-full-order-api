package com.example.demo.controller;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.service.S3FileStorageService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 업로드 이미지 보기(/image/**)·내려받기(/download/image/**). 예전 WebConfig 정적 파일 연결(/opt/thefull/uploads/image)을 대신한다.
 * 저장 위치는 S3FileStorageService가 정한다(운영 S3, 로컬 디스크). DB의 '/image/...' 경로는 그대로 쓴다.
 */
@RestController
public class S3ImageController {

	private final S3FileStorageService fileStorageService;

	public S3ImageController(S3FileStorageService fileStorageService) {
		this.fileStorageService = fileStorageService;
	}

	@GetMapping("/image/**")
	public ResponseEntity<InputStreamResource> view(HttpServletRequest request) {
		return stream(request, false);
	}

	@GetMapping("/download/image/**")
	public ResponseEntity<InputStreamResource> download(HttpServletRequest request) {
		return stream(request, true);
	}

	private ResponseEntity<InputStreamResource> stream(HttpServletRequest request, boolean download) {
		String requestPath = request.getRequestURI();
		int imageIndex = requestPath.indexOf(S3FileStorageService.LEGACY_PREFIX);
		if (imageIndex < 0) return ResponseEntity.notFound().build();
		return fileStorageService.streamObject(requestPath.substring(imageIndex), download);
	}
}
