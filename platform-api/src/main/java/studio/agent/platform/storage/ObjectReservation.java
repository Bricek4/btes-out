package studio.agent.platform.storage;

public record ObjectReservation(String objectKey, long size, String sha256) {
  public ObjectReservation {
    if (objectKey == null || objectKey.isBlank()) throw new IllegalArgumentException("object key is required");
    if (size < 0) throw new IllegalArgumentException("size must not be negative");
    if (sha256 == null || !sha256.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException("sha256 is invalid");
    sha256 = sha256.toLowerCase(java.util.Locale.ROOT);
  }
  public void verify(long actualSize, String actualSha256) {
    if (size != actualSize) throw new IllegalArgumentException("uploaded object size does not match reservation");
    if (actualSha256 == null || !sha256.equalsIgnoreCase(actualSha256)) throw new IllegalArgumentException("uploaded object checksum does not match reservation");
  }
}
