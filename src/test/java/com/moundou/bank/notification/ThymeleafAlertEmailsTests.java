package com.moundou.bank.notification;

import com.moundou.bank.i18n.Formats;
import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.MemberDirectory;
import com.moundou.bank.ledger.LedgerTransaction;
import com.moundou.bank.ledger.TransactionKind;
import com.moundou.bank.ledger.TransactionStatus;
import com.moundou.bank.notification.NotificationRepository.ClaimedAlert;
import com.moundou.bank.notification.OutboxWriter.AlertKind;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import static com.moundou.bank.ledger.TransactionKind.COMPENSATING;
import static com.moundou.bank.ledger.TransactionKind.LOAN;
import static com.moundou.bank.ledger.TransactionKind.REPAYMENT;
import static com.moundou.bank.ledger.TransactionStatus.APPROVED;
import static com.moundou.bank.ledger.TransactionStatus.CANCELLED;
import static com.moundou.bank.ledger.TransactionStatus.DECLINED;
import static com.moundou.bank.ledger.TransactionStatus.PENDING;
import static com.moundou.bank.notification.OutboxWriter.AlertKind.CANCELLATION_ALERT;
import static com.moundou.bank.notification.OutboxWriter.AlertKind.PENDING_ALERT;
import static com.moundou.bank.notification.OutboxWriter.AlertKind.SETTLEMENT_ALERT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fast suite. The alert emails' content (Notify.Pending-1, -2, Notify.Settlement-1, -2,
 * -4; ADR-014). This is the specification for ThymeleafAlertEmails.
 *
 * Real templates from templates/email and the real messages.properties, rendered by a
 * Thymeleaf engine set up the way Spring Boot sets up the application's. Only the member
 * directory is a stand-in. Assertions read the email as a person would, as text, through
 * Jsoup, so HTML escaping (an apostrophe becomes &#39;) does not get in the way.
 *
 * Ama lends to Ben throughout, unless a test says otherwise.
 */
class ThymeleafAlertEmailsTests {

    static final UUID AMA = UUID.randomUUID();
    static final UUID BEN = UUID.randomUUID();
    static final Currency USD = Currency.getInstance("USD");
    static final Currency XAF = Currency.getInstance("XAF");
    static final LocalDate DATE = LocalDate.of(2026, 9, 1);
    static final String BASE_URL = "https://bank.example";

    private MemberDirectory members;
    private ThymeleafAlertEmails emails;

    @BeforeEach
    void twoMembers() {
        members = mock(MemberDirectory.class);
        when(members.findById(AMA)).thenReturn(Optional.of(new Member(AMA, "Ama", ZoneId.of("Africa/Douala"), true)));
        when(members.findById(BEN)).thenReturn(Optional.of(new Member(BEN, "Ben", ZoneId.of("Europe/Paris"), true)));
        when(members.emailOf(AMA)).thenReturn(Optional.of("ama@example.com"));
        when(members.emailOf(BEN)).thenReturn(Optional.of("ben@example.com"));
        emails = withBaseUrl(BASE_URL);
    }

    @Nested
    class PendingAlerts {

        @Test
        void tellsTheBorrowerWhatWaitsForThemInTheQueuesWords() {                  // T-NPend-1a
            LedgerTransaction loan = loan(PENDING).withNote("Dinner at Mama's").build();

            EmailMessage email = emails.compose(alert(PENDING_ALERT, BEN, loan), loan);

            assertThat(email.to()).isEqualTo("ben@example.com");
            assertThat(email.subject()).isEqualTo("Ama needs you to approve a record");
            assertThat(text(email)).contains(
                    "Hi Ben,",
                    "Ama says they lent you $20.00",
                    "Date: Sep 1, 2026",
                    "Note: Dinner at Mama's",
                    "Nothing changes in your balances until you approve it.");
        }

        @Test
        void linksStraightToTheItemNotTheHomePage() {                            // T-NPend-2a
            LedgerTransaction loan = loan(PENDING).build();

            EmailMessage email = emails.compose(alert(PENDING_ALERT, BEN, loan), loan);

            assertThat(html(email).select("a")).singleElement().satisfies(link -> {
                assertThat(link.attr("href")).isEqualTo(BASE_URL + "/pending/" + loan.id());
                assertThat(link.text()).isEqualTo("Approve or decline it");
            });
        }

        @Test
        void aRepaymentIsDescribedFromTheLendersSide() {                          // T-NPend-1a, D2
            // On a repayment the borrower is the creditor and the lender the debtor (D2).
            LedgerTransaction repayment = new Row(REPAYMENT, BEN, AMA, BEN, PENDING).build();

            EmailMessage email = emails.compose(alert(PENDING_ALERT, AMA, repayment), repayment);

            assertThat(email.to()).isEqualTo("ama@example.com");
            assertThat(email.subject()).isEqualTo("Ben needs you to approve a record");
            assertThat(text(email)).contains("Ben says they repaid you $20.00");
        }

        @Test
        void aCorrectionSaysWhoseFavourItIsIn() {                                 // T-NPend-1b
            // Ama undoes her loan to Ben: the correction swaps the loan's parties.
            LedgerTransaction correction = new Row(COMPENSATING, BEN, AMA, AMA, PENDING).build();

            EmailMessage email = emails.compose(alert(PENDING_ALERT, BEN, correction), correction);

            assertThat(text(email)).contains("Ama asks to undo an earlier record, in your favour: $20.00");
        }

        @Test
        void hasNoNoteLineWhenThereIsNoNote() {
            LedgerTransaction loan = loan(PENDING).withNote("  ").build();

            assertThat(text(emails.compose(alert(PENDING_ALERT, BEN, loan), loan))).doesNotContain("Note:");
        }

        @Test
        void showsCfaFrancsWithoutDecimals() {                                    // I18N-3, ADR-013
            LedgerTransaction loan = loan(PENDING).inCurrency(10_000, XAF).build();

            String text = text(emails.compose(alert(PENDING_ALERT, BEN, loan), loan));

            assertThat(text).contains("10,000").doesNotContain("10,000.");
        }
    }

    @Nested
    class SettlementAlerts {

        @Test
        void anApprovalTellsTheInitiator() {                                     // T-NSettle-1a
            LedgerTransaction loan = loan(APPROVED).build();

            EmailMessage email = emails.compose(alert(SETTLEMENT_ALERT, AMA, loan), loan);

            assertThat(email.to()).isEqualTo("ama@example.com");
            assertThat(email.subject()).isEqualTo("Ben approved your record");
            assertThat(text(email)).contains(
                    "Hi Ama,",
                    "You lent Ben $20.00",
                    "Ben approved it. It now counts in your balances.");
            assertThat(html(email).select("a").attr("href")).isEqualTo(BASE_URL + "/pending/" + loan.id());
        }

        @Test
        void aDeclineCarriesTheReason() {                                        // T-NSettle-1a, T-NSettle-2a
            LedgerTransaction loan = loan(DECLINED).withDeclineReason("Not mine").build();

            EmailMessage email = emails.compose(alert(SETTLEMENT_ALERT, AMA, loan), loan);

            assertThat(email.subject()).isEqualTo("Ben declined your record");
            assertThat(text(email)).contains(
                    "Ben declined it. Nothing changes in your balances.",
                    "Their reason: Not mine");
        }

        @Test
        void aDeclineWithoutAReasonIsStillWellFormed() {                         // T-NSettle-2a
            LedgerTransaction loan = loan(DECLINED).build();

            String text = text(emails.compose(alert(SETTLEMENT_ALERT, AMA, loan), loan));

            assertThat(text).contains("Ben declined it.").doesNotContain("reason").doesNotContain("null");
        }

        @Test
        void isRefusedForAnItemThatWasNotApprovedOrDeclined() {
            // Settlement alerts are written by approve and decline only, so this is a bug
            // somewhere else; refusing makes the worker log it and retry, then give up.
            LedgerTransaction loan = loan(PENDING).build();

            assertThatThrownBy(() -> emails.compose(alert(SETTLEMENT_ALERT, AMA, loan), loan))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("pending");
        }
    }

    @Nested
    class CancellationAlerts {

        @Test
        void namesTheInitiatorAndTheItemSaysBalancesAreUnchangedAndLinksToIt() { // T-NSettle-4a
            LedgerTransaction loan = loan(CANCELLED).build();

            EmailMessage email = emails.compose(alert(CANCELLATION_ALERT, BEN, loan), loan);

            assertThat(email.to()).isEqualTo("ben@example.com");
            assertThat(email.subject()).isEqualTo("Ama cancelled a record waiting for you");
            assertThat(text(email)).contains(
                    "Ama says they lent you $20.00",
                    "Ama cancelled it, so there is nothing for you to approve.",
                    "Your balances are unchanged.");
            assertThat(html(email).select("a").attr("href")).isEqualTo(BASE_URL + "/pending/" + loan.id());
        }
    }

    @Nested
    class Safety {

        @Test
        void whatAMemberTypedIsShownAsTextNeverAsHtml() {
            LedgerTransaction loan = loan(DECLINED)
                    .withNote("<b>Dinner</b> & drinks")
                    .withDeclineReason("<a href=\"https://evil.example\">click</a>")
                    .build();

            EmailMessage pending = emails.compose(alert(PENDING_ALERT, BEN, loan), loan);
            EmailMessage declined = emails.compose(alert(SETTLEMENT_ALERT, AMA, loan), loan);

            assertThat(html(pending).select("b")).isEmpty();
            assertThat(text(pending)).contains("Note: <b>Dinner</b> & drinks");
            assertThat(html(declined).select("a")).as("only the app's own link").hasSize(1);
            assertThat(text(declined)).contains("Their reason: <a href=\"https://evil.example\">click</a>");
        }

        @Test
        void everyKindOfItemRendersFromEitherSideWithNoMissingMessage() {
            // Thymeleaf shows a missing message as ??key_locale??, so none may appear.
            for (TransactionKind kind : TransactionKind.values()) {
                for (UUID recipient : new UUID[] {AMA, BEN}) {
                    check(PENDING_ALERT, new Row(kind, AMA, BEN, other(recipient), PENDING), recipient);
                    check(CANCELLATION_ALERT, new Row(kind, AMA, BEN, other(recipient), CANCELLED), recipient);
                    check(SETTLEMENT_ALERT, new Row(kind, AMA, BEN, recipient, APPROVED), recipient);
                    check(SETTLEMENT_ALERT, new Row(kind, AMA, BEN, recipient, DECLINED).withDeclineReason("No"), recipient);
                }
            }
        }

        private void check(AlertKind alertKind, Row row, UUID recipient) {
            LedgerTransaction transaction = row.build();
            EmailMessage email = emails.compose(alert(alertKind, recipient, transaction), transaction);
            assertThat(email.subject() + email.body())
                    .as(alertKind + " / " + transaction.kind() + " / " + transaction.status() + " to " + recipient)
                    .doesNotContain("??").doesNotContain("{0}").doesNotContain("{1}");
        }

        @Test
        void isRefusedWhenTheRecipientIsNotAParty() {
            LedgerTransaction loan = loan(PENDING).build();
            UUID stranger = UUID.randomUUID();

            assertThatThrownBy(() -> emails.compose(alert(PENDING_ALERT, stranger, loan), loan))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a party");
        }

        @Test
        void linksHaveOneSlashWhateverTheBaseUrlEndsWith() {
            LedgerTransaction loan = loan(PENDING).build();

            EmailMessage email = withBaseUrl("https://bank.example/ ").compose(alert(PENDING_ALERT, BEN, loan), loan);

            assertThat(html(email).select("a").attr("href")).isEqualTo("https://bank.example/pending/" + loan.id());
        }

        @Test
        void withNoBaseUrlNoAlertCanBeComposed() {
            // Sending a link that goes nowhere would be worse: the alert fails, is logged
            // with this message, and is retried once BASE_URL is set.
            LedgerTransaction loan = loan(PENDING).build();

            for (String missing : new String[] {null, "", "  "}) {
                assertThatThrownBy(() -> withBaseUrl(missing).compose(alert(PENDING_ALERT, BEN, loan), loan))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("BASE_URL");
            }
        }
    }

    // ---- building blocks ---------------------------------------------------------------

    /** The engine and messages as Spring Boot sets them up from application.yml. */
    private ThymeleafAlertEmails withBaseUrl(String baseUrl) {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(messages);

        return new ThymeleafAlertEmails(engine, messages, members, new Formats(), new NotificationProperties(baseUrl));
    }

    private static UUID other(UUID member) {
        return member.equals(AMA) ? BEN : AMA;
    }

    private static ClaimedAlert alert(AlertKind kind, UUID recipient, LedgerTransaction transaction) {
        return new ClaimedAlert(UUID.randomUUID(), transaction.id(), recipient, kind, 1);
    }

    /** Ama lends Ben $20.00, recorded by Ama. */
    private static Row loan(TransactionStatus status) {
        return new Row(LOAN, AMA, BEN, AMA, status);
    }

    private static String text(EmailMessage email) {
        return html(email).text();
    }

    private static Document html(EmailMessage email) {
        return Jsoup.parse(email.body());
    }

    /** One transaction row, built by hand: nothing touches a database. */
    static final class Row {
        private final TransactionKind kind;
        private final UUID creditor;
        private final UUID debtor;
        private final UUID initiatedBy;
        private final TransactionStatus status;
        private long amountMinor = 2000;
        private Currency currency = USD;
        private String note;
        private String declineReason;

        Row(TransactionKind kind, UUID creditor, UUID debtor, UUID initiatedBy, TransactionStatus status) {
            this.kind = kind;
            this.creditor = creditor;
            this.debtor = debtor;
            this.initiatedBy = initiatedBy;
            this.status = status;
        }

        Row withNote(String text) {
            note = text;
            return this;
        }

        Row withDeclineReason(String text) {
            declineReason = text;
            return this;
        }

        Row inCurrency(long amount, Currency in) {
            amountMinor = amount;
            currency = in;
            return this;
        }

        LedgerTransaction build() {
            return new LedgerTransaction(
                    UUID.randomUUID(), kind, creditor, debtor, amountMinor, currency, DATE, note, status,
                    declineReason, initiatedBy, Instant.parse("2026-09-01T10:00:00Z"),
                    status == PENDING ? null : Instant.parse("2026-09-02T10:00:00Z"),
                    kind == REPAYMENT ? UUID.randomUUID() : null,
                    kind == COMPENSATING ? UUID.randomUUID() : null,
                    false, UUID.randomUUID());
        }
    }
}
