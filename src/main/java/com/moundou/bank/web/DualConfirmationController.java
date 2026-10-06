package com.moundou.bank.web;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.ProfileService;
import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Controller
public class DualConfirmationController {

   private final DualConfirmationService dualConfirmationService;

   DualConfirmationController(DualConfirmationService dualConfirmationService) {
       this.dualConfirmationService = dualConfirmationService;
   }

    @GetMapping("/pending/{id}")
    public String getPending(@AuthenticationPrincipal SignedInMember me, Model model) {
        return form(me, model);
    }

    @PostMapping("/pending/{id}/approve")
    public String transactionApproved(@AuthenticationPrincipal SignedInMember me,
                                      @PathVariable UUID id, RedirectAttributes redirect) {

       redirect.addFlashAttribute("messageKey", "decision.approved");
       return "redirect:/";

    }

    @PostMapping("/pending/{id}/decline")
    public String transactionDeclined(@AuthenticationPrincipal SignedInMember me, LedgerTransaction transaction,
                                      String reason, RedirectAttributes redirect) {
       dualConfirmationService.decline(me.memberId(), transaction, reason);
       redirect.addFlashAttribute("messageKey", "decision.declined");

       return "redirect:/";
    }

    private String form(SignedInMember me, Model model) {
        model.addAttribute("items", dualConfirmationService.awaitingDecisionBy(me.memberId()));
        return  "pending";
    }

}
