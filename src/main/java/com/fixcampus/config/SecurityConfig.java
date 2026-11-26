package com.fixcampus.config;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;
import com.fixcampus.security.CampusUserDetailsService;
import com.fixcampus.security.CampusLoginFailureHandler;
import com.fixcampus.security.RoleLoginSuccessHandler;
import com.fixcampus.security.SessionLifecycle;
import com.fixcampus.security.SsrSessionLifecycleFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    Clock applicationClock() { return Clock.system(ZoneId.of("Asia/Hong_Kong")); }

    @Bean
    PasswordEncoder passwordEncoder() {
        String id = "pbkdf2@SpringSecurity_v5_8";
        return new DelegatingPasswordEncoder(id, Map.of(id, Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CampusUserDetailsService userDetails,
            PasswordEncoder passwords, SessionLifecycle lifecycle,
            @Value("${fixcampus.auth.registration-enabled:true}") boolean registrationEnabled) throws Exception {
        var provider = new DaoAuthenticationProvider(userDetails);
        provider.setPasswordEncoder(passwords);
        var login = new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint("/login");
        http.authenticationProvider(provider)
                .authorizeHttpRequests(authorize -> {
                    authorize.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                    if (registrationEnabled) {
                        authorize.requestMatchers(HttpMethod.GET, "/register").permitAll()
                                .requestMatchers(HttpMethod.POST, "/register").anonymous();
                    } else {
                        authorize.requestMatchers("/register").denyAll();
                    }
                    authorize.requestMatchers(HttpMethod.GET, "/", "/login", "/css/**", "/js/**", "/error").permitAll()
                            .requestMatchers(HttpMethod.POST, "/login").permitAll()
                            .requestMatchers("/student/**").hasRole("STUDENT")
                            .requestMatchers("/worker/**").hasRole("WORKER")
                            .requestMatchers("/admin/**").hasRole("ADMIN")
                            .requestMatchers("/logout").authenticated()
                            .anyRequest().denyAll();
                })
                .formLogin(form -> form.loginPage("/login").loginProcessingUrl("/login")
                        .successHandler(new RoleLoginSuccessHandler(lifecycle)).failureHandler(new CampusLoginFailureHandler()))
                .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true).deleteCookies("JSESSIONID"))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.disable())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(login))
                .addFilterBefore(new SsrSessionLifecycleFilter(lifecycle, login, registrationEnabled),
                        org.springframework.security.web.csrf.CsrfFilter.class);
        // CSRF remains enabled, including login, registration and POST logout.
        return http.build();
    }
}
