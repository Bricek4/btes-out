package studio.agent.platform.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class SecretBox {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final SecretKeySpec key;

  public SecretBox(String base64Key) {
    byte[] decoded;
    try { decoded = Base64.getDecoder().decode(base64Key); }
    catch (IllegalArgumentException exception) { throw new IllegalArgumentException("encryption key must be base64", exception); }
    if (decoded.length != 32) throw new IllegalArgumentException("encryption key must contain 32 bytes");
    key = new SecretKeySpec(decoded, "AES");
  }

  public String encrypt(String context, String plaintext) {
    if (plaintext == null || plaintext.isBlank()) throw new IllegalArgumentException("secret is required");
    var nonce = new byte[12]; RANDOM.nextBytes(nonce);
    try {
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
      cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
      var encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
    } catch (GeneralSecurityException exception) { throw new IllegalStateException("secret encryption failed", exception); }
  }

  public String decrypt(String context, String ciphertext) {
    try {
      var packed = Base64.getUrlDecoder().decode(ciphertext);
      if (packed.length < 29) throw new IllegalArgumentException("invalid encrypted secret");
      var nonce = java.util.Arrays.copyOfRange(packed, 0, 12);
      var encrypted = java.util.Arrays.copyOfRange(packed, 12, packed.length);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
      cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
      return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException exception) { throw new IllegalArgumentException("encrypted secret could not be authenticated", exception); }
  }

  public static String mask(String value) { return value != null && value.length() > 8 ? "********" + value.substring(value.length() - 4) : "********"; }
}
