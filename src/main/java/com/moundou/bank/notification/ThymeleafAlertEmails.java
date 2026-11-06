package com.moundou.bank.notification;

import com.moundou.bank.i18n.Formats;
import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.MemberDirectory;
import com.moundou.bank.ledger.LedgerTransaction;
import com.moundou.bank.notification.NotificationRepository.ClaimedAlert;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.moundou.bank.notification.OutboxWriter.AlertKind.SETTLEMENT_ALERT;

/**
 * Composes the alert emails from the Thymeleaf templates in {@code templates/email}, at
 * send time, in the recipient's locale (ADR-014). The body is HTML.
 *
 * Every alert is written from the recipient's side, with the same sentence the app shows
 * them for that item (the queue's {@code item.toDecide.*} and {@code item.sent.*} keys), and
 * links to the item itself (Notify.Pending-2). Which template:
 * <ul>
 *   <li>pending alert, to the counterparty: what waits for them (Notify.Pending-1);</li>
 *   <li>settlement alert, to the initiator: approved, or declined with the reason if one
 *       was given (Notify.Settlement-1, -2);</li>
 *   <li>cancellation alert, to the counterparty: who cancelled what, and that balances are
 *       unchanged (Notify.Settlement-4).</li>
 * </ul>
 *
 * Anything it cannot do, such as a recipient who is not a party, throws. The worker counts
 * that as a failed try, logs it, and retries within RetryPolicy's limit.
 *
 * Specified by ThymeleafAlertEmailsTests.
 */
@Component
class ThymeleafAlertEmails implements AlertEmails {

    /**
     * Release 1.0 is English for everyone, like the screens (I18N-2). Release 2.0 reads
     * the recipient's stored language here, so a queued alert goes out in the language
     * they have when it is sent.
     */
    static final Locale LOCALE = Locale.ENGLISH;

    private final ITemplateEngine templates;
    private final MessageSource messages;
    private final MemberDirectory members;
    private final Formats formats;
    private final String baseUrl;

    ThymeleafAlertEmails(ITemplateEngine templates, MessageSource messages, MemberDirectory members,
                         Formats formats, NotificationProperties properties) {
        this.templates = templates;
        this.messages = messages;
        this.members = members;
        this.formats = formats;
        String url = properties.baseUrl() == null ? "" : properties.baseUrl().strip();
        this.baseUrl = url.replaceAll("/+$", "");
    }

    @Override
    public EmailMessage compose(ClaimedAlert alert, LedgerTransaction transaction) {
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException("No base URL for alert links: set BASE_URL or RENDER_EXTERNAL_URL");
        }

        // The recipient's side of the row (SRS 4.2), and the other party: for a pending or
        // cancellation alert that is the initiator, for a settlement alert the counterparty.
        UUID recipient = alert.recipientId();
        String side;
        UUID other;
        if (recipient.equals(transaction.creditor())) {
            side = "creditor";
            other = transaction.debtor();
        } else if (recipient.equals(transaction.debtor())) {
            side = "debtor";
            other = transaction.creditor();
        } else {
            throw new IllegalStateException("Alert " + alert.id() + ": its recipient is not a party to "
                    + transaction.id());
        }

        String to = members.emailOf(recipient)
                .orElseThrow(() -> new IllegalStateException("No email address for member " + recipient));
        String otherName = nameOf(other);
        String outcome = alert.kind() == SETTLEMENT_ALERT ? outcomeOf(alert, transaction) : null;

        String template = switch (alert.kind()) {
            case PENDING_ALERT -> "email/pending-alert";
            case SETTLEMENT_ALERT -> "email/settlement-alert";
            case CANCELLATION_ALERT -> "email/cancellation-alert";
        };
        String subjectKey = switch (alert.kind()) {
            case PENDING_ALERT -> "email.pending.subject";
            case SETTLEMENT_ALERT -> "email.settlement.subject." + outcome;
            case CANCELLATION_ALERT -> "email.cancellation.subject";
        };
        String subject = messages.getMessage(subjectKey, new Object[] {otherName}, LOCALE);

        Map<String, Object> model = new HashMap<>();
        model.put("subject", subject);
        model.put("recipientName", nameOf(recipient));
        model.put("otherName", otherName);
        model.put("kind", transaction.kind().dbValue());
        model.put("side", side);
        model.put("amount", formats.amount(transaction.amountMinor(), transaction.currency(), LOCALE));
        model.put("date", formats.date(transaction.transactionDate(), LOCALE));
        model.put("note", blankToNull(transaction.note()));
        model.put("outcome", outcome);
        model.put("reason", blankToNull(transaction.declineReason()));
        model.put("link", baseUrl + "/pending/" + transaction.id());

        return new EmailMessage(to, subject, templates.process(template, new Context(LOCALE, model)));
    }

    /** A settlement alert is written on approval or decline, so its item is one or the other. */
    private static String outcomeOf(ClaimedAlert alert, LedgerTransaction transaction) {
        return switch (transaction.status()) {
            case APPROVED -> "approved";
            case DECLINED -> "declined";
            default -> throw new IllegalStateException("Settlement alert " + alert.id() + " for an item that is "
                    + transaction.status().dbValue());
        };
    }

    /** A deactivated member keeps their name on old records (Auth.Roles-7), so they are found too. */
    private String nameOf(UUID memberId) {
        return members.findById(memberId)
                .map(Member::displayName)
                .orElseThrow(() -> new IllegalStateException("No member " + memberId));
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
