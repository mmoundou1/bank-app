package com.moundou.bank.identity;

import com.moundou.bank.NotPermittedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The family administrator's operations (SRS 3.11; MB-10): the allow-list, which is how
 * accounts are provisioned (Auth.Roles-8, -9, TBD-2), and deactivation (Auth.Roles-3).
 *
 * Every method first checks, from the database, that the actor is an active
 * administrator (Auth.Roles-5). The session's role is a snapshot from sign-in; reading
 * it fresh means a demoted or deactivated administrator loses these powers at once.
 *
 * Removing an address and deactivating a member are separate operations, and neither
 * deletes a member or a transaction (Data-7, Auth.Roles-7).
 */
@Service
public class AdminService {

    static final String INVALID_EMAIL = "admin.allowList.invalidEmail";
    static final String LAST_ADMINISTRATOR = "admin.lastAdministrator";   // Auth.Roles-12 (SRS wording)

    public record Overview(List<MemberAdministration.MemberSummary> members,
                           List<MemberAdministration.AllowedEmail> allowList) { }

    private final MemberAdministration administration;

    public AdminService(MemberAdministration administration) {
        this.administration = administration;
    }

    @Transactional(readOnly = true)
    public Overview overview(UUID actorId) {
        requireAdministrator(actorId, "view the administration page");
        return new Overview(administration.listMembers(), administration.listAllowed());
    }

    /**
     * Adds an address to the allow-list (Auth.Roles-8). Its owner's account is created on
     * their first sign-in (Auth.Roles-2). Adding an address already listed is a no-op.
     *
     * @throws AdminRuleException with {@link #INVALID_EMAIL} if it cannot be an address
     */
    @Transactional
    public String allow(UUID actorId, String typedEmail) {
        requireAdministrator(actorId, "change the allow-list");
        String email = EmailAddresses.normalize(typedEmail).orElseThrow(() -> new AdminRuleException(INVALID_EMAIL));
        administration.allow(email, actorId);
        return email;
    }

    /**
     * Removes an address from the allow-list (Auth.Roles-8). Its owner can no longer sign
     * in; their account and transactions stay (Data-7).
     *
     * @throws AdminRuleException with {@link #LAST_ADMINISTRATOR} if this is the address of
     *         the last administrator able to sign in (Auth.Roles-12)
     */
    @Transactional
    public void disallow(UUID actorId, String email) {
        requireAdministrator(actorId, "change the allow-list");
        String normalized = EmailAddresses.normalize(email).orElse(email);
        List<UUID> usable = administration.lockUsableAdministrators();
        List<UUID> losing = administration.membersWithEmail(normalized).stream().filter(usable::contains).toList();
        if (!losing.isEmpty() && usable.size() <= losing.size()) {
            throw new AdminRuleException(LAST_ADMINISTRATOR);
        }
        administration.disallow(normalized);
    }

    /**
     * Deactivates a member (Auth.Roles-3). They can no longer sign in (Auth.Login-5) or be
     * chosen as a counterparty (Auth.Roles-4); their transactions stay and still count
     * (Auth.Roles-7). Deactivating someone already deactivated is a no-op.
     *
     * @throws AdminRuleException with {@link #LAST_ADMINISTRATOR} if they are the last
     *         administrator able to sign in (Auth.Roles-12)
     */
    @Transactional
    public void deactivate(UUID actorId, UUID memberId) {
        requireAdministrator(actorId, "deactivate members");
        List<UUID> usable = administration.lockUsableAdministrators();
        if (usable.contains(memberId) && usable.size() == 1) {
            throw new AdminRuleException(LAST_ADMINISTRATOR);
        }
        administration.deactivate(memberId);
    }

    /** Throws unless the actor is, right now, an active family administrator (Auth.Roles-5). */
    public void requireAdministrator(UUID actorId, String action) {
        boolean administrator = administration.findById(actorId)
                .filter(MemberAdministration.MemberSummary::active)
                .filter(m -> m.role() == Role.FAMILY_ADMINISTRATOR)
                .isPresent();
        if (!administrator) {
            throw new NotPermittedException(actorId, action);
        }
    }
}
