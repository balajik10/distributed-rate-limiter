package dev.ratelimiter.service.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestIdFilter extends OncePerRequestFilter {

  public static final String HEADER_NAME = "X-Request-Id";
  public static final String REQUEST_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
  public static final int MAX_REQUEST_BODY_BYTES = 4_096;

  private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

  private final ServletProblemWriter problemWriter;

  public RequestIdFilter(ServletProblemWriter problemWriter) {
    this.problemWriter = problemWriter;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    RequestIdHeader suppliedRequestId = requestIdHeader(request);
    boolean valid =
        !suppliedRequestId.present()
            || (suppliedRequestId.single()
                && VALID_REQUEST_ID.matcher(suppliedRequestId.value()).matches());
    String requestId =
        valid && suppliedRequestId.present() ? suppliedRequestId.value() : newRequestId();

    request.setAttribute(REQUEST_ATTRIBUTE, requestId);
    response.setHeader(HEADER_NAME, requestId);
    MDC.put("requestId", requestId);
    try {
      if (!valid) {
        problemWriter.write(
            request,
            response,
            HttpStatus.BAD_REQUEST,
            "Invalid request ID",
            "X-Request-Id must contain 1 to 64 letters, digits, '.', '_', ':', or '-' characters",
            requestId);
        return;
      }
      if (request.getContentLengthLong() > MAX_REQUEST_BODY_BYTES) {
        problemWriter.write(
            request,
            response,
            HttpStatus.PAYLOAD_TOO_LARGE,
            "Request body too large",
            "The request body exceeds the 4096-byte limit",
            requestId);
        return;
      }
      if (mayContainBody(request)) {
        byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
        if (body.length > MAX_REQUEST_BODY_BYTES) {
          problemWriter.write(
              request,
              response,
              HttpStatus.PAYLOAD_TOO_LARGE,
              "Request body too large",
              "The request body exceeds the 4096-byte limit",
              requestId);
          return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
      } else {
        filterChain.doFilter(request, response);
      }
    } finally {
      MDC.remove("requestId");
    }
  }

  public static String requestId(HttpServletRequest request) {
    Object value = request.getAttribute(REQUEST_ATTRIBUTE);
    return value instanceof String requestId ? requestId : newRequestId();
  }

  private static String newRequestId() {
    return UUID.randomUUID().toString();
  }

  private static boolean mayContainBody(HttpServletRequest request) {
    return switch (request.getMethod()) {
      case "POST", "PUT", "PATCH" -> true;
      default -> false;
    };
  }

  private static RequestIdHeader requestIdHeader(HttpServletRequest request) {
    Enumeration<String> values = request.getHeaders(HEADER_NAME);
    if (values == null || !values.hasMoreElements()) {
      return new RequestIdHeader(false, true, "");
    }
    String value = values.nextElement();
    return new RequestIdHeader(true, !values.hasMoreElements(), value == null ? "" : value);
  }

  private record RequestIdHeader(boolean present, boolean single, String value) {}

  private static final class CachedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    private CachedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
      return new ByteArrayServletInputStream(body);
    }

    @Override
    public BufferedReader getReader() {
      Charset charset =
          getCharacterEncoding() == null
              ? StandardCharsets.UTF_8
              : Charset.forName(getCharacterEncoding());
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }
  }

  private static final class ByteArrayServletInputStream extends ServletInputStream {
    private final ByteArrayInputStream delegate;

    private ByteArrayServletInputStream(byte[] body) {
      this.delegate = new ByteArrayInputStream(body);
    }

    @Override
    public int read() {
      return delegate.read();
    }

    @Override
    public int read(byte[] bytes, int offset, int length) {
      return delegate.read(bytes, offset, length);
    }

    @Override
    public boolean isFinished() {
      return delegate.available() == 0;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
      if (readListener == null) {
        throw new IllegalArgumentException("readListener must not be null");
      }
      try {
        if (isFinished()) {
          readListener.onAllDataRead();
        } else {
          readListener.onDataAvailable();
        }
      } catch (IOException exception) {
        readListener.onError(exception);
      }
    }
  }
}
