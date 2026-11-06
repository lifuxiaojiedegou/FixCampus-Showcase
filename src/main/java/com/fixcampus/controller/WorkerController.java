package com.fixcampus.controller;

import com.fixcampus.form.CompleteRepairForm;
import com.fixcampus.form.OrderFilterForm;
import com.fixcampus.entity.RepairStatus;
import java.util.List;
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

@Controller
public class WorkerController {
    private final RepairOrderService orders;
    public WorkerController(RepairOrderService orders) { this.orders = orders; }

    @InitBinder("completeRepairForm")
    void editableFields(WebDataBinder binder) { binder.setAllowedFields("repairResult"); }

    @InitBinder("orderFilter")
    void filterFields(WebDataBinder binder) { binder.setAllowedFields("status", "q"); }

    @ModelAttribute("statuses")
    List<RepairStatus> statuses() { return List.of(RepairStatus.PENDING_REPAIR, RepairStatus.IN_PROGRESS,
            RepairStatus.PENDING_CONFIRMATION, RepairStatus.COMPLETED); }

    @ModelAttribute("currentUser")
    CampusPrincipal currentUser(@AuthenticationPrincipal CampusPrincipal actor) { return actor; }

    @GetMapping("/worker/orders")
    public ModelAndView list(@AuthenticationPrincipal CampusPrincipal actor,
                            @Valid @ModelAttribute("orderFilter") OrderFilterForm filter, BindingResult errors, Model model) {
        if (!errors.hasErrors() && filter.statusValue() != null && !statuses().contains(filter.statusValue())) {
            errors.rejectValue("status", "invalid", "请选择有效的工单状态。");
        }
        model.addAttribute("orders", errors.hasErrors() ? List.of() : orders.searchForWorker(actor, filter));
        model.addAttribute("todo", orders.workerTodo(actor));
        model.addAttribute("hasFilters", filter.hasFilters());
        model.addAttribute("filterError", errors.hasErrors() ? errors.getAllErrors().getFirst().getDefaultMessage() : null);
        return new ModelAndView("worker/orders", errors.hasErrors() ? HttpStatus.BAD_REQUEST : HttpStatus.OK);
    }

    @GetMapping("/worker/orders/{id}")
    public String detail(@AuthenticationPrincipal CampusPrincipal actor, @PathVariable Long id, Model model) {
        model.addAttribute("order", orders.getForWorker(actor, id));
        model.addAttribute("completeRepairForm", new CompleteRepairForm());
        return "worker/order-detail";
    }

    @PostMapping("/worker/orders/{id}/start")
    public ModelAndView start(@AuthenticationPrincipal CampusPrincipal actor, @PathVariable Long id,
                             RedirectAttributes flash) {
        orders.start(actor, id);
        flash.addFlashAttribute("successMessage", "已开始维修，工单已进入维修中状态。");
        return redirectToDetail(id);
    }

    @PostMapping("/worker/orders/{id}/complete")
    public ModelAndView complete(@AuthenticationPrincipal CampusPrincipal actor, @PathVariable Long id,
                                @Valid @ModelAttribute("completeRepairForm") CompleteRepairForm form,
                                BindingResult errors, Model model, RedirectAttributes flash) {
        if (errors.hasErrors()) {
            model.addAttribute("order", orders.getForWorker(actor, id));
            return new ModelAndView("worker/order-detail", HttpStatus.BAD_REQUEST);
        }
        orders.complete(actor, id, form);
        flash.addFlashAttribute("successMessage", "维修结果已提交，等待报修学生确认。");
        return redirectToDetail(id);
    }

    private ModelAndView redirectToDetail(Long id) {
        var redirect = new RedirectView("/worker/orders/" + id, true);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return new ModelAndView(redirect);
    }
}
