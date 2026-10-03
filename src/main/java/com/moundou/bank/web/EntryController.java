package com.moundou.bank.web;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.i18n.Formats;
import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.MemberDirectory;
import com.moundou.bank.identity.ProfileRules;
import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Controller
public class EntryController {

    private final Formats formats;
    private final EntryService entryService;
    private final EntryValidator validator;
    private final MemberDirectory members;

    EntryController(EntryService entryService, EntryValidator validator, MemberDirectory members, Formats formats) {
        this.entryService = entryService;
        this.validator = validator;
        this.members = members;
        this.formats = formats;
    }



    @GetMapping("/transactions/new")
    String page(@AuthenticationPrincipal SignedInMember me, Model model) {
        return form(model, me, entryService.submissionKey());
    }

    @PostMapping("/transactions")
    String save(@AuthenticationPrincipal SignedInMember me,
                            UUID initiatorId, EntryRequest entryRequest, Locale locale, RedirectAttributes redirect, Model model) {

        return switch (entryService.submit(initiatorId, entryRequest, locale)) {
            case EntryService.Outcome.Recorded recorded -> {
                LedgerTransaction t = recorded.transaction();

                // Which way did the money go? Ask the saved transaction, not the form:
                // in a split the form's role is ignored, and you are always the lender.
                boolean iLent = t.creditor().equals(me.memberId());
                UUID otherId = iLent ? t.debtor() : t.creditor();
                String otherName = members.findById(otherId).map(Member::displayName).orElseThrow();

                // e.g. "entry.sent.lent" or "entry.already.borrowed"
                String key = (recorded.alreadyRecorded() ? "entry.already." : "entry.sent.")
                        + (iLent ? "lent" : "borrowed");

                redirect.addFlashAttribute("messageKey", key);
                redirect.addFlashAttribute("messageArgs", List.of(
                        otherName,                                       // {0}
                        formats.amount(t.amountMinor(), t.currency()),   // {1}, e.g. "$20.00"
                        formats.date(t.transactionDate())));             // {2}, e.g. "Sep 30, 2026"
                yield "redirect:/transactions/new";
            }
            case EntryService.Outcome.Rejected rejected -> {
                model.addAttribute("entry", entryRequest);
                model.addAttribute("errors", rejected.fieldErrors());
                yield form(model, me, entryRequest.submissionKey());
            }
        };
    }

    private String form(Model model, SignedInMember me, UUID submissionKey) {
        model.addAttribute("submissionKey", submissionKey);
        model.addAttribute("counterparties", members.counterpartiesFor(me.memberId()));
        model.addAttribute("currencies",  EntryValidator.CURRENCIES.stream().sorted().toList());

        Member member = members.findById(me.memberId())
                .filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(me.memberId(), "record an entry"));

        model.addAttribute("today", validator.today(member));

        return "transactions-new";
    }

}
