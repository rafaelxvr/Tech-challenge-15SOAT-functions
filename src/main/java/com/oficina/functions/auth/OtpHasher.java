package com.oficina.functions.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

public final class OtpHasher {
    private final SecureRandom random = new SecureRandom();

    public byte[] novoSalt() {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return salt;
    }

    public byte[] hash(String codigo, byte[] salt) {
        if (codigo == null || !codigo.matches("[0-9]{6}") || salt == null || salt.length < 16) {
            throw new IllegalArgumentException("Invalid OTP hash input");
        }
        PBEKeySpec spec = new PBEKeySpec(codigo.toCharArray(), salt, 120_000, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("OTP hashing unavailable");
        } finally {
            spec.clearPassword();
        }
    }

    public boolean verificar(String codigo, byte[] salt, byte[] esperado) {
        if (codigo == null || !codigo.matches("[0-9]{6}")) return false;
        byte[] atual = hash(codigo, salt);
        try {
            return MessageDigest.isEqual(atual, esperado);
        } finally {
            Arrays.fill(atual, (byte) 0);
        }
    }
}
