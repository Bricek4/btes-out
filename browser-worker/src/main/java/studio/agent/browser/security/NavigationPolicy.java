package studio.agent.browser.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class NavigationPolicy {
  @FunctionalInterface
  public interface AddressResolver {
    List<InetAddress> resolve(String host) throws UnknownHostException;
  }

  private final boolean localMode;
  private final Set<String> localAllowedHosts;
  private final Set<String> configuredOrigins;
  private final AddressResolver resolver;

  public NavigationPolicy(boolean localMode, List<String> localAllowedHosts,
      List<String> configuredOrigins, AddressResolver resolver) {
    this.localMode = localMode;
    this.localAllowedHosts = lowerCaseSet(localAllowedHosts);
    this.configuredOrigins = new HashSet<>();
    for (String origin : configuredOrigins) {
      this.configuredOrigins.add(origin(URI.create(origin)));
    }
    this.resolver = resolver;
  }

  public URI validate(URI target, URI sessionBase) {
    if (target == null || target.getScheme() == null
        || !(target.getScheme().equalsIgnoreCase("http") || target.getScheme().equalsIgnoreCase("https"))) {
      throw new NavigationRejectedException("SCHEME_NOT_ALLOWED", "only http and https are supported");
    }
    if (target.getUserInfo() != null || target.getHost() == null) {
      throw new NavigationRejectedException("INVALID_TARGET_URL", "URL must not contain credentials and must have a host");
    }
    String targetOrigin = origin(target);
    if (sessionBase != null && !targetOrigin.equals(origin(sessionBase)) && !configuredOrigins.contains(targetOrigin)) {
      throw new NavigationRejectedException("ORIGIN_NOT_ALLOWED", targetOrigin);
    }
    String host = target.getHost().toLowerCase(Locale.ROOT);
    try {
      List<InetAddress> addresses = resolver.resolve(host);
      if (addresses.isEmpty()) {
        throw new NavigationRejectedException("DNS_RESOLUTION_FAILED", host);
      }
      for (InetAddress address : addresses) {
        if (isPrivate(address) && !(localMode && localAllowedHosts.contains(host))) {
          throw new NavigationRejectedException("PRIVATE_ADDRESS_BLOCKED", host);
        }
      }
    } catch (UnknownHostException e) {
      throw new NavigationRejectedException("DNS_RESOLUTION_FAILED", host);
    }
    return target;
  }

  public static NavigationPolicy production(List<String> configuredOrigins) {
    return new NavigationPolicy(false, List.of(), configuredOrigins,
        host -> List.of(InetAddress.getAllByName(host)));
  }

  private static boolean isPrivate(InetAddress address) {
    if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
      return true;
    }
    byte[] bytes = address.getAddress();
    if (address instanceof Inet4Address) {
      int first = bytes[0] & 0xff;
      int second = bytes[1] & 0xff;
      return first == 0 || first == 10 || first == 127 || first >= 224
          || (first == 100 && second >= 64 && second <= 127)
          || (first == 169 && second == 254)
          || (first == 172 && second >= 16 && second <= 31)
          || (first == 192 && second == 168);
    }
    if (address instanceof Inet6Address) {
      int first = bytes[0] & 0xff;
      return (first & 0xfe) == 0xfc;
    }
    return true;
  }

  private static String origin(URI uri) {
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    int port = uri.getPort() >= 0 ? uri.getPort() : (scheme.equals("https") ? 443 : 80);
    return scheme + "://" + host + ":" + port;
  }

  private static Set<String> lowerCaseSet(List<String> values) {
    var result = new HashSet<String>();
    for (String value : values) {
      result.add(value.toLowerCase(Locale.ROOT));
    }
    return result;
  }
}
