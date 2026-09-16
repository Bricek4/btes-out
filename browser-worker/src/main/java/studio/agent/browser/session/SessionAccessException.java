package studio.agent.browser.session;

public final class SessionAccessException extends RuntimeException {
  private final String code;

  public SessionAccessException(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
