package com.fixcampus.api.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/** Routing failures occur before a controller exists, so scoped advice cannot handle them. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiRoutingErrors implements HandlerExceptionResolver {
    private final ObjectMapper mapper;
    public ApiRoutingErrors(ObjectMapper mapper) { this.mapper = mapper; }
    @Override public ModelAndView resolveException(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        if (!req.getRequestURI().startsWith(req.getContextPath() + "/api/v1/")) return null;
        ApiProblem problem;
        if (handler == null && ex instanceof MultipartException multipart) problem = ApiErrors.multipartProblem(multipart);
        else if (ex instanceof org.springframework.web.HttpMediaTypeNotSupportedException)
            problem = new ApiProblem(415, "VALIDATION_ERROR", "请求方法或媒体类型不受支持");
        else if (ex instanceof org.springframework.web.HttpRequestMethodNotSupportedException)
            problem = new ApiProblem(405, "VALIDATION_ERROR", "请求方法或媒体类型不受支持");
        else return null;
        try {
            ApiProblem.write(res, mapper, problem.status(), problem.code(), problem.getMessage());
            return new ModelAndView();
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
}
