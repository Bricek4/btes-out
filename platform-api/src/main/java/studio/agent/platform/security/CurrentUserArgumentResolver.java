package studio.agent.platform.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Makes owner checks explicit in controller signatures without trusting request-supplied user IDs. */
@Configuration
public class CurrentUserArgumentResolver implements WebMvcConfigurer, HandlerMethodArgumentResolver {
  @Override public void addArgumentResolvers(java.util.List<HandlerMethodArgumentResolver> resolvers) { resolvers.add(this); }
  @Override public boolean supportsParameter(MethodParameter parameter) { return parameter.getParameterType() == CurrentUser.class; }
  @Override public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container, NativeWebRequest request, WebDataBinderFactory factory) {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) return user;
    throw new org.springframework.security.access.AccessDeniedException("authentication required");
  }
}
