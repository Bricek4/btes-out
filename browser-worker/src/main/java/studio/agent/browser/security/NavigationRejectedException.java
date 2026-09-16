package studio.agent.browser.security;

public final class NavigationRejectedException extends RuntimeException {
  private final String code;

  public NavigationRejectedException(String code, String detail) {
    super(code + ": " + detail);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
