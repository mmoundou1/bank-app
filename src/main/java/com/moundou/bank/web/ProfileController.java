package com.moundou.bank.web;

import com.moundou.bank.identity.ProfileRules;
import com.moundou.bank.identity.ProfileService;
import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.EntryRequest;
import com.moundou.bank.ledger.EntryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * The signed-in member's own profile (Auth.Roles-11, CR-16). The route has no member id
 * in it: whose profile it is comes only from the session.
 */
@Controller
class ProfileController {

    private final ProfileService profiles;

    ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/profile")
    String page(@AuthenticationPrincipal SignedInMember me, Model model) {
        ProfileRules.Profile profile = profiles.view(me.memberId());
        return form(model, profile.displayName(), profile.timeZone().getId());
    }

    @PostMapping("/profile")
    String save(@AuthenticationPrincipal SignedInMember me,
                @RequestParam(defaultValue = "") String displayName,
                @RequestParam(defaultValue = "") String timeZone,
                Model model, RedirectAttributes redirect) {
        return switch (profiles.update(me.memberId(), displayName, timeZone)) {
            case ProfileService.Outcome.Saved saved -> {
                redirect.addFlashAttribute("messageKey", "profile.saved");
                yield "redirect:/profile";
            }
            case ProfileService.Outcome.Rejected rejected -> {
                model.addAttribute("errors", rejected.fieldErrors());   // shown beside each field (UI-4)
                yield form(model, displayName, timeZone);                // with what was typed
            }
        };
    }

    private static String form(Model model, String displayName, String timeZone) {
        model.addAttribute("displayName", displayName);
        model.addAttribute("timeZone", timeZone);
        model.addAttribute("zones", ProfileRules.ZONES);
        return "profile";
    }


}
