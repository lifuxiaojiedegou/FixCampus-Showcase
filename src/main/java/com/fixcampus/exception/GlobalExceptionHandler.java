package com.fixcampus.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(OrderNotFoundException.class)
    public ModelAndView orderNotFound() {
        return businessError(HttpStatus.NOT_FOUND, "工单不存在或无权访问");
    }

    @ExceptionHandler({OrderConflictException.class, PessimisticLockingFailureException.class, QueryTimeoutException.class})
    public ModelAndView orderConflict() {
        return businessError(HttpStatus.CONFLICT, "工单状态已变化或正在被处理，请刷新后重试。");
    }

    private static ModelAndView businessError(HttpStatus status, String message) {
        var view = new ModelAndView("error");
        view.setStatus(status);
        view.addObject("status", status.value());
        view.addObject("message", message);
        return view;
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ModelAndView databaseUnavailable(RuntimeException exception) {
        // Do not log request bodies or SQL exception messages containing submitted data.
        log.error("Database request failed ({}).", exception.getClass().getSimpleName());
        var view = new ModelAndView("error");
        view.setStatus(HttpStatus.SERVICE_UNAVAILABLE);
        view.addObject("status", 503);
        return view;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ModelAndView accessDenied() {
        var view = new ModelAndView("error");
        view.setStatus(HttpStatus.FORBIDDEN);
        view.addObject("status", 403);
        return view;
    }
}
