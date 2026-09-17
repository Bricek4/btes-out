package studio.agent.worker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Builds a deterministic, auditable read plan without silently truncating project-owned text. */
public final class SourceReadPlanner {
  public static final int MAX_ENTRIES = 20_000;
  public static final long MAX_EXPANDED_BYTES = 500L * 1024 * 1024;
  public static final int CHUNK_CHARS = 24_000;
  private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
      ".adoc", ".asciidoc", ".md", ".mdx", ".rst", ".text", ".txt");
  private static final Set<String> CONFIG_EXTENSIONS = Set.of(
      ".conf", ".env", ".ini", ".json", ".properties", ".toml", ".xml", ".yaml", ".yml");
  private static final Set<String> BINARY_EXTENSIONS = Set.of(
      ".7z", ".bmp", ".class", ".dll", ".doc", ".docx", ".gif", ".gz", ".ico", ".jar",
      ".jpeg", ".jpg", ".mov", ".mp3", ".mp4", ".pdf", ".png", ".so", ".tar", ".tif", ".tiff",
      ".ttf", ".wav", ".webp", ".woff", ".woff2", ".xls", ".xlsx", ".zip");
  private static final Set<String> DEPENDENCY_DIRECTORIES = Set.of(
      ".m2", ".pnpm", ".venv", "node_modules", "packages", "vendor", "venv");
  private static final Set<String> GENERATED_DIRECTORIES = Set.of(
      ".git", ".gradle", ".idea", ".next", ".nuxt", "bin", "build", "coverage", "dist", "obj", "out", "target");

  private SourceReadPlanner() { }

  public static SourceReadPlan plan(byte[] archive) {
    if (archive == null || archive.length == 0 || !hasZipSignature(archive)) {
      throw new SourceReadException("SOURCE_ARCHIVE_INVALID");
    }
    String archiveSha256 = sha256(archive);
    List<SourceReadFile> files = new ArrayList<>();
    int entries = 0;
    long expandedBytes = 0;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
      for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
        if (++entries > MAX_ENTRIES) throw new SourceReadException("SOURCE_ARCHIVE_TOO_MANY_ENTRIES");
        String path = entry.getName();
        if (entry.isDirectory()) continue;
        if (unsafePath(path)) throw new SourceReadException("SOURCE_ARCHIVE_UNSAFE_PATH");

        byte[] bytes = readEntry(zip, expandedBytes);
        expandedBytes += bytes.length;
        if (expandedBytes > MAX_EXPANDED_BYTES) {
          throw new SourceReadException("SOURCE_ARCHIVE_EXPANDED_TOO_LARGE");
        }
        String fileSha256 = sha256(bytes);
        SourceFileCategory category = category(path, bytes);
        if (category == SourceFileCategory.DEPENDENCY || category == SourceFileCategory.GENERATED) {
          files.add(new SourceReadFile(path, category, bytes.length, fileSha256, List.of(),
              SourceReadStatus.SKIPPED, category == SourceFileCategory.DEPENDENCY
                  ? "OUT_OF_SCOPE_DEPENDENCY_DIRECTORY" : "OUT_OF_SCOPE_GENERATED_DIRECTORY"));
          continue;
        }
        if (category == SourceFileCategory.BINARY) {
          files.add(new SourceReadFile(path, category, bytes.length, fileSha256, List.of(),
              SourceReadStatus.SKIPPED, "BINARY_CONTENT"));
          continue;
        }
        String text = decodeUtf8(bytes);
        files.add(new SourceReadFile(path, category, bytes.length, fileSha256,
            chunks(path, fileSha256, text), SourceReadStatus.PLANNED, null));
      }
    } catch (IOException invalid) {
      throw new SourceReadException("SOURCE_ARCHIVE_INVALID", invalid);
    }
    if (files.isEmpty()) throw new SourceReadException("SOURCE_EVIDENCE_EMPTY");
    files.sort(Comparator.comparing(SourceReadFile::path));
    return new SourceReadPlan(archiveSha256, entries, expandedBytes, List.copyOf(files));
  }

  public static SourceReadPlan planText(String path, byte[] source) {
    if (path == null || path.isBlank() || unsafePath(path) || source == null || source.length == 0) {
      throw new SourceReadException("SOURCE_TEXT_INVALID");
    }
    if (source.length > MAX_EXPANDED_BYTES) throw new SourceReadException("SOURCE_ARCHIVE_EXPANDED_TOO_LARGE");
    String fileSha256 = sha256(source);
    String text = decodeUtf8(source);
    return new SourceReadPlan(fileSha256, 1, source.length,
        List.of(new SourceReadFile(path, SourceFileCategory.SOURCE, source.length, fileSha256,
            chunks(path, fileSha256, text), SourceReadStatus.PLANNED, null)));
  }

  private static byte[] readEntry(ZipInputStream zip, long expandedBytes) throws IOException {
    var output = new ByteArrayOutputStream();
    var buffer = new byte[8192];
    for (int read; (read = zip.read(buffer)) >= 0; ) {
      if (read == 0) continue;
      if (expandedBytes + output.size() + read > MAX_EXPANDED_BYTES) {
        throw new SourceReadException("SOURCE_ARCHIVE_EXPANDED_TOO_LARGE");
      }
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private static List<SourceReadChunk> chunks(String path, String fileSha256, String text) {
    if (text.isEmpty()) return List.of(chunk(path, fileSha256, 0, 0, 0, ""));
    var result = new ArrayList<SourceReadChunk>();
    int start = 0;
    long startOffset = 0;
    int ordinal = 0;
    while (start < text.length()) {
      int end = Math.min(start + CHUNK_CHARS, text.length());
      if (end < text.length()) {
        int newline = text.lastIndexOf('\n', end - 1);
        if (newline > start + CHUNK_CHARS / 2) end = newline + 1;
      }
      String content = text.substring(start, end);
      long endOffset = startOffset + content.getBytes(StandardCharsets.UTF_8).length;
      result.add(chunk(path, fileSha256, ordinal++, startOffset, endOffset, content));
      start = end;
      startOffset = endOffset;
    }
    return List.copyOf(result);
  }

  private static SourceReadChunk chunk(String path, String fileSha256, int ordinal,
      long startOffset, long endOffset, String content) {
    String id = sha256(path + "\n" + fileSha256 + "\n" + ordinal + "\n" + startOffset + "\n" + endOffset);
    return new SourceReadChunk(id, path, ordinal, startOffset, endOffset, sha256(content), content,
        SourceReadStatus.PLANNED);
  }

  private static SourceFileCategory category(String path, byte[] bytes) {
    String lower = path.toLowerCase(Locale.ROOT);
    for (String part : lower.split("/")) {
      if (DEPENDENCY_DIRECTORIES.contains(part)) return SourceFileCategory.DEPENDENCY;
      if (GENERATED_DIRECTORIES.contains(part)) return SourceFileCategory.GENERATED;
    }
    if (BINARY_EXTENSIONS.stream().anyMatch(lower::endsWith) || containsNul(bytes)) return SourceFileCategory.BINARY;
    if (DOCUMENT_EXTENSIONS.stream().anyMatch(lower::endsWith)) return SourceFileCategory.DOCUMENT;
    if (CONFIG_EXTENSIONS.stream().anyMatch(lower::endsWith)
        || lower.endsWith(".env") || lower.contains("/config/") || lower.startsWith("config/")) {
      return SourceFileCategory.CONFIG;
    }
    return SourceFileCategory.SOURCE;
  }

  private static boolean containsNul(byte[] bytes) {
    for (byte value : bytes) if (value == 0) return true;
    return false;
  }

  private static String decodeUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException invalid) {
      throw new SourceReadException("SOURCE_FILE_ENCODING_UNSUPPORTED", invalid);
    }
  }

  private static boolean unsafePath(String path) {
    if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0) return true;
    for (String part : path.split("/")) if (part.equals("..") || part.equals(".")) return true;
    return false;
  }

  private static boolean hasZipSignature(byte[] archive) {
    return archive.length >= 4 && archive[0] == 'P' && archive[1] == 'K'
        && ((archive[2] == 3 && archive[3] == 4) || (archive[2] == 5 && archive[3] == 6)
            || (archive[2] == 7 && archive[3] == 8));
  }

  private static String sha256(byte[] value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
  }

  private static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
}

