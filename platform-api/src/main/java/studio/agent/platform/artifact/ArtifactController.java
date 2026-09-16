package studio.agent.platform.artifact;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import studio.agent.platform.security.CurrentUser;

@RestController
@RequestMapping("/api/v1")
public class ArtifactController {
  private final ArtifactService artifacts;

  public ArtifactController(ArtifactService artifacts) {
    this.artifacts = artifacts;
  }

  @GetMapping("/tasks/{taskId}/artifacts")
  List<ArtifactTreeBuilder.Node> tree(CurrentUser user, @PathVariable UUID taskId) {
    return artifacts.tree(user, taskId);
  }

  @GetMapping("/artifacts/{artifactId}")
  ArtifactService.ArtifactDetail detail(CurrentUser user, @PathVariable UUID artifactId,
                                        @RequestParam(required = false) Integer beforeVersion,
                                        @RequestParam(defaultValue = "20") int limit) {
    return artifacts.detail(user, artifactId, beforeVersion, limit);
  }

  @GetMapping("/artifacts/{artifactId}/versions/{version}")
  ArtifactService.VersionView version(CurrentUser user, @PathVariable UUID artifactId, @PathVariable int version) {
    return artifacts.versionDetail(user, artifactId, version);
  }

  @GetMapping("/artifacts/{artifactId}/preview")
  ResponseEntity<byte[]> preview(CurrentUser user, @PathVariable UUID artifactId,
                                 @RequestParam(required = false) Integer version) {
    var preview = artifacts.preview(user, artifactId, version);
    var stored = preview.version();
    return ResponseEntity.ok().contentType(safePreviewMediaType(stored.mediaType()))
        .cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.inline().filename(stored.name(), StandardCharsets.UTF_8).build().toString())
        .header("Content-Security-Policy", "sandbox; default-src 'none'; img-src data: blob:; style-src 'unsafe-inline'")
        .header("X-Content-Type-Options", "nosniff").body(preview.content());
  }

  @GetMapping("/artifacts/{artifactId}/download")
  ResponseEntity<StreamingResponseBody> download(CurrentUser user, @PathVariable UUID artifactId,
                                @RequestParam(required = false) Integer version) {
    var stored = artifacts.readableVersion(user, artifactId, version);
    StreamingResponseBody body = output -> artifacts.streamDownload(stored, output);
    return ResponseEntity.ok().contentType(safePreviewMediaType(stored.mediaType()))
        .cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(stored.name(), StandardCharsets.UTF_8).build().toString())
        .header("X-Content-Type-Options", "nosniff").body(body);
  }

  @GetMapping(value = {"/tasks/{taskId}/artifacts/export", "/tasks/{taskId}/artifacts/export.zip"}, produces = "application/zip")
  ResponseEntity<StreamingResponseBody> export(CurrentUser user, @PathVariable UUID taskId) {
    var plan = artifacts.exportPlan(user, taskId);
    return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
        .cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("artifacts-" + taskId + ".zip", StandardCharsets.UTF_8).build().toString())
        .body(output -> artifacts.writeExport(plan, output));
  }

  @PutMapping(value = "/artifacts/{artifactId}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ArtifactService.VersionView replaceImage(CurrentUser user, @PathVariable UUID artifactId,
                                           @RequestPart("file") MultipartFile file) throws java.io.IOException {
    if (file.getSize() > ArtifactService.MAX_REPLACEMENT_BYTES) throw new IllegalArgumentException("replacement image exceeds size limit");
    return artifacts.replaceImage(user, artifactId, file.getContentType(), file.getBytes());
  }

  @PostMapping("/artifacts/{artifactId}/verify")
  ArtifactVerifier.Report verify(CurrentUser user, @PathVariable UUID artifactId) {
    return artifacts.verify(user, artifactId);
  }

  static MediaType safePreviewMediaType(String value) {
    try {
      var parsed = MediaType.parseMediaType(value);
      String normalized = parsed.getType() + "/" + parsed.getSubtype();
      return switch (normalized) {
        case "image/png", "image/jpeg", "image/gif", "image/webp", "text/plain", "text/markdown", "application/json" ->
            new MediaType(parsed.getType(), parsed.getSubtype());
        case "text/html", "application/xhtml+xml", "image/svg+xml", "text/xml", "application/xml" -> MediaType.TEXT_PLAIN;
        default -> MediaType.APPLICATION_OCTET_STREAM;
      };
    } catch (IllegalArgumentException exception) {
      return MediaType.APPLICATION_OCTET_STREAM;
    }
  }
}
