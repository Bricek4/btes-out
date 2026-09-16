package studio.agent.platform.importing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Imports a shallow Git revision without allowing arbitrary server-side network access.
 *
 * <p>The URL is checked before every JGit HTTP connection. Redirects are disabled at the
 * connection layer, so a redirect can only be followed by JGit after the next URL has passed the
 * same allowlist and private-address checks. Operators should still enforce outbound egress at
 * the host/container firewall; DNS is deliberately resolved again for each connection.</p>
 */
@Service
public class GitImportService {
  private static final long DEFAULT_MAX_ARCHIVE_BYTES = 100L * 1024 * 1024;
  private static final long DEFAULT_MAX_FILES = 20_000;
  private static final int DEFAULT_TIMEOUT_SECONDS = 90;
  private static final Set<String> DEFAULT_ALLOWED_HOSTS = Set.of("github.com", "gitlab.com", "bitbucket.org");

  private final GitPolicy policy;

  /** Used by focused unit tests that do not boot Spring. */
  public GitImportService() {
    this(new GitPolicy(DEFAULT_ALLOWED_HOSTS, DEFAULT_MAX_ARCHIVE_BYTES, DEFAULT_MAX_FILES,
        Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS)));
  }

  @Autowired
  public GitImportService(
      @Value("${agent-studio.git.allowed-hosts:github.com,gitlab.com,bitbucket.org}") String allowedHosts,
      @Value("${agent-studio.git.max-archive-bytes:104857600}") long maxArchiveBytes,
      @Value("${agent-studio.git.max-files:20000}") long maxFiles,
      @Value("${agent-studio.git.timeout-seconds:90}") long timeoutSeconds) {
    this(new GitPolicy(parseHosts(allowedHosts), maxArchiveBytes, maxFiles,
        Duration.ofSeconds(Math.max(5, Math.min(timeoutSeconds, 300)))));
  }

  GitImportService(GitPolicy policy) {
    this.policy = policy;
  }

  public List<String> branches(String rawUrl, String token) {
    URI url = policy.validate(rawUrl);
    try {
      Collection<Ref> refs = Git.lsRemoteRepository()
          .setRemote(url.toString())
          .setHeads(true)
          .setTags(false)
          .setCredentialsProvider(credentials(token))
          .setTransportConfigCallback(policy.transportCallback())
          .call();
      return refs.stream()
          .map(Ref::getName)
          .filter(name -> name.startsWith("refs/heads/"))
          .map(name -> name.substring("refs/heads/".length()))
          .filter(name -> !name.isBlank())
          .sorted()
          .toList();
    } catch (Exception failure) {
      throw new IllegalStateException("GIT_BRANCH_DISCOVERY_FAILED");
    }
  }

  public GitArchive archive(String rawUrl, String branch, String token) {
    URI url = policy.validate(rawUrl);
    String selectedBranch = policy.validateBranch(branch);
    PathHolder directory = null;
    try {
      directory = new PathHolder(Files.createTempDirectory("agent-studio-git-"));
      var command = Git.cloneRepository()
          .setURI(url.toString())
          .setDirectory(directory.path().toFile())
          .setCloneAllBranches(false)
          .setNoCheckout(false)
          .setDepth(1)
          .setCredentialsProvider(credentials(token))
          .setTransportConfigCallback(policy.transportCallback());
      if (selectedBranch != null) command.setBranch(selectedBranch);
      try (Git git = command.call()) {
        String commit = git.getRepository().resolve("HEAD").name();
        byte[] bytes = zipDirectory(directory.path(), policy.maxArchiveBytes(), policy.maxFiles());
        return new GitArchive(commit, bytes);
      }
    } catch (IllegalArgumentException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException("GIT_IMPORT_FAILED");
    } finally {
      delete(directory == null ? null : directory.path());
    }
  }

  private CredentialsProvider credentials(String token) {
    if (token == null || token.isBlank()) return CredentialsProvider.getDefault();
    if (token.length() > 512) throw new IllegalArgumentException("Git token is too long");
    return new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider("x-access-token", token);
  }

  private static Set<String> parseHosts(String value) {
    if (value == null || value.isBlank()) return DEFAULT_ALLOWED_HOSTS;
    var hosts = new HashSet<String>();
    for (String raw : value.split(",")) {
      String host = raw.trim().toLowerCase(Locale.ROOT);
      if (host.isBlank()) continue;
      if (!host.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")) {
        throw new IllegalArgumentException("Git allowed host is invalid");
      }
      hosts.add(host);
    }
    if (hosts.isEmpty()) throw new IllegalArgumentException("at least one Git allowed host is required");
    return Set.copyOf(hosts);
  }

  private static byte[] zipDirectory(java.nio.file.Path root, long maxBytes, long maxFiles) throws IOException {
    if (maxBytes <= 0 || maxBytes > 1_000_000_000L || maxFiles <= 0 || maxFiles > 1_000_000L) {
      throw new IllegalArgumentException("Git import limits are invalid");
    }
    var out = new BoundedByteArrayOutputStream(maxBytes);
    long count = 0;
    try (ZipOutputStream zip = new ZipOutputStream(out); var paths = Files.walk(root)) {
      for (java.nio.file.Path path : paths.filter(Files::isRegularFile)
          .filter(candidate -> !candidate.startsWith(root.resolve(".git")))
          .sorted()
          .toList()) {
        if (++count > maxFiles) throw new IllegalArgumentException("Git repository has too many files");
        java.nio.file.Path relative = root.relativize(path).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
          throw new IllegalArgumentException("Git repository contains an unsafe path");
        }
        String name = relative.toString().replace('\\', '/');
        zip.putNextEntry(new ZipEntry(name));
        try (var input = Files.newInputStream(path)) {
          input.transferTo(zip);
        }
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }

  private static void delete(java.nio.file.Path root) {
    if (root == null) return;
    try (var paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
      });
    } catch (IOException ignored) { }
  }

  public record GitArchive(String commit, byte[] bytes) {
    public GitArchive {
      if (commit == null || !commit.matches("[0-9a-fA-F]{40,64}")) {
        throw new IllegalArgumentException("Git commit is invalid");
      }
      if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Git archive is empty");
      bytes = bytes.clone();
    }
    @Override public byte[] bytes() { return bytes.clone(); }
  }

  static final class GitPolicy {
    private final Set<String> allowedHosts;
    private final long maxArchiveBytes;
    private final long maxFiles;
    private final Duration timeout;

    GitPolicy(Set<String> allowedHosts, long maxArchiveBytes, long maxFiles, Duration timeout) {
      this.allowedHosts = Set.copyOf(allowedHosts);
      this.maxArchiveBytes = maxArchiveBytes;
      this.maxFiles = maxFiles;
      this.timeout = timeout;
    }

    URI validate(String raw) {
      if (raw == null || raw.isBlank() || raw.length() > 2_048) {
        throw new IllegalArgumentException("Git URL is invalid");
      }
      try {
        URI uri = new URI(raw.trim()).normalize();
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(scheme) || host == null || host.isBlank()
            || uri.getUserInfo() != null || uri.getFragment() != null
            || (uri.getPort() != -1 && uri.getPort() != 443)
            || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
          throw new IllegalArgumentException("Git URL is not allowed");
        }
        assertPublic(host);
        return uri;
      } catch (URISyntaxException failure) {
        throw new IllegalArgumentException("Git URL is invalid");
      }
    }

    String validateBranch(String branch) {
      if (branch == null || branch.isBlank()) return null;
      String value = branch.trim();
      if (value.length() > 255 || value.startsWith("-") || value.endsWith(".")
          || value.contains("..") || value.contains("//") || value.contains("@{")) {
        throw new IllegalArgumentException("Git branch is invalid");
      }
      if (!value.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,254}")) {
        throw new IllegalArgumentException("Git branch is invalid");
      }
      return value;
    }

    TransportConfigCallback transportCallback() {
      HttpConnectionFactory delegate = new JDKHttpConnectionFactory();
      return transport -> {
        transport.setTimeout(Math.toIntExact(timeout.toSeconds()));
        if (transport instanceof TransportHttp http) {
          http.setHttpConnectionFactory(new ValidatingConnectionFactory(delegate, this));
        }
      };
    }

    long maxArchiveBytes() { return maxArchiveBytes; }
    long maxFiles() { return maxFiles; }

    private void assertPublic(String host) {
      try {
        for (InetAddress address : InetAddress.getAllByName(host)) {
          if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
              || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            throw new IllegalArgumentException("Git host resolves to a private address");
          }
        }
      } catch (java.net.UnknownHostException failure) {
        throw new IllegalArgumentException("Git host could not be resolved");
      }
    }
  }

  private static final class ValidatingConnectionFactory implements HttpConnectionFactory {
    private final HttpConnectionFactory delegate;
    private final GitPolicy policy;

    ValidatingConnectionFactory(HttpConnectionFactory delegate, GitPolicy policy) {
      this.delegate = delegate;
      this.policy = policy;
    }

    @Override public HttpConnection create(URL url) throws IOException {
      policy.validate(url.toExternalForm());
      HttpConnection connection = delegate.create(url);
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(Math.toIntExact(policy.timeout.toMillis()));
      connection.setReadTimeout(Math.toIntExact(policy.timeout.toMillis()));
      return connection;
    }

    @Override public HttpConnection create(URL url, Proxy proxy) throws IOException {
      policy.validate(url.toExternalForm());
      HttpConnection connection = delegate.create(url, proxy);
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(Math.toIntExact(policy.timeout.toMillis()));
      connection.setReadTimeout(Math.toIntExact(policy.timeout.toMillis()));
      return connection;
    }
  }

  private static final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
    private final long limit;
    BoundedByteArrayOutputStream(long limit) { this.limit = limit; }
    @Override public synchronized void write(int value) {
      ensure(1);
      super.write(value);
    }
    @Override public synchronized void write(byte[] bytes, int offset, int length) {
      ensure(length);
      super.write(bytes, offset, length);
    }
    private void ensure(long additional) {
      if ((long) count + additional > limit) throw new IllegalArgumentException("Git archive exceeds size limit");
    }
  }

  private record PathHolder(java.nio.file.Path path) { }
}
