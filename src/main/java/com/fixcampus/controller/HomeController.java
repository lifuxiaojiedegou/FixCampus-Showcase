package com.fixcampus.controller;

import org.springframework.stereotype.Controller;
import com.fixcampus.security.CampusPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {
    private final boolean redirectToApp;

    public HomeController(@Value("${fixcampus.web.root-redirect-to-app:false}") boolean redirectToApp) {
        this.redirectToApp = redirectToApp;
    }

    @GetMapping("/")
    public String home(@AuthenticationPrincipal CampusPrincipal principal) {
        if (redirectToApp) return "redirect:/app/";
        return "redirect:" + (principal == null ? "/login" : principal.getRole().getHomePath());
    }
}
