package com.fixcampus;

import com.fixcampus.controller.AdminController;
import com.fixcampus.exception.GlobalExceptionHandler;
import com.fixcampus.service.RepairOrderService;
import com.fixcampus.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminFailureHandlingTest {
    private MockMvc mvc(RepairOrderService orders) {
        return MockMvcBuilders.standaloneSetup(new AdminController(orders, mock(UserService.class)))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void assignmentDatabaseAndCommitFailuresReturn503WithoutSuccessRedirect() throws Exception {
        for (RuntimeException failure : new RuntimeException[]{new DataAccessResourceFailureException("private-db-details"), new TransactionSystemException("private-db-details")}) {
            var orders = mock(RepairOrderService.class);
            doThrow(failure).when(orders).assign(any(), eq(1L), eq(2L));
            mvc(orders).perform(post("/admin/orders/1/assign").param("workerId", "2"))
                    .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("Location"))
                    .andExpect(content().string(not(containsString("private-db-details"))));
        }
    }

    @Test
    void assignmentLockFailureReturns409WithoutSqlOrRedirect() throws Exception {
        var orders = mock(RepairOrderService.class);
        doThrow(new CannotAcquireLockException("private-lock-sql")).when(orders).assign(any(), eq(1L), eq(2L));
        mvc(orders).perform(post("/admin/orders/1/assign").param("workerId", "2"))
                .andExpect(status().isConflict()).andExpect(header().doesNotExist("Location"))
                .andExpect(content().string(not(containsString("private-lock-sql"))));
    }

    @Test
    void adminListDatabaseFailureReturns503WithoutSql() throws Exception {
        var orders = mock(RepairOrderService.class);
        when(orders.searchForAdmin(any(), any())).thenThrow(new DataAccessResourceFailureException("private-query-sql"));
        mvc(orders).perform(get("/admin/orders")).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(not(containsString("private-query-sql"))));
    }
}
