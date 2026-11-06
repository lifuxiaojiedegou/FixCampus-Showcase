package com.fixcampus.controller;

import com.fixcampus.exception.DuplicateUsernameException;
import com.fixcampus.form.RegisterForm;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class AuthController {
    private final UserService users;

    public AuthController(UserService users) { this.users = users; }

    @InitBinder("registerForm")
    void registrationFields(WebDataBinder binder) {
        binder.setAllowedFields("username", "password", "confirmPassword", "realName", "phone");
    }

    @GetMapping("/login")
    public String login(@AuthenticationPrincipal CampusPrincipal principal) {
        return principal == null ? "auth/login" : "redirect:" + principal.getRole().getHomePath();
    }

    @GetMapping("/register")
    public String register(@AuthenticationPrincipal CampusPrincipal principal, Model model) {
        if (principal != null) { return "redirect:" + principal.getRole().getHomePath(); }
        model.addAttribute("registerForm", new RegisterForm());
        return "auth/register";
    }

    @PostMapping("/register")
    public ModelAndView register(@Valid @ModelAttribute("registerForm") RegisterForm form,
                           BindingResult errors, HttpServletResponse response) {
        if (!errors.hasErrors()) {
            try {
                users.register(form);
                var redirect = new RedirectView("/login?registered", true);
                redirect.setStatusCode(HttpStatus.SEE_OTHER);
                return new ModelAndView(redirect);
            } catch (DuplicateUsernameException exception) {
                errors.rejectValue("username", "duplicate", exception.getMessage());
            }
        }
        form.clearPasswords();
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return new ModelAndView("auth/register");
    }

    @GetMapping("/logout")
    public String getLogout(Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.METHOD_NOT_ALLOWED.value());
        response.setHeader("Allow", "POST");
        model.addAttribute("status", 405);
        return "error";
    }
}
