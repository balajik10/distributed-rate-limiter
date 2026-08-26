# syntax=docker/dockerfile:1.7
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace
COPY . .
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN apk add --no-cache curl \
    && addgroup -S -g 10001 ratelimiter \
    && adduser -S -D -H -u 10001 -G ratelimiter ratelimiter
WORKDIR /app
COPY --from=builder --chown=10001:10001 /workspace/rate-limiter-service/target/rate-limiter-service.jar /app/rate-limiter-service.jar
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/urandom"
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=6 \
  CMD ["curl", "--fail", "--silent", "--show-error", "http://127.0.0.1:8080/actuator/health/liveness"]
ENTRYPOINT ["java", "-jar", "/app/rate-limiter-service.jar"]
