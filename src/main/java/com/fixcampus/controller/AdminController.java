package com.fixcampus.controller;

import java.util.List;
import com.fixcampus.entity.RepairStatus;
import com.fixcampus.exception.InvalidWorkerException;
import com.fixcampus.form.AssignWorkerForm;
import com.fixcampus.form.OrderFilterForm;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.service.RepairOrderService;
import com.fixcampus.service.UserService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class AdminController {
    private final RepairOrderService orders;
    private final UserService users;

    public AdminController(RepairOrderService orders, UserService users) { this.orders = orders; this.users = users; }

    @InitBinder("assignWorkerForm")
    void editableFields(WebDataBinder binder) { binder.setAllowedFields("workerId"); }

    @InitBinder("orderFilter")
    void filterFields(WebDataBinder binder) { binder.setAllowedFields("status", "q"); }

    @ModelAttribute("currentUser")
    CampusPrincipal currentUser(@AuthenticationPrincipal CampusPrincipal actor) { return actor; }

    @ModelAttribute("statuses")
    RepairStatus[] statuses() { return RepairStatus.values(); }

    @GetMapping("/admin/orders")
    public ModelAndView list(@AuthenticationPrincipal CampusPrincipal actor,
                             @Valid @ModelAttribute("orderFilter") OrderFilterForm filter, BindingResult errors, Model model) {
        model.addAttribute("selectedStatus", filter.isStatusValid() ? filter.statusValue() : null);
        model.addAttribute("orders", errors.hasErrors() ? List.of() : orders.searchForAdmin(actor, filter));
        model.addAttribute("pendingCount", orders.countAwaitingAssignment(actor));
        model.addAttribute("hasFilters", filter.hasFilters());
        model.addAttribute("filterError", errors.hasErrors() ? errors.getAllErrors().getFirst().getDefaultMessage() : null);
        return new ModelAndView("admin/orders", errors.hasErrors() ? HttpStatus.BAD_REQUEST : HttpStatus.OK);
    }

    @GetMapping("/admin/orders/{id}")
    public String detail(@AuthenticationPrincipal CampusPrincipal actor, @PathVariable Long id, Model model) {
        model.addAttribute("assignWorkerForm", new AssignWorkerForm());
        populateDetail(actor, id, model);
        return "admin/order-detail";
    }

    @PostMapping("/admin/orders/{id}/assign")
    public ModelAndView assign(@AuthenticationPrincipal CampusPrincipal actor, @PathVariable Long id,
                              @Valid @ModelAttribute("assignWorkerForm") AssignWorkerForm form,
                              BindingResult errors, Model model, RedirectAttributes flash) {
        if (!errors.hasErrors()) {
            try {
                orders.assign(actor, id, form.getWorkerId());
                flash.addFlashAttribute("successMessage", "派单成功，工单已进入待维修状态。");
                var redirect = new RedirectView("/admin/orders/" + id, true);
                redirect.setStatusCode(HttpStatus.SEE_OTHER);
                return new ModelAndView(redirect);
            } catch (InvalidWorkerException exception) {
                errors.rejectValue("workerId", "invalid", exception.getMessage());
            }
        }
        populateDetail(actor, id, model);
        return new ModelAndView("admin/order-detail", HttpStatus.BAD_REQUEST);
    }

    private void populateDetail(CampusPrincipal actor, Long id, Model model) {
        var detail = orders.getForAdmin(actor, id);
        model.addAttribute("order", detail);
        model.addAttribute("workers", detail.canAssign() ? users.listWorkers(actor) : List.of());
    }
}
