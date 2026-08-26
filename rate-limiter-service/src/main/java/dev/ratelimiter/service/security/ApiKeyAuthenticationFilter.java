package dev.ratelimiter.service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Enumeration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates an API key by comparing fixed-length SHA-256 digests in constant time. */
public final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

  public static final String HEADER_NAME = "X-API-Key";

  private final byte[] expectedDigest;

  public ApiKeyAuthenticationFilter(String expectedApiKey) {
    this.expectedDigest = digest(expectedApiKey);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    HeaderValue headerValue = singleHeader(request);
    byte[] candidateDigest = digest(headerValue.value());
    if (headerValue.present() && MessageDigest.isEqual(expectedDigest, candidateDigest)) {
      var authentication =
          UsernamePasswordAuthenticationToken.authenticated(
              "api-key-client", null, Collections.singleton(new SimpleGrantedAuthority("SERVICE")));
      SecurityContextHolder.getContext().setAuthentication(authentication);
    }
    filterChain.doFilter(request, response);
  }

  private static HeaderValue singleHeader(HttpServletRequest request) {
    Enumeration<String> values = request.getHeaders(HEADER_NAME);
    if (values == null || !values.hasMoreElements()) {
      return new HeaderValue(false, "");
    }
    String value = values.nextElement();
    if (values.hasMoreElements()) {
      return new HeaderValue(false, "");
    }
    return new HeaderValue(true, value);
  }

  private static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private record HeaderValue(boolean present, String value) {}
}
