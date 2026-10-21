package com.moundou.bank.web;

import com.moundou.bank.identity.ProfileService;
import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.HistoryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HistoryController {

    private final HistoryService historyService;
    private final ProfileService profileService;

    public HistoryController(HistoryService historyService,  ProfileService profileService) {
        this.historyService = historyService;
        this.profileService = profileService;
    }

    @GetMapping("/history")
    public String history(@AuthenticationPrincipal SignedInMember me, Model model) {
        model.addAttribute("history list", historyService.historyFor(me.memberId()));
        model.addAttribute("timeZone", profileService.view(me.memberId()).timeZone());

        return "history";
    }

}
