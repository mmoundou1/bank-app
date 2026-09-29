package com.moundou.bank.web;

import com.moundou.bank.identity.MemberAdministration;
import com.moundou.bank.identity.Role;
import com.moundou.bank.identity.SignedInMember;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Set;

/**
 * The sign-in page, and a minimal home page to land on after signing in. Home becomes
 * the dashboard in MB-15.
 */
@Controller
class SignInController {

    private final MemberAdministration administration;

    SignInController(MemberAdministration administration) {
        this.administration = administration;
    }

    /** The refusal reasons the page has a message for; anything else gets the general one. */
    private static final Set<String> REASONS = Set.of("email_not_verified", "not_on_allow_list", "deactivated");

    @GetMapping("/login")
    String login(@RequestParam(required = false) String refused,
                 @RequestParam(required = false) String failed,
                 @RequestParam(required = false) String signedOut,
                 Model model) {
        if (refused != null) {
            model.addAttribute("messageKey",
                    REASONS.contains(refused) ? "signIn.refused." + refused : "signIn.failed");
        } else if (failed != null) {
            model.addAttribute("messageKey", "signIn.failed");
        } else if (signedOut != null) {
            model.addAttribute("messageKey", "signIn.signedOut");
        }
        return "login";
    }

    /**
     * The name and role come from the database, not the session: the session holds what
     * was true at sign-in, and the member may have renamed themselves since (Auth.Roles-11).
     */
    @GetMapping("/")
    String home(@AuthenticationPrincipal SignedInMember member, Model model) {
        var me = administration.findById(member.memberId()).orElseThrow();
        model.addAttribute("displayName", me.displayName());
        model.addAttribute("administrator", me.role() == Role.FAMILY_ADMINISTRATOR);
        return "home";
    }
}
