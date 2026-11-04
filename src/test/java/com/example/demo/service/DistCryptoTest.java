package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class DistCryptoTest {
	private final DistCrypto crypto = new DistCrypto(Base64.getEncoder().encodeToString(new byte[32]));

	@Test void roundTripsAndUsesRandomIv() {
		String a = crypto.encrypt("홍길동"), b = crypto.encrypt("홍길동");
		assertThat(a).startsWith("v1:").isNotEqualTo(b).doesNotContain("홍길동");
		assertThat(crypto.decrypt(a)).isEqualTo("홍길동");
		assertThat(crypto.encrypt("")).isNull();
		assertThat(crypto.decrypt(null)).isEmpty();
	}

	@Test void hashIsStable() {
		assertThat(crypto.hash("01012345678")).isEqualTo(crypto.hash("01012345678")).hasSize(64);
	}

	@Test void otherKeyCannotRead() {
		byte[] other = new byte[32];
		other[0] = 1;
		String stored = crypto.encrypt("서울시");
		assertThatThrownBy(() -> new DistCrypto(Base64.getEncoder().encodeToString(other)).decrypt(stored)).isInstanceOf(IllegalStateException.class);
	}

	@Test void missingKeyBlocksEncryption() {
		DistCrypto none = new DistCrypto("");
		assertThat(none.configured()).isFalse();
		assertThatThrownBy(() -> none.encrypt("x")).hasMessageContaining("암호화 키");
		assertThatThrownBy(() -> new DistCrypto(Base64.getEncoder().encodeToString(new byte[16]))).isInstanceOf(IllegalStateException.class);
	}

	@Test void masks() {
		assertThat(DistCrypto.maskName("홍길동")).isEqualTo("홍*동");
		assertThat(DistCrypto.maskName("김철")).isEqualTo("김*");
		assertThat(DistCrypto.maskName("남궁민수")).isEqualTo("남**수");
		assertThat(DistCrypto.maskPhone("010-1234-5678")).isEqualTo("010-****-5678");
	}
}
