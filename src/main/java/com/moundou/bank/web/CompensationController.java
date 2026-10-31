package com.moundou.bank.web;

import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.MemberDirectory;
import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.CompensationService;
import com.moundou.bank.ledger.HistoryDirection;
import com.moundou.bank.ledger.LedgerTransaction;
import com.moundou.bank.ledger.UndoRules;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

/**
 * Undoing a record (MB-14 journey 2; Ledger.History-5, -8 to -11). The confirm screen
 * at GET /history/{id}/undo, and its POST. Both end back on the history.
 *
 * CHANGED from your version:
 *  - @Controller, not @RestController. A @RestController writes the returned String
 *    into the response as text; a @Controller treats it as a template name, or as
 *    "redirect:/path".
 *  - The id is in the path, "/history/{targetId}/undo", so @PathVariable has
 *    something to bind; "/undo-repayment" had no {targetId} in it.
 *  - The form fields arrive as @RequestParam. @RequestBody reads the raw request body,
 *    which for an HTML form is "note=...&submissionKey=...", not the note.
 *  - After a POST the browser is always redirected (Post/Redirect/Get), and anything
 *    to show after the redirect goes in RedirectAttributes as a flash attribute. A
 *    Model attribute is lost when the browser makes its new request.
 *  - Model attribute names are identifiers, so no spaces.
 */
@Controller
public class CompensationController {

    private final CompensationService compensationService;
    private final MemberDirectory members;

    public CompensationController(CompensationService compensationService, MemberDirectory members) {
        this.compensationService = compensationService;
        this.members = members;
    }

    @GetMapping("/history/{targetId}/undo")
    public String targetDetails(@AuthenticationPrincipal SignedInMember me, @PathVariable UUID targetId,
                                Model model, RedirectAttributes redirect) {
        CompensationService.UndoTarget details = compensationService.targetDetails(me.memberId(), targetId);

        // A record that cannot be undone has no confirm screen: back to the history, saying why.
        // Reached only by typing the address or from an out-of-date page, since the link is hidden.
        if (details.check() instanceof UndoRules.Check.Refused refused) {
            redirect.addFlashAttribute("errorKey", refused.messageKey());
            return "redirect:/history";
        }

        LedgerTransaction target = details.target();
        model.addAttribute("target", target);
        model.addAttribute("direction", HistoryDirection.of(target, me.memberId()));
        model.addAttribute("otherPartyName", name(otherParty(target, me.memberId())));
        model.addAttribute("submissionKey", UUID.randomUUID());   // rendered once, sent back on a retry
        return "undo";
    }

    @PostMapping("/history/{targetId}/undo")
    public String targetCorrection(@AuthenticationPrincipal SignedInMember me, @PathVariable UUID targetId,
                                   @RequestParam(required = false) String note,
                                   @RequestParam UUID submissionKey,
                                   RedirectAttributes redirect) {
        return switch (compensationService.compensation(me.memberId(), targetId, note, submissionKey)) {
            case CompensationService.Outcome.Recorded recorded -> {
                // The same message for a retry: the first tap was saved, so "sent" is true.
                redirect.addFlashAttribute("messageKey", "history.undo.sent");
                redirect.addFlashAttribute("messageArgs",
                        List.of(name(otherParty(recorded.transaction(), me.memberId()))));
                yield "redirect:/history";
            }
            case CompensationService.Outcome.Rejected rejected -> {
                redirect.addFlashAttribute("errorKey", rejected.reason());
                yield "redirect:/history";
            }
        };
    }

    private static UUID otherParty(LedgerTransaction t, UUID me) {
        return t.creditor().equals(me) ? t.debtor() : t.creditor();
    }

    private String name(UUID memberId) {
        return members.findById(memberId).map(Member::displayName).orElseThrow();
    }
}
