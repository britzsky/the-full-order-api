package com.example.demo.service;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 업로드 파일(메뉴·레시피 사진) 저장소. the-full-api(ERP)의 S3FileStorageService와 같은 방식(2026-11-16).
 *
 * - DB에는 지금처럼 '/image/menu/파일.jpg' 같은 경로를 남긴다. 화면은 /image/** 를 그대로 부르고,
 *   S3ImageController가 이 서비스로 파일을 받아 흘려보낸다(S3 주소를 화면에 노출하지 않음 → 버킷 비공개·CORS 설정 불필요).
 * - aws.s3.enabled=true(운영): 버킷 aws.s3.bucket, 키 = aws.s3.key-prefix + '/' + ('/image/' 뒤 경로).
 *   자격 증명은 AWS 기본 체인(EC2 IAM 역할 등) — 설정 파일에 키를 넣지 않는다.
 * - aws.s3.enabled=false(로컬): local.storage.dir(기본 file.upload-dir) 아래 디스크에 저장. AWS 없이 부팅된다.
 */
@Service
public class S3FileStorageService {

	public static final String LEGACY_PREFIX = "/image/";
	/** 파일 이름에 UUID가 붙어 내용이 바뀌지 않으므로 브라우저가 오래 캐시해도 된다 */
	private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofDays(7)).cachePublic();

	private final boolean s3Enabled;
	private final S3Client s3Client;
	private final String bucket;
	private final String keyPrefix;
	private final Path localStorageDir;

	public S3FileStorageService(ObjectProvider<S3Client> s3Client,
			@Value("${aws.s3.enabled:false}") boolean s3Enabled,
			@Value("${aws.s3.bucket:}") String bucket,
			@Value("${aws.s3.key-prefix:}") String keyPrefix,
			@Value("${local.storage.dir:${file.upload-dir:./local-uploads}}") String localStorageDir) {
		this.s3Enabled = s3Enabled;
		this.s3Client = s3Client.getIfAvailable();
		this.bucket = bucket;
		this.keyPrefix = normalizePrefix(keyPrefix);
		this.localStorageDir = Paths.get(localStorageDir).toAbsolutePath().normalize();
	}

	public boolean isS3Enabled() {
		return s3Enabled;
	}

	/**
	 * 바이트를 저장하고 '/image/폴더/UUID_파일이름' 경로를 돌려준다.
	 * @param pathSegments 폴더(예: "menu", "recipe")
	 */
	public String uploadBytes(byte[] bytes, String filename, String contentType, String... pathSegments) throws IOException {
		if (s3Enabled) requireConfiguredBucket();
		if (bytes == null || bytes.length == 0) {
			throw new IllegalArgumentException("저장할 파일 내용이 없습니다.");
		}

		String prefix = Arrays.stream(pathSegments).map(this::sanitizeSegment).filter(segment -> !segment.isEmpty())
				.collect(Collectors.joining("/"));
		String objectKey = (prefix.isEmpty() ? "" : prefix + "/") + UUID.randomUUID() + "_" + sanitizeFilename(filename);
		String physicalKey = physicalKey(objectKey);

		if (!s3Enabled) {
			Path target = resolveLocalPath(physicalKey);
			Files.createDirectories(target.getParent());
			Files.write(target, bytes);
			return LEGACY_PREFIX + objectKey;
		}

		PutObjectRequest.Builder request = PutObjectRequest.builder().bucket(bucket).key(physicalKey);
		if (contentType != null && !contentType.isBlank()) {
			request.contentType(contentType);
		}
		s3Client.putObject(request.build(), RequestBody.fromBytes(bytes));
		return LEGACY_PREFIX + objectKey;
	}

	/**
	 * 저장된 파일을 서버가 직접 받아 그대로 흘려보낸다(S3로 302 리다이렉트하지 않음 — 화면이 fetch로 읽어도 CORS 문제 없음).
	 */
	public ResponseEntity<InputStreamResource> streamObject(String storedPath, boolean download) {
		String objectKey;
		try {
			objectKey = toObjectKey(storedPath);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.notFound().build();
		}
		String filename = objectKey.substring(objectKey.lastIndexOf('/') + 1).replace("\r", "").replace("\n", "");
		String disposition = (download ? "attachment" : "inline") + "; filename*=UTF-8''"
				+ URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");

		if (!s3Enabled) {
			Path localPath = resolveLocalPath(physicalKey(objectKey));
			if (!Files.isRegularFile(localPath)) {
				return ResponseEntity.notFound().build();
			}
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(mediaType(probe(localPath)));
			try {
				headers.setContentLength(Files.size(localPath));
			} catch (IOException ignored) {
				// 길이는 부가 정보라 실패해도 계속
			}
			headers.set(HttpHeaders.CONTENT_DISPOSITION, disposition);
			headers.setCacheControl(CACHE);
			try {
				return ResponseEntity.ok().headers(headers).body(new InputStreamResource(Files.newInputStream(localPath)));
			} catch (IOException e) {
				throw new IllegalStateException("로컬 파일을 읽을 수 없습니다: " + localPath, e);
			}
		}

		requireConfiguredBucket();
		ResponseInputStream<GetObjectResponse> s3Object;
		try {
			s3Object = s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(physicalKey(objectKey)).build());
		} catch (NoSuchKeyException e) {
			return ResponseEntity.notFound().build();
		}
		GetObjectResponse meta = s3Object.response();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(mediaType(meta.contentType()));
		if (meta.contentLength() != null) {
			headers.setContentLength(meta.contentLength());
		}
		headers.set(HttpHeaders.CONTENT_DISPOSITION, disposition);
		headers.setCacheControl(CACHE);
		return ResponseEntity.ok().headers(headers).body(new InputStreamResource(s3Object));
	}

	public StoredObjectMetadata metadata(String storedPath) {
		String objectKey = toObjectKey(storedPath);
		String filename = objectKey.substring(objectKey.lastIndexOf('/') + 1);

		if (!s3Enabled) {
			Path localPath = resolveLocalPath(physicalKey(objectKey));
			if (!Files.isRegularFile(localPath)) {
				throw NoSuchKeyException.builder().message("로컬 파일이 없습니다: " + localPath).build();
			}
			try {
				return new StoredObjectMetadata(filename, probe(localPath), Files.size(localPath));
			} catch (IOException e) {
				throw new IllegalStateException("로컬 파일 정보를 읽을 수 없습니다: " + localPath, e);
			}
		}

		requireConfiguredBucket();
		HeadObjectResponse response = s3Client
				.headObject(HeadObjectRequest.builder().bucket(bucket).key(physicalKey(objectKey)).build());
		return new StoredObjectMetadata(filename, response.contentType(), response.contentLength());
	}

	/** 그 경로에 파일이 있는지. 경로 모양이 잘못됐으면 false */
	public boolean exists(String storedPath) {
		try {
			metadata(storedPath);
			return true;
		} catch (NoSuchKeyException | IllegalArgumentException e) {
			return false;
		} catch (S3Exception e) {
			if (e.statusCode() == 404) return false;
			throw e;
		}
	}

	/** 실제 저장 위치(tb_recipe_image.file_path 등 기록용): 로컬은 절대 경로, S3는 s3://버킷/키 */
	public String location(String storedPath) {
		String physicalKey = physicalKey(toObjectKey(storedPath));
		return s3Enabled ? "s3://" + bucket + "/" + physicalKey : resolveLocalPath(physicalKey).toString();
	}

	/** '/image/menu/a.jpg', 'http://서버/api/image/menu/a.jpg', 'image/menu/a.jpg' → 'menu/a.jpg' */
	public String toObjectKey(String storedPath) {
		if (storedPath == null || storedPath.isBlank()) {
			throw new IllegalArgumentException("파일 경로가 비어 있습니다.");
		}
		String normalized = decodeRepeatedly(storedPath.trim()).replace('\\', '/');
		int queryIndex = normalized.indexOf('?');
		if (queryIndex >= 0) normalized = normalized.substring(0, queryIndex);
		int hashIndex = normalized.indexOf('#');
		if (hashIndex >= 0) normalized = normalized.substring(0, hashIndex);
		int imageIndex = normalized.indexOf(LEGACY_PREFIX);
		if (imageIndex >= 0) normalized = normalized.substring(imageIndex + LEGACY_PREFIX.length());
		else if (normalized.startsWith("image/")) normalized = normalized.substring("image/".length());
		while (normalized.startsWith("/")) normalized = normalized.substring(1);
		if (normalized.isBlank() || normalized.equals("..") || normalized.contains("../") || normalized.endsWith("/..")) {
			throw new IllegalArgumentException("허용되지 않는 파일 경로입니다.");
		}
		return normalized;
	}

	private static MediaType mediaType(String value) {
		try {
			return value == null || value.isBlank() ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(value);
		} catch (Exception e) {
			return MediaType.APPLICATION_OCTET_STREAM;
		}
	}

	private static String probe(Path path) {
		try {
			return Files.probeContentType(path);
		} catch (IOException e) {
			return null;
		}
	}

	private String sanitizeSegment(String value) {
		if (value == null) return "";
		return value.trim().replace("/", "_").replace("\\", "_").replace("..", "_");
	}

	private String physicalKey(String objectKey) {
		return keyPrefix.isEmpty() ? objectKey : keyPrefix + "/" + objectKey;
	}

	private String normalizePrefix(String value) {
		if (value == null) return "";
		String normalized = value.trim().replace('\\', '/');
		while (normalized.startsWith("/")) normalized = normalized.substring(1);
		while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
		if (normalized.contains("..")) {
			throw new IllegalArgumentException("aws.s3.key-prefix에 '..'을 사용할 수 없습니다.");
		}
		return normalized;
	}

	private String sanitizeFilename(String value) {
		String filename = value == null ? "file" : value.replace('\\', '/');
		// 연속 점은 하나로 줄여 확장자를 유지하고, 경로·윈도우 금지 문자는 '_'로
		filename = filename.substring(filename.lastIndexOf('/') + 1).trim().replaceAll("\\.{2,}", ".")
				.replaceAll("[:*?\"<>|]", "_");
		return filename.isEmpty() ? "file" : filename;
	}

	/** 인코딩된 경로를 원래대로. '+'는 공백으로 바꾸지 않고, 더 풀 수 없으면 그때 값 */
	private String decodeRepeatedly(String value) {
		String decoded = value;
		for (int i = 0; i < 3; i++) {
			String next;
			try {
				next = URLDecoder.decode(decoded.replace("+", "%2B"), StandardCharsets.UTF_8);
			} catch (IllegalArgumentException e) {
				break;
			}
			if (next.equals(decoded)) break;
			decoded = next;
		}
		return decoded;
	}

	private void requireConfiguredBucket() {
		if (bucket == null || bucket.isBlank()) {
			throw new IllegalStateException("AWS_S3_BUCKET 환경변수 또는 aws.s3.bucket 설정이 필요합니다.");
		}
	}

	/** 로컬 저장일 때만: 저장 루트 밖으로 나가는 경로('..' 등)는 막는다 */
	private Path resolveLocalPath(String physicalKey) {
		Path resolved = localStorageDir.resolve(physicalKey).normalize();
		if (!resolved.startsWith(localStorageDir)) {
			throw new IllegalArgumentException("허용되지 않는 파일 경로입니다.");
		}
		return resolved;
	}

	public record StoredObjectMetadata(String filename, String contentType, long contentLength) {
	}
}
