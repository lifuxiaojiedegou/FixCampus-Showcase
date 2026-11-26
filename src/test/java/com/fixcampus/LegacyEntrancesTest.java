package com.fixcampus;

import com.fixcampus.config.SecurityConfig;
import com.fixcampus.controller.AuthController;
import com.fixcampus.controller.HomeController;
import com.fixcampus.security.CampusUserDetailsService;
import com.fixcampus.security.SessionLifecycle;
import com.fixcampus.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {HomeController.class, AuthController.class},
        excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@ActiveProfiles("legacy-ssr")
@Import({SecurityConfig.class, SessionLifecycle.class})
class LegacyEntrancesTest {
    @Autowired MockMvc mvc;
    @MockitoBean CampusUserDetailsService accounts;
    @MockitoBean UserService users;

    @Test
    void legacyRootAndRegistrationRemainAvailableByDefault() throws Exception {
        mvc.perform(get("/")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(view().name("auth/login"))
                .andExpect(content().string(containsString("href=\"/register\"")));
        mvc.perform(get("/register")).andExpect(status().isOk()).andExpect(view().name("auth/register"));
    }
}