enum SourceFileCategory { SOURCE, CONFIG, DOCUMENT, GENERATED, DEPENDENCY, BINARY }

enum SourceReadStatus { PLANNED, READING, ANALYZED, SKIPPED, FAILED }

record SourceReadChunk(String chunkId, String filePath, int ordinal, long startOffset, long endOffset,
                       String sha256, String content, SourceReadStatus status) { }

record SourceReadFile(String path, SourceFileCategory category, long sizeBytes, String sha256,
                      List<SourceReadChunk> chunks, SourceReadStatus status, String skipReason) {
  SourceReadFile {
    chunks = List.copyOf(chunks == null ? List.of() : chunks);
  }
}

record SourceReadPlan(String archiveSha256, int totalEntries, long expandedBytes,
                      List<SourceReadFile> files) {
  SourceReadPlan {
    files = List.copyOf(files == null ? List.of() : files);
  }

  SourceReadFile file(String path) {
    return files.stream().filter(value -> value.path().equals(path)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("source file not found: " + path));
  }

  int totalChunks() { return files.stream().mapToInt(value -> value.chunks().size()).sum(); }
}

final class SourceReadException extends RuntimeException {
  private final String code;

  SourceReadException(String code) { super(code); this.code = code; }
  SourceReadException(String code, Throwable cause) { super(code, cause); this.code = code; }
  String code() { return code; }
}
