package com.fixcampus.config;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;
import com.fixcampus.security.CampusUserDetailsService;
import com.fixcampus.security.CampusLoginFailureHandler;
import com.fixcampus.security.RoleLoginSuccessHandler;
import jakarta.servlet.DispatcherType;
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
                                            PasswordEncoder passwords) throws Exception {
        var provider = new DaoAuthenticationProvider(userDetails);
        provider.setPasswordEncoder(passwords);
        http.authenticationProvider(provider)
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/login", "/register", "/css/**", "/js/**", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/register").anonymous()
                        .requestMatchers("/student/**").hasRole("STUDENT")
                        .requestMatchers("/worker/**").hasRole("WORKER")
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/logout").authenticated()
                        .anyRequest().denyAll())
                .formLogin(form -> form.loginPage("/login").loginProcessingUrl("/login")
                        .successHandler(new RoleLoginSuccessHandler()).failureHandler(new CampusLoginFailureHandler()))
                .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true).deleteCookies("JSESSIONID"))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.disable())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint("/login")));
        // CSRF remains enabled, including login, registration and POST logout.
        return http.build();
    }
}
