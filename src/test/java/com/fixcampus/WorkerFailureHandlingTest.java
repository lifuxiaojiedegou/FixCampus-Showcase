package com.fixcampus;

import com.fixcampus.controller.WorkerController;
import com.fixcampus.exception.GlobalExceptionHandler;
import com.fixcampus.service.RepairOrderService;
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

class WorkerFailureHandlingTest {
    private MockMvc mvc(RepairOrderService orders) {
        return MockMvcBuilders.standaloneSetup(new WorkerController(orders))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void databaseAndCommitFailuresNeverRedirectToSuccess() throws Exception {
        for (RuntimeException failure : new RuntimeException[]{new DataAccessResourceFailureException("private-db-details"),
                new TransactionSystemException("private-db-details")}) {
            var orders = mock(RepairOrderService.class);
            doThrow(failure).when(orders).start(any(), eq(1L));
            doThrow(failure).when(orders).complete(any(), eq(1L), any());
            for (String action : new String[]{"start", "complete"}) {
                mvc(orders).perform(post("/worker/orders/1/" + action).param("repairResult", "处理说明"))
                        .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("Location"))
                        .andExpect(flash().attributeCount(0)).andExpect(content().string(not(containsString("private-db-details"))));
            }
        }
    }

    @Test
    void lockFailuresAre409WithoutSuccessRedirectOrPrivateDetails() throws Exception {
        var orders = mock(RepairOrderService.class);
        doThrow(new CannotAcquireLockException("private-lock-sql")).when(orders).start(any(), eq(1L));
        doThrow(new CannotAcquireLockException("private-lock-sql")).when(orders).complete(any(), eq(1L), any());
        for (String action : new String[]{"start", "complete"}) {
            mvc(orders).perform(post("/worker/orders/1/" + action)).andExpect(status().isConflict())
                    .andExpect(header().doesNotExist("Location")).andExpect(flash().attributeCount(0))
                    .andExpect(content().string(not(containsString("private-lock-sql"))));
        }
    }

    @Test
    void readsFailWith503WithoutPrivateDetails() throws Exception {
        var orders = mock(RepairOrderService.class);
        when(orders.searchForWorker(any(), any())).thenThrow(new DataAccessResourceFailureException("private-query-sql"));
        when(orders.getForWorker(any(), eq(1L))).thenThrow(new DataAccessResourceFailureException("private-query-sql"));
        for (String path : new String[]{"/worker/orders", "/worker/orders/1"}) {
            mvc(orders).perform(get(path)).andExpect(status().isServiceUnavailable())
                    .andExpect(content().string(not(containsString("private-query-sql"))));
        }
    }
}
