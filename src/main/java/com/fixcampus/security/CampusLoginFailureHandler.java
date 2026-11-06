package com.fixcampus.security;

import java.io.IOException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

public class CampusLoginFailureHandler implements AuthenticationFailureHandler {
    private final AuthenticationFailureHandler invalidCredentials = new SimpleUrlAuthenticationFailureHandler("/login?error");

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        if (exception instanceof AuthenticationServiceException) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        } else {
            invalidCredentials.onAuthenticationFailure(request, response, exception);
        }
    }
}
