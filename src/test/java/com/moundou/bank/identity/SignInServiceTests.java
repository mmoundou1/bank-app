package com.moundou.bank.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast suite. The sign-in decision (Technical Design 6). No database and no Spring:
 * the allow-list and the members are small in-memory stand-ins defined below, so these
 * run in well under a second.
 *
 * MB-9 exercise: delete the {@code @Disabled} line, run this class in IntelliJ (the
 * green arrow beside the class name) or with {@code mvn test -Dtest=SignInServiceTests},
 * watch every test fail, then write {@link SignInService#signIn} until they all pass.
 */
class SignInServiceTests {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("America/New_York");

    private FakeAllowList allowList;
    private FakeMemberAccounts members;
    private SignInService service;

    @BeforeEach
    void setUp() {
        allowList = new FakeAllowList();
        members = new FakeMemberAccounts();
        service = new SignInService(allowList, members, DEFAULT_ZONE);
    }

    private static GoogleIdentity google(String subject, String email, Boolean verified, String name) {
        return new GoogleIdentity(subject, email, verified, name);
    }

    private static MemberAccount signedIn(SignInResult result) {
        assertThat(result).isInstanceOf(SignInResult.SignedIn.class);
        return ((SignInResult.SignedIn) result).member();
    }

    // ---- Letting people in ----------------------------------------------------------

    /** T-Roles-2a: first sign-in by an allow-listed address creates the member, no admin step. */
    @Test
    void firstSignInOfAnAllowListedAddressCreatesTheMember() {
        allowList.add("ama@example.com");

        MemberAccount member = signedIn(service.signIn(google("sub-ama", "ama@example.com", true, "Ama Moundou")));

        assertThat(member.displayName()).isEqualTo("Ama Moundou");
        assertThat(member.role()).isEqualTo(Role.FAMILY_MEMBER);
        assertThat(member.timeZone()).isEqualTo(DEFAULT_ZONE);
        assertThat(member.active()).isTrue();
        assertThat(members.created).containsExactly(
                new NewMemberAccount("sub-ama", "ama@example.com", "Ama Moundou", DEFAULT_ZONE, Role.FAMILY_MEMBER));
    }

    @Test
    void aReturningMemberIsFoundAndNotCreatedAgain() {
        allowList.add("ama@example.com");
        MemberAccount first = signedIn(service.signIn(google("sub-ama", "ama@example.com", true, "Ama")));

        MemberAccount second = signedIn(service.signIn(google("sub-ama", "ama@example.com", true, "Ama")));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(members.created).hasSize(1);
    }

    /**
     * Why the member is found by subject, not by email: people change the address on
     * their Google account, but Google never changes their subject. Found by email,
     * Ama would become a new, empty member with none of her history.
     */
    @Test
    void theMemberIsFoundByGoogleSubjectSoAChangedAddressKeepsTheirHistory() {
        MemberAccount existing = members.existing("sub-ama", "Ama", true);
        allowList.add("ama.new@example.com");

        MemberAccount member = signedIn(service.signIn(google("sub-ama", "ama.new@example.com", true, "Ama")));

        assertThat(member.id()).isEqualTo(existing.id());
        assertThat(members.created).isEmpty();
    }

    /** The schema only accepts lower-case addresses, and Google may send capitals. */
    @Test
    void capitalsInTheAddressStillMatchTheAllowList() {
        allowList.add("ama.moundou@example.com");

        signedIn(service.signIn(google("sub-ama", "Ama.Moundou@Example.com", true, "Ama")));

        assertThat(members.created).extracting(NewMemberAccount::email).containsExactly("ama.moundou@example.com");
    }

    @Test
    void withNoNameFromGoogleTheDisplayNameIsTheAddressBeforeTheAt() {
        allowList.add("ama@example.com");

        MemberAccount member = signedIn(service.signIn(google("sub-ama", "ama@example.com", true, null)));

        assertThat(member.displayName()).isEqualTo("ama");
    }

    // ---- Keeping people out ---------------------------------------------------------

    /** T-Roles-2b: a real Google account that is not on the allow-list. */
    @Test
    void anAddressNotOnTheAllowListIsRefusedAndNothingIsCreated() {
        SignInResult result = service.signIn(google("sub-eve", "eve@example.com", true, "Eve"));

        assertThat(result).isEqualTo(new SignInResult.Refused(SignInResult.Reason.NOT_ON_ALLOW_LIST));
        assertThat(members.created).isEmpty();
    }

    @Test
    void anUnverifiedAddressIsRefusedEvenWhenItIsOnTheAllowList() {
        allowList.add("ama@example.com");

        SignInResult result = service.signIn(google("sub-ama", "ama@example.com", false, "Ama"));

        assertThat(result).isEqualTo(new SignInResult.Refused(SignInResult.Reason.EMAIL_NOT_VERIFIED));
        assertThat(members.created).isEmpty();
    }

    /** Google may leave the claim out. Absence of proof is not proof. */
    @Test
    void aMissingVerificationClaimCountsAsUnverified() {
        allowList.add("ama@example.com");

        SignInResult result = service.signIn(google("sub-ama", "ama@example.com", null, "Ama"));

        assertThat(result).isEqualTo(new SignInResult.Refused(SignInResult.Reason.EMAIL_NOT_VERIFIED));
        assertThat(members.created).isEmpty();
    }

    /** T-Login-5a: signed in successfully with Google, refused here. */
    @Test
    void aDeactivatedMemberIsRefused() {
        members.existing("sub-ben", "Ben", false);
        allowList.add("ben@example.com");

        SignInResult result = service.signIn(google("sub-ben", "ben@example.com", true, "Ben"));

        assertThat(result).isEqualTo(new SignInResult.Refused(SignInResult.Reason.DEACTIVATED));
    }

    /** T-Roles-9a: removing access refuses sign-in and removes nothing. */
    @Test
    void anExistingMemberWhoseAddressWasRemovedFromTheAllowListIsRefused() {
        members.existing("sub-ben", "Ben", true);

        SignInResult result = service.signIn(google("sub-ben", "ben@example.com", true, "Ben"));

        assertThat(result).isEqualTo(new SignInResult.Refused(SignInResult.Reason.NOT_ON_ALLOW_LIST));
        assertThat(members.bySubject).containsKey("sub-ben");
    }

    // ---- The order of the checks ----------------------------------------------------

    @Test
    void verificationIsCheckedFirst() {
        SignInResult result = service.signIn(google("sub-eve", "eve@example.com", false, "Eve"));

        assertThat(result).isEqualTo(new SignInResult.Refused(SignInResult.Reason.EMAIL_NOT_VERIFIED));
    }

    @Test
    void theAllowListIsCheckedBeforeDeactivation() {
        members.existing("sub-ben", "Ben", false);

        SignInResult result = service.signIn(google("sub-ben", "ben@example.com", true, "Ben"));

        assertThat(result).isEqualTo(new SignInResult.Refused(SignInResult.Reason.NOT_ON_ALLOW_LIST));
    }

    // ---- In-memory stand-ins for the database ---------------------------------------

    static class FakeAllowList implements AllowList {
        final Set<String> emails = new HashSet<>();

        void add(String email) {
            emails.add(email);
        }

        @Override
        public boolean contains(String email) {
            return emails.contains(email);
        }
    }

    static class FakeMemberAccounts implements MemberAccounts {
        final Map<String, MemberAccount> bySubject = new HashMap<>();
        final List<NewMemberAccount> created = new ArrayList<>();

        MemberAccount existing(String subject, String name, boolean active) {
            MemberAccount account = new MemberAccount(UUID.randomUUID(), name, DEFAULT_ZONE, Role.FAMILY_MEMBER, active);
            bySubject.put(subject, account);
            return account;
        }

        @Override
        public Optional<MemberAccount> findBySubject(String subject) {
            return Optional.ofNullable(bySubject.get(subject));
        }

        @Override
        public MemberAccount create(NewMemberAccount account) {
            created.add(account);
            MemberAccount member = new MemberAccount(UUID.randomUUID(), account.displayName(),
                    account.timeZone(), account.role(), true);
            bySubject.put(account.subject(), member);
            return member;
        }
    }
}
