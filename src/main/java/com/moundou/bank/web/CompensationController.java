package com.moundou.bank.web;

import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.CompensationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.UUID;

@RestController
public class CompensationController {

    private final CompensationService compensationService;

    public CompensationController(CompensationService compensationService) {
        this.compensationService = compensationService;
    }

    @GetMapping("/undo-repayment")
    public String targetDetails(@AuthenticationPrincipal SignedInMember me, @PathVariable UUID targetId, Model model) {
        model.addAttribute("transaction details", compensationService.targetDetails(me.memberId(), targetId));
        return "undo-repayment";
    }

    @PostMapping("/undo-repayment")
    public String targetCorrection(@AuthenticationPrincipal SignedInMember me, @PathVariable UUID targetId,
                                                                                @RequestBody String note, Model model,
                                                                                        RedirectAttributes redirect) {
        return switch (compensationService.compensation(me.memberId(), targetId, note)) {
            case CompensationService.Outcome.Recorded recorded -> {
                redirect.addFlashAttribute("messageKey",
                        recorded.alreadyRecorded() ? "TBD" : "TBD");
                yield "TBD";
            }
            case CompensationService.Outcome.Rejected rejected -> {
                model.addAttribute("error", rejected.reason());
                yield "TBD";
            }
        };
    }

}
