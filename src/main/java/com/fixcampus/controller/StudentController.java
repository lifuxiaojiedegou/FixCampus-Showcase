package com.fixcampus.controller;

import com.fixcampus.entity.RepairCategory;
import com.fixcampus.entity.RepairStatus;
import com.fixcampus.form.OrderFilterForm;
import com.fixcampus.form.RepairOrderForm;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.service.RepairOrderService;
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
import java.util.List;

@Controller
public class StudentController {
    private final RepairOrderService orders;
    public StudentController(RepairOrderService orders) { this.orders = orders; }

    @InitBinder("repairOrderForm")
    void editableFields(WebDataBinder binder) {
        binder.setAllowedFields("location", "category", "description");
    }

    @InitBinder("orderFilter")
    void filterFields(WebDataBinder binder) { binder.setAllowedFields("status", "q"); }

    @ModelAttribute("currentUser")
    CampusPrincipal currentUser(@AuthenticationPrincipal CampusPrincipal actor) { return actor; }

    @ModelAttribute("categories")
    RepairCategory[] categories() { return RepairCategory.values(); }

    @ModelAttribute("statuses")
    RepairStatus[] statuses() { return RepairStatus.values(); }

    @GetMapping("/student/orders")
    public ModelAndView list(@AuthenticationPrincipal CampusPrincipal actor,
                            @Valid @ModelAttribute("orderFilter") OrderFilterForm filter, BindingResult errors, Model model) {
        model.addAttribute("orders", errors.hasErrors() ? List.of() : orders.searchForStudent(actor, filter));
        model.addAttribute("pendingCount", orders.countAwaitingConfirmation(actor));
        model.addAttribute("hasFilters", filter.hasFilters());
        model.addAttribute("filterError", errors.hasErrors() ? errors.getAllErrors().getFirst().getDefaultMessage() : null);
        return new ModelAndView("student/orders", errors.hasErrors() ? HttpStatus.BAD_REQUEST : HttpStatus.OK);
    }

    @GetMapping("/student/orders/new")
    public String createForm(Model model) {
        model.addAttribute("repairOrderForm", new RepairOrderForm());
        return "student/order-form";
    }

    @PostMapping("/student/orders")
    public ModelAndView create(@AuthenticationPrincipal CampusPrincipal actor,
                              @Valid @ModelAttribute("repairOrderForm") RepairOrderForm form,
                              BindingResult errors, RedirectAttributes flash) {
        if (errors.hasErrors()) {
            return new ModelAndView("student/order-form", HttpStatus.BAD_REQUEST);
        }
        Long id = orders.createForStudent(actor, form);
        flash.addFlashAttribute("successMessage", "报修已提交，请等待分配维修人员。");
        return redirectToDetail(id);
    }

    @GetMapping("/student/orders/{id}")
    public String detail(@AuthenticationPrincipal CampusPrincipal actor, @PathVariable Long id, Model model) {
        model.addAttribute("order", orders.getForStudent(actor, id));
        return "student/order-detail";
    }

    @PostMapping("/student/orders/{id}/cancel")
    public ModelAndView cancel(@AuthenticationPrincipal CampusPrincipal actor, @PathVariable Long id,
                              RedirectAttributes flash) {
        orders.cancel(actor, id);
        flash.addFlashAttribute("successMessage", "工单已撤销，记录仍保留在我的报修中。");
        return redirectToDetail(id);
    }

    @PostMapping("/student/orders/{id}/confirm")
    public ModelAndView confirm(@AuthenticationPrincipal CampusPrincipal actor, @PathVariable Long id,
                               RedirectAttributes flash) {
        orders.confirm(actor, id);
        flash.addFlashAttribute("successMessage", "已确认维修完成，工单记录将继续保留。");
        return redirectToDetail(id);
    }

    private static ModelAndView redirectToDetail(Long id) {
        var redirect = new RedirectView("/student/orders/" + id, true);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return new ModelAndView(redirect);
    }
}
