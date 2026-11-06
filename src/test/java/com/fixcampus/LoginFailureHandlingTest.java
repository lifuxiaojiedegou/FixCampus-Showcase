package com.fixcampus;

import com.fixcampus.security.CampusLoginFailureHandler;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginFailureHandlingTest {
    @Test
    void databaseFailureDuringAuthenticationProduces503WithoutLeakingDetails() throws Exception {
        var details = mock(UserDetailsService.class);
        when(details.loadUserByUsername("outage_test"))
                .thenThrow(new DataAccessResourceFailureException("private-database-details"));
        var provider = new DaoAuthenticationProvider(details);
        provider.setPasswordEncoder(Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8());
        Throwable failure = catchThrowable(() -> provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("outage_test", "Synthetic_Test_123")));
        assertThat(failure).isInstanceOf(AuthenticationServiceException.class);
        var response = new MockHttpServletResponse();
        new CampusLoginFailureHandler().onAuthenticationFailure(new MockHttpServletRequest(), response,
                (AuthenticationServiceException) failure);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).doesNotContain("private-database-details", "Synthetic_Test_123");
        assertThat(response.getErrorMessage()).isNull();
    }

    @Test
    void invalidCredentialsUseTheUniformFailureRedirect() throws Exception {
        var response = new MockHttpServletResponse();
        new CampusLoginFailureHandler().onAuthenticationFailure(new MockHttpServletRequest(), response,
                new BadCredentialsException("Bad credentials"));
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
    }
}
