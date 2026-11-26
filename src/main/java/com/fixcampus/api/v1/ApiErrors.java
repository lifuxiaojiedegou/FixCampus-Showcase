package com.fixcampus.api.v1;

import com.fixcampus.exception.NotificationNotFoundException;
import com.fixcampus.exception.OrderNotFoundException;
import com.fixcampus.service.AttachmentImages;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice(basePackageClasses = StudentApi.class)
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class ApiErrors {
    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<?> problem(ApiProblem p) {
        return ResponseEntity.status(p.status()).header("Cache-Control", "no-store")
                .header("Content-Type", "application/problem+json").body(p.body());
    }
    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<?> missing() { return problem(new ApiProblem(404, "ORDER_NOT_FOUND", "工单不存在或无权访问")); }
    @ExceptionHandler(NotificationNotFoundException.class)
    public ResponseEntity<?> missingNotification() {
        return problem(new ApiProblem(404, "NOTIFICATION_NOT_FOUND", "通知不存在或无权访问"));
    }
    @ExceptionHandler(com.fixcampus.exception.OrderConflictException.class)
    public ResponseEntity<?> conflict() { return problem(new ApiProblem(409, "ORDER_CONFLICT", "工单状态已变化，请刷新后重试")); }
    @ExceptionHandler(com.fixcampus.exception.InvalidWorkerException.class)
    public ResponseEntity<?> worker() { return problem(new ApiProblem(400, "INVALID_WORKER", "维修人员不存在或不可分配")); }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> denied() { return problem(new ApiProblem(403, "ROLE_FORBIDDEN", "当前身份无权执行此操作")); }
    @ExceptionHandler({DataAccessException.class, TransactionException.class, LockTimeoutException.class,
            PessimisticLockException.class, jakarta.persistence.QueryTimeoutException.class})
    public ResponseEntity<?> unavailable() { return problem(new ApiProblem(503, "TEMPORARILY_UNAVAILABLE", "服务暂不可用，请核查原提交结果")); }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            jakarta.validation.ConstraintViolationException.class})
    public ResponseEntity<?> invalid() { return problem(new ApiProblem(400, "VALIDATION_ERROR", "请求参数无效")); }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<?> multipart(MultipartException e) { return problem(multipartProblem(e)); }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<?> missingPart() { return problem(AttachmentImages.invalid()); }

    static ApiProblem multipartProblem(MultipartException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof MaxUploadSizeExceededException
                    || cause.getClass().getSimpleName().equals("FileSizeLimitExceededException")
                    || cause.getClass().getSimpleName().equals("SizeLimitExceededException")) {
                return AttachmentImages.tooLarge();
            }
        }
        return AttachmentImages.invalid();
    }
}
