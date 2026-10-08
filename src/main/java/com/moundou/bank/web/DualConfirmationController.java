package com.moundou.bank.web;

import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.MemberDirectory;
import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.*;

@Controller
public class DualConfirmationController {

   private final DualConfirmationService dualConfirmationService;
   private final MemberDirectory members;

   DualConfirmationController(DualConfirmationService dualConfirmationService, MemberDirectory members) {
       this.dualConfirmationService = dualConfirmationService;
       this.members = members;
   }

    @GetMapping("/pending/{id}")
    public String getPending(@AuthenticationPrincipal SignedInMember me, @PathVariable UUID id, Model model) {

       DualConfirmationService.View details = dualConfirmationService.view(me.memberId(), id);
       LedgerTransaction transaction = details.transaction();
       Map<UUID, String> names = details.partiesMap();

       return form(transaction, names, model);
    }

    @PostMapping("/pending/{id}/approve")
    public String transactionApproved(@AuthenticationPrincipal SignedInMember me,
                                      @PathVariable UUID id, RedirectAttributes redirect) {

       redirect.addFlashAttribute("messageKey", "decision.approved");
       return "redirect:/";

    }

    @PostMapping("/pending/{id}/decline")
    public String transactionDeclined(@AuthenticationPrincipal SignedInMember me, @PathVariable UUID id,
                                      String reason, RedirectAttributes redirect) {
       return switch (dualConfirmationService.decline(me.memberId(), id, reason)) {
               case DualConfirmationService.Outcome.Recorded recorded -> {
                   UUID initiatorId = recorded.transaction().initiatedBy();
                   Member member = members.findById(initiatorId)
                                            .orElseThrow();

                   redirect.addFlashAttribute("messageKey", "decision.declined");
                   redirect.addFlashAttribute("messageArgs", List.of(member.displayName()));

                   yield "redirect:/";
               }

               case DualConfirmationService.Outcome.Rejected rejected -> {
                   redirect.addFlashAttribute("errorKey", rejected.reason());
                    yield "redirect:/pending/" + id;
               }
       };
    }

    @PostMapping("/transactions/{id}/cancel")
    public String cancel(@AuthenticationPrincipal SignedInMember me, RedirectAttributes redirect) {
       redirect.addFlashAttribute("messageKey", "decision.cancelled");
       redirect.addFlashAttribute("messageArgs", List.of());

       return "redirect:/";
    }

    private String form(LedgerTransaction transaction, Map<UUID, String> names, Model model) {
       model.addAttribute("item", transaction);
       model.addAttribute("names", names);

       return  "pending";
    }

}
