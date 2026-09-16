package studio.agent.platform.product;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

final class ProviderEndpointPolicy {
  interface Resolver {
    InetAddress[] resolve(String host) throws Exception;
  }

  private final Resolver resolver;

  ProviderEndpointPolicy() {
    this(InetAddress::getAllByName);
  }

  ProviderEndpointPolicy(Resolver resolver) {
    this.resolver = resolver;
  }

  URI modelsEndpoint(String baseUrl) {
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
    validateResolution(host);
    String path = base.getPath() == null ? "" : base.getPath();
    while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
    if (!path.endsWith("/models")) path += "/models";
    try {
      return new URI("https", null, host, base.getPort(), path, null, null);
    } catch (Exception exception) {
      throw new IllegalArgumentException("provider baseUrl is invalid");
    }
  }

  private void validateResolution(String host) {
    final InetAddress[] addresses;
    try {
      addresses = resolver.resolve(host);
    } catch (Exception exception) {
      throw new IllegalArgumentException("provider host cannot be resolved");
    }
    if (addresses.length == 0) throw new IllegalArgumentException("provider host cannot be resolved");
    for (var address : addresses) {
      if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
          || address.isSiteLocalAddress() || address.isMulticastAddress() || isReserved(address.getAddress())) {
        throw new IllegalArgumentException("provider host resolves to a private address");
      }
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
      boolean mapped = true;
      for (int index = 0; index < 10; index++) mapped &= bytes[index] == 0;
      mapped &= (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
      if (mapped) return isReserved(java.util.Arrays.copyOfRange(bytes, 12, 16));
    }
    return false;
  }
}
