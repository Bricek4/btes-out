package studio.agent.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

public final class TokenDigest {
  private static final SecureRandom RANDOM = new SecureRandom();
  private TokenDigest() {}
  public static String issue() { var bytes = new byte[32]; RANDOM.nextBytes(bytes); return HexFormat.of().formatHex(bytes); }
  public static String hash(String token) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
}
