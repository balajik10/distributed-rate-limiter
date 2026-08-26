package dev.ratelimiter.service.api;

import dev.ratelimiter.core.PermitsExceedPolicyLimitException;
import dev.ratelimiter.core.UnknownPolicyException;
import dev.ratelimiter.service.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> invalidBody(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    List<FieldViolation> violations =
        exception.getBindingResult().getFieldErrors().stream()
            .map(
                error ->
                    new FieldViolation(error.getField(), safeMessage(error.getDefaultMessage())))
            .toList();
    ProblemDetail problem =
        problem(request, HttpStatus.BAD_REQUEST, "Invalid request", "Request validation failed");
    problem.setProperty("errors", violations);
    return response(problem);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemDetail> malformedJson(
      HttpMessageNotReadableException exception, HttpServletRequest request) {
    return response(
        problem(
            request,
            HttpStatus.BAD_REQUEST,
            "Malformed JSON",
            "The request body is not valid for this endpoint"));
  }

  @ExceptionHandler(PermitsExceedPolicyLimitException.class)
  ResponseEntity<ProblemDetail> invalidPermits(
      PermitsExceedPolicyLimitException exception, HttpServletRequest request) {
    return response(
        problem(
            request,
            HttpStatus.BAD_REQUEST,
            "Invalid permits",
            "Requested permits are not valid for the selected policy"));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ProblemDetail> constraintViolation(
      ConstraintViolationException exception, HttpServletRequest request) {
    return response(
        problem(request, HttpStatus.BAD_REQUEST, "Invalid request", "Request validation failed"));
  }

  @ExceptionHandler(UnknownPolicyException.class)
  ResponseEntity<ProblemDetail> unknownPolicy(
      UnknownPolicyException exception, HttpServletRequest request) {
    return response(
        problem(
            request,
            HttpStatus.NOT_FOUND,
            "Unknown policy",
            "The requested rate-limit policy does not exist"));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ProblemDetail> noResource(
      NoResourceFoundException exception, HttpServletRequest request) {
    return response(
        problem(
            request, HttpStatus.NOT_FOUND, "Not found", "The requested resource does not exist"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> internalError(Exception exception, HttpServletRequest request) {
    LOGGER.error(
        "request_failed requestId={} errorCategory=INTERNAL", RequestIdFilter.requestId(request));
    return response(
        problem(
            request,
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal server error",
            "The request could not be completed"));
  }

  private static ProblemDetail problem(
      HttpServletRequest request, HttpStatus status, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("requestId", RequestIdFilter.requestId(request));
    return problem;
  }

  private static ResponseEntity<ProblemDetail> response(ProblemDetail problem) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    headers.setCacheControl("no-store");
    return new ResponseEntity<>(problem, headers, HttpStatus.valueOf(problem.getStatus()));
  }

  private static String safeMessage(String message) {
    return message == null ? "is invalid" : message;
  }

  private record FieldViolation(String field, String message) {}
}
