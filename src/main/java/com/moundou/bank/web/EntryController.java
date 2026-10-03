package com.moundou.bank.web;

import com.moundou.bank.identity.ProfileRules;
import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.EntryRequest;
import com.moundou.bank.ledger.EntryService;
import com.moundou.bank.ledger.EntryValidator;
import com.moundou.bank.ledger.TransactionKind;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Controller
public class EntryController {

    private final EntryService entryService;

    EntryController(EntryService entryService) {
        this.entryService = entryService;
    }



    @GetMapping("/transaction/new")
    String page(@AuthenticationPrincipal SignedInMember me, Model model) {

    }

    @PostMapping("/transactions")
    String save(@AuthenticationPrincipal SignedInMember me,
                            UUID initiatorId, EntryRequest entryRequest, Locale locale, RedirectAttributes redirect, Model model) {

        return switch (entryService.submit(initiatorId, entryRequest, locale)) {
            case EntryService.Outcome.Recorded recorded -> {
                if(entryRequest.role() == EntryRequest.Role.LENDER && recorded.alreadyRecorded()) {
                    redirect.addFlashAttribute("messageKey", "entry.already.lent");
                }
                else if(entryRequest.role() == EntryRequest.Role.BORROWER && recorded.alreadyRecorded())
                    redirect.addFlashAttribute("messageKey", "entry.already.borrowed");

                redirect.addFlashAttribute("messageKey", "entry.submit");
                yield "redirect:/transactions/new";
            }
            case EntryService.Outcome.Rejected rejected -> {
                rejected.fieldErrors().forEach((key, value) -> {
                    if(key.equals(EntryValidator.COUNTERPARTY) && value.equals(EntryValidator.COUNTERPARTY_REQUIRED))
                        model.addAttribute("messageKey", "ledger.entry.counterparty.required");
                    else if(key.equals(EntryValidator.COUNTERPARTY) && value.equals(EntryValidator.COUNTERPARTY_SELF)
                        model.addAttribute("messageKey", "ledger.entry.counterparty.self");

                });


            }
        };
    }

}
