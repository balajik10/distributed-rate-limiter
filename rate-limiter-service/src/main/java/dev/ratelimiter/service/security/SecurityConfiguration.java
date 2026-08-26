package dev.ratelimiter.service.security;

import dev.ratelimiter.service.config.ServiceSecurityProperties;
import dev.ratelimiter.service.web.RequestIdFilter;
import dev.ratelimiter.service.web.ServletProblemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

  private static final String[] PUBLIC_OPERATIONS_ENDPOINTS = {
    "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/info"
  };

  private static final String[] PROTECTED_ENDPOINTS = {
    "/api/v1/**", "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/actuator/prometheus"
  };

  @Bean
  SecurityFilterChain serviceSecurityFilterChain(
      HttpSecurity http, ServiceSecurityProperties properties, ServletProblemWriter problemWriter)
      throws Exception {
    http.cors(AbstractHttpConfigurer::disable)
        .csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            problemWriter.write(
                                request,
                                response,
                                HttpStatus.UNAUTHORIZED,
                                "Unauthorized",
                                "A valid X-API-Key header is required",
                                RequestIdFilter.requestId(request)))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            problemWriter.write(
                                request,
                                response,
                                HttpStatus.FORBIDDEN,
                                "Forbidden",
                                "This endpoint is not available",
                                RequestIdFilter.requestId(request))));

    if (properties.enabled()) {
      if (!StringUtils.hasText(properties.apiKey())) {
        throw new IllegalStateException("An API key is required when authentication is enabled");
      }
      http.addFilterBefore(
          new ApiKeyAuthenticationFilter(properties.apiKey()), AnonymousAuthenticationFilter.class);
      http.authorizeHttpRequests(
          requests ->
              requests
                  .requestMatchers(PUBLIC_OPERATIONS_ENDPOINTS)
                  .permitAll()
                  .requestMatchers(PROTECTED_ENDPOINTS)
                  .authenticated()
                  .anyRequest()
                  .denyAll());
    } else {
      http.authorizeHttpRequests(
          requests ->
              requests
                  .requestMatchers(PUBLIC_OPERATIONS_ENDPOINTS)
                  .permitAll()
                  .requestMatchers(PROTECTED_ENDPOINTS)
                  .permitAll()
                  .anyRequest()
                  .denyAll());
    }
    return http.build();
  }
}
