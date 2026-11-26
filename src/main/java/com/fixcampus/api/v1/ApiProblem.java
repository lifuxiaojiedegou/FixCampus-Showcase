package com.fixcampus.api.v1;

import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

public class ApiProblem extends RuntimeException {
    private final int status;
    private final String code;
    public ApiProblem(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
    public Map<String, Object> body() {
        String requestId = MDC.get(ApiRequestIdFilter.MDC_KEY);
        String title = status == HttpStatus.TOO_MANY_REQUESTS.value()
                ? HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase() : getMessage();
        return Map.of("type", "about:blank", "title", title, "status", status,
                "code", code, "message", getMessage(), "requestId", requestId != null ? requestId : UUID.randomUUID().toString());
    }
    public static void write(HttpServletResponse response, ObjectMapper mapper, int status, String code, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getWriter(), new ApiProblem(status, code, message).body());
    }
}
