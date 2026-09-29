package com.moundou.bank.web;

import com.moundou.bank.identity.AdminRuleException;
import com.moundou.bank.identity.AdminService;
import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.AuditService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * The administration page (Technical Design 8; MB-10). Only administrators reach these
 * routes (SecurityConfig), and every service call checks again from the database
 * (Auth.Roles-5). After each change the browser is redirected back to the page, with a
 * one-line message saying what happened.
 */
@Controller
class AdminController {

    private final AdminService admin;
    private final AuditService audit;

    AdminController(AdminService admin, AuditService audit) {
        this.admin = admin;
        this.audit = audit;
    }

    @GetMapping("/admin")
    String page(@AuthenticationPrincipal SignedInMember me, Model model) {
        model.addAttribute("overview", admin.overview(me.memberId()));
        model.addAttribute("me", me.memberId());
        return "admin";
    }

    @PostMapping("/admin/allow-list")
    String allow(@AuthenticationPrincipal SignedInMember me, @RequestParam(defaultValue = "") String email,
                 RedirectAttributes redirect) {
        try {
            String added = admin.allow(me.memberId(), email);
            flash(redirect, "admin.allowList.added", added);
        } catch (AdminRuleException refused) {
            flashError(redirect, refused.messageKey());
            redirect.addFlashAttribute("typedEmail", email);   // keep what was typed (UI-4)
        }
        return "redirect:/admin";
    }

    @PostMapping("/admin/allow-list/remove")
    String disallow(@AuthenticationPrincipal SignedInMember me, @RequestParam String email, RedirectAttributes redirect) {
        try {
            admin.disallow(me.memberId(), email);
            flash(redirect, "admin.allowList.removed", email);
        } catch (AdminRuleException refused) {
            flashError(redirect, refused.messageKey());
        }
        return "redirect:/admin";
    }

    @PostMapping("/admin/members/{id}/deactivate")
    String deactivate(@AuthenticationPrincipal SignedInMember me, @PathVariable UUID id,
                      @RequestParam(defaultValue = "") String name, RedirectAttributes redirect) {
        try {
            admin.deactivate(me.memberId(), id);
            flash(redirect, "admin.members.deactivated", name);
        } catch (AdminRuleException refused) {
            flashError(redirect, refused.messageKey());
        }
        return "redirect:/admin";
    }

    @GetMapping("/admin/audit")
    String audit(@AuthenticationPrincipal SignedInMember me, @RequestParam(required = false) UUID a,
                 @RequestParam(required = false) UUID b, Model model, RedirectAttributes redirect) {
        var view = audit.audit(me.memberId(), a, b);
        if (view.isEmpty()) {
            flashError(redirect, "admin.audit.chooseTwo");
            return "redirect:/admin";
        }
        model.addAttribute("audit", view.get());
        return "audit";
    }

    private static void flash(RedirectAttributes redirect, String key, String argument) {
        redirect.addFlashAttribute("messageKey", key);
        redirect.addFlashAttribute("messageArg", argument);
    }

    private static void flashError(RedirectAttributes redirect, String key) {
        redirect.addFlashAttribute("errorKey", key);
    }
}
