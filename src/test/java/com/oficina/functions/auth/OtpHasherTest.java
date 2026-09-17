package com.oficina.functions.auth;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class OtpHasherTest {
    private final OtpHasher hasher = new OtpHasher();

    @Test void matchesIndependentPbkdf2Sha256VectorAt120000IterationsAnd256Bits() {
        byte[] salt = HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f");
        // Independently calculated with Python hashlib.pbkdf2_hmac.
        assertThat(HexFormat.of().formatHex(hasher.hash("123456", salt)))
                .isEqualTo("8e8969ceadd8a2436d1f93af29a693c244eb6c659390930fc4832f86caab330e");
    }

    @Test void comparesCodesAndRejectsMalformedCodes() {
        byte[] salt = hasher.novoSalt();
        byte[] hash = hasher.hash("123456", salt);
        assertThat(hash).hasSize(32);
        assertThat(hasher.verificar("123456", salt, hash)).isTrue();
        assertThat(hasher.verificar("123457", salt, hash)).isFalse();
        assertThat(hasher.verificar(null, salt, hash)).isFalse();
        assertThat(hasher.verificar("１２３４５６", salt, hash)).isFalse();
        assertThat(hasher.verificar("123456", salt, new byte[0])).isFalse();
    }

    @Test void saltsAreFreshAndHashesDiffer() {
        byte[] first = hasher.novoSalt();
        byte[] second = hasher.novoSalt();
        assertThat(first).hasSize(16).isNotEqualTo(second);
        assertThat(hasher.hash("123456", first)).isNotEqualTo(hasher.hash("123456", second));
    }

    @Test void invalidHashInputIsRejectedWithoutEchoingIt() {
        assertThatThrownBy(() -> hasher.hash(null, new byte[16])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.hash("sensitive", new byte[16]))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid OTP hash input");
        assertThatThrownBy(() -> hasher.hash("123456", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.hash("123456", new byte[15])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void challengeCopiesArraysAtConstructionAndOnEveryAccess() {
        byte[] salt = hasher.novoSalt();
        byte[] hash = hasher.hash("123456", salt);
        byte[] expectedSalt = salt.clone();
        byte[] expectedHash = hash.clone();
        Desafio desafio = new Desafio(UUID.randomUUID(), "protected-hash", null, 0,
                salt, hash, Instant.EPOCH);
        salt[0]++;
        hash[0]++;
        desafio.salt()[1]++;
        desafio.hash()[1]++;
        assertThat(desafio.salt()).containsExactly(expectedSalt);
        assertThat(desafio.hash()).containsExactly(expectedHash);
    }

    @Test void secureGeneratorProducesExactlySixAsciiDigits() {
        GeradorCodigo generator = new SecureCodigoGenerator();
        for (int i = 0; i < 100; i++) assertThat(generator.gerar()).matches("[0-9]{6}");
    }
}
