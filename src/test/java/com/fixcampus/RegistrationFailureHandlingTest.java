package com.fixcampus;

import com.fixcampus.controller.AuthController;
import com.fixcampus.exception.GlobalExceptionHandler;
import com.fixcampus.form.RegisterForm;
import com.fixcampus.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RegistrationFailureHandlingTest {
    @Test
    void databaseFailureReturns503WithoutExposingSqlOrSubmittedPasswords() throws Exception {
        var service = mock(UserService.class);
        doThrow(new DataAccessResourceFailureException("internal-database-details"))
                .when(service).register(any(RegisterForm.class));
        var mvc = MockMvcBuilders.standaloneSetup(new AuthController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/register").param("username", "failure_test").param("realName", "测试学生")
                        .param("password", "Synthetic_Test_123").param("confirmPassword", "Synthetic_Test_123"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(not(containsString("internal-database-details"))))
                .andExpect(content().string(not(containsString("Synthetic_Test_123"))));
    }
}
