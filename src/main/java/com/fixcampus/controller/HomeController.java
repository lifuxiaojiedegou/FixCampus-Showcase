package com.fixcampus.controller;

import org.springframework.stereotype.Controller;
import com.fixcampus.security.CampusPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home(@AuthenticationPrincipal CampusPrincipal principal) {
        return "redirect:" + (principal == null ? "/login" : principal.getRole().getHomePath());
    }
}
