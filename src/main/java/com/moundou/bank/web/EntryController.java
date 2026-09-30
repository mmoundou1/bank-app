package com.moundou.bank.web;

import com.moundou.bank.identity.ProfileRules;
import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.EntryRequest;
import com.moundou.bank.ledger.EntryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Locale;
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
                            UUID initiatorId, EntryRequest entryRequest, Locale locale, RedirectAttributes redirect) {

        return switch (entryService.submit(initiatorId, entryRequest, locale)) {
            case EntryService.Outcome.Recorded recorded -> {

            }
            case EntryService.Outcome.Recorded rejected -> {

            }
        };
    }

}
