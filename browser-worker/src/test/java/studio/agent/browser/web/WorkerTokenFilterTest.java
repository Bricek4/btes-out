package studio.agent.browser.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WorkerTokenFilterTest {
  @Test
  void rejectsMissingOrWrongBearerTokenWithoutReachingController() throws Exception {
    var filter = new WorkerTokenFilter("expected-secret");
    var request = new MockHttpServletRequest("POST", "/internal/sessions");
    request.addHeader("Authorization", "Bearer wrong-secret");
    var response = new MockHttpServletResponse();
    var chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).contains("UNAUTHORIZED").doesNotContain("expected-secret");
    verifyNoInteractions(chain);
  }
}
