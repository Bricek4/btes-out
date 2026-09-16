package studio.agent.platform.product;

import jakarta.annotation.PreDestroy;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hc.client5.http.DnsResolver;
import org.springframework.stereotype.Component;

@Component
final class ProviderEndpointPolicy {
  private static final Duration DNS_TIMEOUT = Duration.ofSeconds(3);

  interface Resolver {
    InetAddress[] resolve(String host) throws Exception;
  }

  private final Resolver resolver;
  private final ExecutorService dnsExecutor;

  ProviderEndpointPolicy() {
    dnsExecutor = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), runnable -> {
      var thread = new Thread(runnable, "provider-dns");
      thread.setDaemon(true);
      return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    resolver = host -> timedResolve(host, dnsExecutor);
  }

  ProviderEndpointPolicy(Resolver resolver) {
    this.resolver = resolver;
    this.dnsExecutor = null;
  }

  URI modelsEndpoint(String baseUrl) {
    var endpoint = modelsEndpointForConnection(baseUrl);
    try {
      resolvePublic(endpoint.getHost());
    } catch (UnknownHostException exception) {
      throw new IllegalArgumentException(exception.getMessage());
    }
    return endpoint;
  }

  URI modelsEndpointForConnection(String baseUrl) {
    final URI base;
    try {
      base = URI.create(baseUrl == null ? "" : baseUrl.trim());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("provider baseUrl is invalid");
    }
    if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null || base.getHost().isBlank()) {
      throw new IllegalArgumentException("provider baseUrl must use HTTPS");
    }
    if (base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
      throw new IllegalArgumentException("provider baseUrl contains unsupported components");
    }
    String host = IDN.toASCII(base.getHost()).toLowerCase(Locale.ROOT);
    String path = base.getPath() == null ? "" : base.getPath();
    while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
    if (!path.endsWith("/models")) path += "/models";
    try {
      return new URI("https", null, host, base.getPort(), path, null, null);
    } catch (Exception exception) {
      throw new IllegalArgumentException("provider baseUrl is invalid");
    }
  }

  DnsResolver connectionResolver() {
    return new DnsResolver() {
      @Override
      public InetAddress[] resolve(String host) throws UnknownHostException {
        return resolvePublic(host);
      }

      @Override
      public String resolveCanonicalHostname(String host) {
        return host;
      }
    };
  }

  InetAddress[] resolvePublic(String host) throws UnknownHostException {
    final InetAddress[] addresses;
    try {
      addresses = resolver.resolve(host);
    } catch (Exception exception) {
      var failure = new UnknownHostException("provider host cannot be resolved");
      failure.initCause(exception);
      throw failure;
    }
    if (addresses.length == 0) throw new UnknownHostException("provider host cannot be resolved");
    for (var address : addresses) {
      if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
          || address.isSiteLocalAddress() || address.isMulticastAddress() || isReserved(address.getAddress())) {
        throw new UnknownHostException("provider host resolves to a private or reserved address");
      }
    }
    return addresses.clone();
  }

  @PreDestroy
  void close() {
    if (dnsExecutor != null) dnsExecutor.shutdownNow();
  }

  private static InetAddress[] timedResolve(String host, ExecutorService executor) throws Exception {
    final Future<InetAddress[]> future;
    try {
      future = executor.submit(() -> InetAddress.getAllByName(host));
    } catch (RuntimeException exception) {
      throw new UnknownHostException("provider DNS capacity is exhausted");
    }
    try {
      return future.get(DNS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException exception) {
      future.cancel(true);
      throw new UnknownHostException("provider DNS lookup timed out");
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof Exception cause) throw cause;
      throw exception;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new UnknownHostException("provider DNS lookup interrupted");
    }
  }

  private static boolean isReserved(byte[] bytes) {
    if (bytes.length == 4) {
      int first = bytes[0] & 0xff;
      int second = bytes[1] & 0xff;
      int third = bytes[2] & 0xff;
      return first == 0 || first == 10 || first == 127 || first >= 224
          || (first == 100 && second >= 64 && second <= 127)
          || (first == 169 && second == 254)
          || (first == 172 && second >= 16 && second <= 31)
          || (first == 192 && second == 0 && (third == 0 || third == 2))
          || (first == 192 && second == 168)
          || (first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100)))
          || (first == 203 && second == 0 && third == 113);
    }
    if (bytes.length == 16) {
      int first = bytes[0] & 0xff;
      int second = bytes[1] & 0xff;
      if ((first & 0xfe) == 0xfc || (first == 0xfe && (second & 0xc0) == 0x80) || first == 0xff) return true;
      if (first == 0x20 && second == 0x01 && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8) return true;
      if (first == 0x20 && second == 0x02) return true;
      if (first == 0x20 && second == 0x01 && bytes[2] == 0 && bytes[3] == 0) return true;
      if (first == 0x00 && second == 0x64 && (bytes[2] & 0xff) == 0xff && (bytes[3] & 0xff) == 0x9b) return true;
      boolean mapped = true;
      for (int index = 0; index < 10; index++) mapped &= bytes[index] == 0;
      mapped &= (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
      if (mapped) return isReserved(java.util.Arrays.copyOfRange(bytes, 12, 16));
    }
    return false;
  }
}
