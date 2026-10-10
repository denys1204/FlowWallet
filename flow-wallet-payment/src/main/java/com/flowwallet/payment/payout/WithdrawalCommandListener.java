package com.flowwallet.payment.payout;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.WithdrawalRequestedEvent;
import com.flowwallet.payment.provider.stripe.StripeCurrencyRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Accepts the withdrawal commands Wallet Service sends on {@code wallet.withdrawals}. Delivery is at least once, so
 * the unique reference decides a repeat: the same terms are a redelivery and are acknowledged, other terms are a
 * defect. A record that can never become a payout, unreadable or of an invalid shape, is dead-lettered at once;
 * any other failure is retried first.
 * <p>
 * Not transactional: it runs after {@link PayoutAcceptance}'s transaction has committed or rolled back, the only
 * position from which a unique violation can be classified. Only the shape is checked here, never configuration,
 * which {@link PayoutAcceptance} judges. See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawalCommandListener {
    /**
     * A lower-case UUID of any version: the wallet sends its idempotency key and its caller's id case-folded.
     */
    private static final Pattern LOWER_CASE_UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    );

    private static final Pattern CURRENCY = Pattern.compile("[A-Za-z]{3}");

    private final ObjectMapper objectMapper;
    private final PayoutAcceptance acceptance;

    @KafkaListener(topics = KafkaConstants.WALLET_WITHDRAWALS_TOPIC)
    public void onWithdrawalCommand(ConsumerRecord<String, String> record) {
        WithdrawalRequestedEvent command = read(record);
        requireShape(command);

        try {
            acceptance.accept(command);
        } catch (DataIntegrityViolationException e) {
            // The transaction is already rolled back. A fresh one reads what holds the reference. No row means
            // another constraint refused the insert, which a retry may fix, so the violation is rethrown.
            Payout existing = acceptance.findByReference(command.transactionReference()).orElseThrow(() -> e);
            if (!sameTerms(existing, command)) {
                log.error("Withdrawal {} arrived again with other terms", command.transactionReference());
                throw new ConflictingWithdrawalCommandException(command.transactionReference());
            }
            log.info("Withdrawal {} was already accepted; redelivery acknowledged", command.transactionReference());
        }
    }

    private WithdrawalRequestedEvent read(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(KafkaConstants.HEADER_EVENT_TYPE);
        String eventType = header == null || header.value() == null
                ? null
                : new String(header.value(), StandardCharsets.UTF_8);
        if (!KafkaConstants.EVENT_TYPE_WITHDRAWAL_REQUESTED.equals(eventType)) {
            throw new UnreadableWithdrawalCommandException(
                    "Record on " + record.topic() + " is not a withdrawal command"
            );
        }
        if (record.value() == null) {
            throw new UnreadableWithdrawalCommandException("Withdrawal command has no value");
        }
        WithdrawalRequestedEvent command;
        try {
            command = objectMapper.readValue(record.value(), WithdrawalRequestedEvent.class);
        } catch (JacksonException e) {
            throw new UnreadableWithdrawalCommandException("Withdrawal command is not readable JSON", e);
        }
        if (command == null) {
            throw new UnreadableWithdrawalCommandException("Withdrawal command is the JSON literal null");
        }
        return command;
    }

    /**
     * The shape a command must have to become a payout at all. A field the command lacks or an amount it could never
     * carry is a defect in the producer, which no retry changes. The wallet's grid is a copy of Stripe's accepted
     * scale, so a debited amount always passes.
     */
    private void requireShape(WithdrawalRequestedEvent command) {
        if (isBlank(command.eventId())
                || command.transactionReference() == null
                || !LOWER_CASE_UUID.matcher(command.transactionReference()).matches()
                || command.userId() == null
                || !LOWER_CASE_UUID.matcher(command.userId()).matches()
                || command.currency() == null
                || !CURRENCY.matcher(command.currency()).matches()) {
            throw new UnreadableWithdrawalCommandException("Withdrawal command has an incomplete envelope");
        }
        BigDecimal amount = command.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new UnreadableWithdrawalCommandException("Withdrawal command has no positive amount");
        }
        boolean fits = PayoutTerms.fitsIntegerDigits(amount)
                && amount.stripTrailingZeros().scale() <= StripeCurrencyRules.of(command.currency()).acceptedScale();
        if (!fits) {
            throw new UnreadableWithdrawalCommandException("Withdrawal command's amount is off its currency's grid");
        }
    }

    /**
     * The terms Payment Service binds to the reference. The amount is compared with {@code compareTo}, because the
     * stored one comes back from {@code NUMERIC(19,4)} at scale 4.
     */
    private static boolean sameTerms(Payout existing, WithdrawalRequestedEvent command) {
        return existing.getUserId().equals(command.userId())
                && existing.getAmount().compareTo(command.amount()) == 0
                && existing.getCurrency().equals(command.currency().toUpperCase(Locale.ROOT));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
