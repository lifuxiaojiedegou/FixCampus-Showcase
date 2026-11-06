package com.fixcampus;

import com.fixcampus.controller.StudentController;
import com.fixcampus.exception.GlobalExceptionHandler;
import com.fixcampus.form.RepairOrderForm;
import com.fixcampus.service.RepairOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.transaction.TransactionSystemException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderFailureHandlingTest {
    @Test
    void confirmationDatabaseAndCommitFailuresReturn503WithoutSuccessFlash() throws Exception {
        for (RuntimeException failure : new RuntimeException[]{new DataAccessResourceFailureException("private-db-details"),
                new TransactionSystemException("private-db-details")}) {
            var service = mock(RepairOrderService.class);
            doThrow(failure).when(service).confirm(any(), eq(1L));
            var mvc = MockMvcBuilders.standaloneSetup(new StudentController(service))
                    .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            mvc.perform(post("/student/orders/1/confirm")).andExpect(status().isServiceUnavailable())
                    .andExpect(header().doesNotExist("Location")).andExpect(flash().attributeCount(0))
                    .andExpect(content().string(not(containsString("private-db-details"))));
        }
    }

    @Test
    void confirmationLockFailureReturns409WithoutSuccessFlash() throws Exception {
        var service = mock(RepairOrderService.class);
        doThrow(new CannotAcquireLockException("private-lock-sql")).when(service).confirm(any(), eq(1L));
        var mvc = MockMvcBuilders.standaloneSetup(new StudentController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/student/orders/1/confirm")).andExpect(status().isConflict())
                .andExpect(header().doesNotExist("Location")).andExpect(flash().attributeCount(0))
                .andExpect(content().string(not(containsString("private-lock-sql"))));
    }

    @Test
    void databaseAndCommitFailuresReturn503AndNeverRedirectToSuccess() throws Exception {
        for (RuntimeException failure : new RuntimeException[]{new DataAccessResourceFailureException("private-db-details"), new TransactionSystemException("private-db-details")}) {
            var service = mock(RepairOrderService.class);
            when(service.createForStudent(any(), any(RepairOrderForm.class))).thenThrow(failure);
            var mvc = MockMvcBuilders.standaloneSetup(new StudentController(service))
                    .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            mvc.perform(post("/student/orders").param("location", "测试地点").param("category", "OTHER").param("description", "故障"))
                    .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("Location"))
                    .andExpect(content().string(not(containsString("private-db-details"))));
        }
    }

    @Test
    void lockConflictsReturn409WithoutSqlOrSuccessRedirect() throws Exception {
        var service = mock(RepairOrderService.class);
        doThrow(new CannotAcquireLockException("private-lock-sql")).when(service).cancel(any(), eq(1L));
        var mvc = MockMvcBuilders.standaloneSetup(new StudentController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/student/orders/1/cancel")).andExpect(status().isConflict())
                .andExpect(model().attribute("status", 409)).andExpect(header().doesNotExist("Location"))
                .andExpect(content().string(not(containsString("private-lock-sql"))));
    }
}
