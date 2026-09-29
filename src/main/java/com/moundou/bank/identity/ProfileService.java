package com.moundou.bank.identity;

import com.moundou.bank.NotPermittedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * A member's own profile: display name and time zone (Auth.Roles-6, Auth.Roles-11,
 * CON-8, CR-16).
 *
 * There is no member id in any method: the actor is always the signed-in member, so no
 * member can reach another's profile (T-Roles-11b). The web layer passes the id from
 * the session, never from the request.
 */
@Service
public class ProfileService {

    public sealed interface Outcome {
        record Saved(ProfileRules.Profile profile) implements Outcome { }
        record Rejected(Map<String, String> fieldErrors) implements Outcome { }
    }

    private final MemberAdministration administration;

    public ProfileService(MemberAdministration administration) {
        this.administration = administration;
    }

    @Transactional(readOnly = true)
    public ProfileRules.Profile view(UUID actorId) {
        MemberAdministration.MemberSummary me = activeMember(actorId);
        return new ProfileRules.Profile(me.displayName(), me.timeZone());
    }

    @Transactional
    public Outcome update(UUID actorId, String displayName, String timeZone) {
        activeMember(actorId);
        return switch (ProfileRules.validate(displayName, timeZone)) {
            case ProfileRules.Result.Invalid invalid -> new Outcome.Rejected(invalid.fieldErrors());
            case ProfileRules.Result.Valid valid -> {
                administration.updateProfile(actorId, valid.profile().displayName(), valid.profile().timeZone());
                yield new Outcome.Saved(valid.profile());
            }
        };
    }

    private MemberAdministration.MemberSummary activeMember(UUID actorId) {
        return administration.findById(actorId)
                .filter(MemberAdministration.MemberSummary::active)
                .orElseThrow(() -> new NotPermittedException(actorId, "change a profile"));
    }
}
