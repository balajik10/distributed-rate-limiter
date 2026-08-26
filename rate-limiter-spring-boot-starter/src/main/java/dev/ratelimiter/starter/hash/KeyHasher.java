package dev.ratelimiter.starter.hash;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class KeyHasher {
  private static final HexFormat HEX = HexFormat.of();
  private final byte[] hmacSecret;

  public KeyHasher(String hmacSecret) {
    this.hmacSecret =
        hmacSecret == null || hmacSecret.isBlank()
            ? null
            : hmacSecret.getBytes(StandardCharsets.UTF_8).clone();
  }

  public String digest(String policyId, long policyVersion, String logicalKey) {
    byte[] input =
        (policyId + "\0" + policyVersion + "\0" + logicalKey).getBytes(StandardCharsets.UTF_8);
    return HEX.formatHex(hmacSecret == null ? sha256(input) : hmacSha256(input));
  }

  public boolean usesHmac() {
    return hmacSecret != null;
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM does not provide SHA-256", impossible);
    }
  }

  private byte[] hmacSha256(byte[] input) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hmacSecret, "HmacSHA256"));
      return mac.doFinal(input);
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException("JVM does not provide HmacSHA256", impossible);
    }
  }
}
