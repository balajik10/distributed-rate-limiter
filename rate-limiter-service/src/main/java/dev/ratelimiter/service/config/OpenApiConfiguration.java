package dev.ratelimiter.service.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

  @Bean
  OpenAPI rateLimiterOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Distributed Rate Limiter API")
                .version("v1")
                .description(
                    "Non-idempotent rate-limit checks backed by trusted server-defined policies."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "apiKey",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-API-Key")));
  }
}
