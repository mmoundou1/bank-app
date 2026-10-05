package com.moundou.bank.web;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Controller
public class DualConfirmationController {

   DualConfirmationService dualConfirmationService;

   DualConfirmationController(DualConfirmationService dualConfirmationService) {
       this.dualConfirmationService = dualConfirmationService;
   }

    @GetMapping("/pending/{id}")
    public String getPending(@AuthenticationPrincipal SignedInMember me, Model model) {
        return form(me, model);
    }

    @PostMapping("/pending/{id}/approve")
    public String transactionApproved(@AuthenticationPrincipal SignedInMember me, LedgerTransaction transaction) {

       dualConfirmationService.approveTransaction(me.memberId(), transaction);

       return "";
    }

    @PostMapping("/pending/{id}/decline")
    public String transactionDeclined(@AuthenticationPrincipal SignedInMember me, LedgerTransaction transaction) {

        dualConfirmationService.declineTransaction(me.memberId(), transaction);

        return "";
    }

    private String form(SignedInMember me, Model model) {
        model.addAttribute("items", dualConfirmationService.awaitingDecisionBy(me.memberId()));
        return  "pending";
    }

}
