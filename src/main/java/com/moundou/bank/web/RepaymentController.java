package com.moundou.bank.web;

import com.moundou.bank.i18n.Formats;
import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.MemberDirectory;
import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.EntryValidator;
import com.moundou.bank.ledger.LedgerTransaction;
import com.moundou.bank.ledger.RepaymentService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Recording a repayment (MB-13, UC-04). One template, repayments-new, in two steps:
 * <ol>
 *   <li>{@code GET /repayments/new}: the loans the signed-in member can repay;</li>
 *   <li>{@code GET /repayments/new?loan={id}}: the form for one of them, which POSTs
 *       to {@code /repayments}.</li>
 * </ol>
 * Every rule lives in RepaymentService. This class only turns its answers into pages:
 * the controller never decides whether a loan may be repaid, it asks.
 */
@Controller
public class RepaymentController {

    private final RepaymentService repayments;
    private final MemberDirectory members;
    private final Formats formats;

    RepaymentController(RepaymentService repayments, MemberDirectory members, Formats formats) {
        this.repayments = repayments;
        this.members = members;
        this.formats = formats;
    }

    /**
     * Both steps share one path; the {@code ?loan=} query parameter picks the step.
     * Spring matches mappings on the path only, so the query string goes in
     * {@code @RequestParam}, not in the mapping. {@code name = "loan"} ties the Java
     * parameter to the URL's spelling, and {@code required = false} lets it be absent,
     * in which case Spring passes null.
     */
    @GetMapping("/repayments/new")
    String page(@AuthenticationPrincipal SignedInMember me,
                @RequestParam(name = "loan", required = false) UUID loanId,
                Model model, RedirectAttributes redirect) {

        if (loanId == null) {
            List<LedgerTransaction> loans = repayments.outstandingLoansOwedBy(me.memberId());
            model.addAttribute("loans", loans);
            model.addAttribute("names", lenderNames(loans));
            return "repayments-new";
        }

        // A link that no longer leads anywhere (the loan was repaid in another tab, or
        // the id was typed by hand) goes back to the list with a message, not to an error page.
        return repayments.viewTransaction(me.memberId(), loanId)
                .map(loan -> form(model, loan, UUID.randomUUID()))
                .orElseGet(() -> backToList(redirect, "ledger.repayment.loan.notOutstanding"));
    }

    @PostMapping("/repayments")
    String submit(@AuthenticationPrincipal SignedInMember me,
                  RepaymentService.RepaymentRequest request,
                  Locale locale, Model model, RedirectAttributes redirect) {

        return switch (repayments.submit(me.memberId(), request, locale)) {
            case RepaymentService.Outcome.Recorded recorded -> {
                LedgerTransaction repayment = recorded.transaction();
                // On a repayment the lender is the debtor (direction convention D2).
                String lender = nameOf(repayment.debtor());
                redirect.addFlashAttribute("messageKey",
                        recorded.alreadyRecorded() ? "repayment.already" : "repayment.sent");
                redirect.addFlashAttribute("messageArgs", List.of(
                        lender,                                                         // {0}
                        formats.amount(repayment.amountMinor(), repayment.currency())));  // {1}
                yield "redirect:/repayments/new";
            }
            case RepaymentService.Outcome.Rejected rejected -> {
                model.addAttribute("repayment", request);   // so the form shows what was typed (UI-4)
                model.addAttribute("errors", rejected.fieldErrors());
                // Show the form again for the same loan, with the same key, so a fixed and
                // resubmitted form is still the same submission (Ledger.Entry-14).
                yield repayments.viewTransaction(me.memberId(), request.loanId())
                        .map(loan -> form(model, loan, request.submissionKey()))
                        .orElseGet(() -> backToList(redirect, rejected.fieldErrors()
                                .getOrDefault("loan", "ledger.repayment.loan.notOutstanding")));
            }
        };
    }

    private String form(Model model, LedgerTransaction loan, UUID submissionKey) {
        model.addAttribute("loan", loan);
        model.addAttribute("names", lenderNames(List.of(loan)));
        model.addAttribute("submissionKey", submissionKey);
        model.addAttribute("currencies", EntryValidator.CURRENCIES.stream().sorted().toList());
        return "repayments-new";
    }

    private String backToList(RedirectAttributes redirect, String errorKey) {
        redirect.addFlashAttribute("errorKey", errorKey);
        return "redirect:/repayments/new";
    }

    /**
     * Lender id to display name, for every loan shown. On a loan the lender is the creditor.
     * {@code Collectors.toMap(keyFunction, valueFunction)} builds a Map from a stream:
     * here each lender id becomes a key, and its display name the value.
     * {@code distinct()} first, because toMap refuses duplicate keys and one lender may
     * appear on several loans.
     */
    private Map<UUID, String> lenderNames(Collection<LedgerTransaction> loans) {
        return loans.stream()
                .map(LedgerTransaction::creditor)
                .distinct()
                .collect(Collectors.toMap(id -> id, this::nameOf));
    }

    private String nameOf(UUID memberId) {
        return members.findById(memberId).map(Member::displayName).orElseThrow();
    }
}
