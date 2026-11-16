package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** 업로드 사진 저장소: 로컬 디스크 모드 저장·보기·경로 막기, S3 모드 키 이름 */
class S3FileStorageServiceTest {

	@TempDir
	Path dir;

	private static <T> ObjectProvider<T> provider(T instance) {
		return new ObjectProvider<T>() {
			@Override
			public T getObject() {
				return instance;
			}

			@Override
			public T getObject(Object... args) {
				return instance;
			}

			@Override
			public T getIfAvailable() {
				return instance;
			}

			@Override
			public T getIfUnique() {
				return instance;
			}
		};
	}

	private S3FileStorageService local() {
		return new S3FileStorageService(provider(null), false, "", "", dir.toString());
	}

	@Test
	void localModeSavesUnderFolderAndStreamsBack() throws Exception {
		S3FileStorageService storage = local();
		byte[] bytes = "png-bytes".getBytes(StandardCharsets.UTF_8);

		String stored = storage.uploadBytes(bytes, "김치찌개.png", "image/png", "menu");

		assertThat(stored).startsWith("/image/menu/").endsWith("_김치찌개.png");
		Path onDisk = dir.resolve(stored.substring("/image/".length()));
		assertThat(Files.readAllBytes(onDisk)).isEqualTo(bytes);
		assertThat(storage.exists(stored)).isTrue();
		assertThat(storage.location(stored)).isEqualTo(onDisk.toAbsolutePath().normalize().toString());
		assertThat(storage.metadata(stored).contentLength()).isEqualTo(bytes.length);

		// 화면은 컨텍스트 경로가 붙은 주소(/api/image/...)나 인코딩된 주소로 부를 수 있다
		ResponseEntity<InputStreamResource> response = storage.streamObject("/api" + stored, false);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		try (var in = response.getBody().getInputStream()) {
			assertThat(in.readAllBytes()).isEqualTo(bytes);
		}
		assertThat(response.getHeaders().getCacheControl()).contains("max-age");
	}

	@Test
	void missingOrOutsidePathsAreNotFound() {
		S3FileStorageService storage = local();
		assertThat(storage.streamObject("/image/menu/none.png", false).getStatusCode().value()).isEqualTo(404);
		assertThat(storage.streamObject("/image/menu/../../secret.txt", false).getStatusCode().value()).isEqualTo(404);
		assertThat(storage.exists("/image/../x")).isFalse();
		assertThatThrownBy(() -> storage.toObjectKey("/image/recipe/../secret.txt")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void objectKeyFromStoredPath() {
		S3FileStorageService storage = local();
		assertThat(storage.toObjectKey("/image/recipe/a.jpg")).isEqualTo("recipe/a.jpg");
		assertThat(storage.toObjectKey("image/recipe/a.jpg")).isEqualTo("recipe/a.jpg");
		assertThat(storage.toObjectKey("http://thefull.kr/api/image/menu/a.jpg?v=1")).isEqualTo("menu/a.jpg");
		assertThat(storage.toObjectKey("%2Fimage%2Fmenu%2Fa%2520b.jpg")).isEqualTo("menu/a b.jpg");
	}

	@Test
	void s3ModePutsUnderKeyPrefixAndKeepsLegacyPath() throws Exception {
		S3Client client = mock(S3Client.class);
		S3FileStorageService storage = new S3FileStorageService(provider(client), true, "thefull-upload", "/uploads/image/",
				dir.toString());

		String stored = storage.uploadBytes(new byte[] { 1, 2, 3 }, "a.jpg", "image/jpeg", "recipe");

		ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
		verify(client).putObject(request.capture(), any(RequestBody.class));
		assertThat(request.getValue().bucket()).isEqualTo("thefull-upload");
		assertThat(request.getValue().key()).isEqualTo("uploads/image/" + stored.substring("/image/".length()));
		assertThat(request.getValue().contentType()).isEqualTo("image/jpeg");
		assertThat(stored).startsWith("/image/recipe/").endsWith("_a.jpg");
		assertThat(storage.location(stored)).isEqualTo("s3://thefull-upload/uploads/image/" + stored.substring("/image/".length()));
		assertThat(Files.list(dir)).isEmpty();
	}

	@Test
	void s3ModeWithoutBucketFailsClearly() {
		S3FileStorageService storage = new S3FileStorageService(provider(mock(S3Client.class)), true, "", "", dir.toString());
		assertThatThrownBy(() -> storage.uploadBytes(new byte[] { 1 }, "a.jpg", "image/jpeg", "menu"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("aws.s3.bucket");
	}
}
