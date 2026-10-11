package com.flowwallet.payment.payout;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.payment.config.WithdrawalCommandConsumerConfig;
import com.flowwallet.payment.support.PaymentIntegrationTest;
import com.flowwallet.payment.support.PayoutRows;
import com.flowwallet.payment.support.StripeFixtures;
import com.flowwallet.payment.support.StripeSignatures;
import com.flowwallet.payment.support.WireSample;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.Payout;
import com.stripe.param.PayoutCreateParams;
import com.stripe.param.TransferCreateParams;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Each payout state reached through the real code: the command on wallet.withdrawals, its acceptance, the executor's
 * steps against a stubbed Stripe, the signed Connect webhook, the row's guarded transitions and the events the outbox
 * sends, checked against the golden samples the wallet's tests publish.
 * <p>
 * Each test starts with {@code payouts} empty, because {@link PayoutExecutor#run()} works every due payout; see
 * {@link PayoutRows}.
 */
class PayoutStatesIT extends PaymentIntegrationTest {
    private static final String CONNECT_WEBHOOK_PATH = "/api/payments/webhooks/stripe/connect";

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private PayoutExecutor executor;

    @Autowired
    private PayoutProperties properties;

    @Autowired
    private PayoutReconciler reconciler;

    @BeforeEach
    void startWithNoPayouts() {
        PayoutRows.clearOnceQuiet(jdbc, kafkaProbe, consumerGroup);
    }

    @Test
    void aWithdrawalCommandIsAcceptedAsOnePayoutAndItsRedeliveriesAddNoSecond() throws Exception {
        // Guards a withdrawal paid out twice: the wallet's outbox delivers at least once, and only the unique reference
        // (payouts_reference_key) keeps a redelivered command, under its own event id or a new one, from becoming a
        // second payout. A redelivery is acknowledged, not dead-lettered.
        String userId = newUserId();
        String reference = newKey();
        WireSample command = command(reference, userId);

        send(command, reference);

        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("ACCEPTED");
        assertThat(PayoutRows.column(jdbc, reference, "user_id")).isEqualTo(userId);
        assertThat(new BigDecimal(PayoutRows.column(jdbc, reference, "amount"))).isEqualByComparingTo("10.00");
        assertThat(PayoutRows.column(jdbc, reference, "currency")).isEqualTo("PLN");
        long deadLetters = kafkaProbe.recordCount(WithdrawalCommandConsumerConfig.DEAD_LETTER_TOPIC);

        send(command, reference);
        send(command(reference, userId), reference);

        assertThat(payoutRows(reference)).isOne();
        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("ACCEPTED");
        assertThat(kafkaProbe.recordCount(WithdrawalCommandConsumerConfig.DEAD_LETTER_TOPIC)).isEqualTo(deadLetters);
    }

    @Test
    void aCommandThatReusesAnAcceptedReferenceWithOtherTermsIsDeadLetteredAndChangesNothing() throws Exception {
        // Guards a payout whose terms a second command overwrites, or a conflict swallowed as a redelivery: the same
        // reference with another amount is a defect in the producer, kept on the dead-letter topic for an operator.
        String userId = newUserId();
        String reference = newKey();
        send(command(reference, userId), reference);
        Map<TopicPartition, Long> start = kafkaProbe.endOffsets(WithdrawalCommandConsumerConfig.DEAD_LETTER_TOPIC);

        send(command(reference, userId).with("amount", new BigDecimal("12.0000")), reference);

        kafkaProbe.awaitRecord(start, r -> reference.equals(r.key()));
        assertThat(payoutRows(reference)).isOne();
        assertThat(new BigDecimal(PayoutRows.column(jdbc, reference, "amount"))).isEqualByComparingTo("10.00");
        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("ACCEPTED");
    }

    @Test
    void aCommandInACurrencyThePayoutTermsRefuseIsStoredReturnedWithItsReturnedEvent() throws Exception {
        // Guards debited money that never comes back: a command the terms no longer allow must not be dead-lettered
        // or dropped, but stored RETURNED with its PayoutReturnedEvent in the same transaction (ADR 0034), and the
        // executor must leave it alone.
        String userId = newUserId();
        String reference = newKey();
        Map<TopicPartition, Long> start = kafkaProbe.endOffsets(KafkaConstants.PAYMENT_EVENTS_TOPIC);

        send(command(reference, userId).with("currency", "EUR"), reference);

        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("RETURNED");
        assertThat(PayoutRows.column(jdbc, reference, "reason_code")).isEqualTo("TERMS_REFUSED");
        ConsumerRecord<String, String> record = awaitEvent(start, reference, KafkaConstants.EVENT_TYPE_PAYOUT_RETURNED);
        WireSample.of("payout-returned").assertSameShape(record.value());
        JsonNode event = json.readTree(record.value());
        assertThat(event.get("transactionReference").asString()).isEqualTo(reference);
        assertThat(event.get("userId").asString()).isEqualTo(userId);
        assertThat(event.get("reasonCode").asString()).isEqualTo("TERMS_REFUSED");
        assertThat(event.get("amount").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(event.get("currency").asString()).isEqualTo("EUR");
        assertThat(event.get("providerPayoutId").isNull()).isTrue();

        executor.run();

        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("RETURNED");
        assertThat(PayoutRows.outboxRows(jdbc, reference, KafkaConstants.EVENT_TYPE_PAYOUT_RETURNED)).isOne();
        verify(stripeClient, never()).createTransfer(any(), any());
    }

    @Test
    void theExecutorTransfersToTheConnectedAccountThenPaysItOutAndRecordsStripesIds() throws Exception {
        // Guards the two steps that send money: a transfer or payout made with other terms or keys than the row's, an
        // id left unrecorded (which would leave the webhook nothing to settle by), or a step that sends an event the
        // wallet would read as an outcome.
        String userId = newUserId();
        String reference = newKey();
        PayoutRows.ReadyAccount account = PayoutRows.seedReadyAccount(jdbc, userId);
        send(command(reference, userId), reference);

        Created created = createPayout(reference, account);

        ArgumentCaptor<TransferCreateParams> transfer = ArgumentCaptor.forClass(TransferCreateParams.class);
        verify(stripeClient).createTransfer(transfer.capture(), eq(transferKey(reference)));
        assertThat(transfer.getValue().getAmount()).isEqualTo(1000L);
        assertThat(transfer.getValue().getCurrency()).isEqualTo("pln");
        assertThat(transfer.getValue().getDestination()).isEqualTo(account.stripeAccountId());
        assertThat(transfer.getValue().getTransferGroup()).isEqualTo("wd-" + reference);
        ArgumentCaptor<PayoutCreateParams> payout = ArgumentCaptor.forClass(PayoutCreateParams.class);
        verify(stripeClient).createPayout(payout.capture(), eq(payoutKey(reference)), eq(account.stripeAccountId()));
        assertThat(payout.getValue().getAmount()).isEqualTo(1000L);
        assertThat(payout.getValue().getCurrency()).isEqualTo("pln");
        assertThat(PayoutRows.column(jdbc, reference, "stripe_account_id")).isEqualTo(account.stripeAccountId());
        assertThat(PayoutRows.column(jdbc, reference, "stripe_transfer_id")).isEqualTo(created.transferId());
        assertThat(PayoutRows.column(jdbc, reference, "stripe_payout_id")).isEqualTo(created.payoutId());
        assertThat(PayoutRows.column(jdbc, reference, "transferred_at")).isNotNull();
        assertThat(eventRows(reference)).isZero();
    }

    @Test
    void aSignedPaidWebhookSettlesThePayoutFromStripesPayoutAndSendsOnePaidEvent() throws Exception {
        // Guards a payout settled from the webhook's own data, which Stripe does not order (ADR 0036), a PAID row
        // without its PayoutPaidEvent, or a redelivered webhook that sends a second one.
        String userId = newUserId();
        String reference = newKey();
        PayoutRows.ReadyAccount account = PayoutRows.seedReadyAccount(jdbc, userId);
        send(command(reference, userId), reference);
        Created created = createPayout(reference, account);
        doReturn(payout(created.payoutId(), "paid", reference, account, null))
                .when(stripeClient).retrievePayout(created.payoutId(), account.stripeAccountId());
        Map<TopicPartition, Long> start = kafkaProbe.endOffsets(KafkaConstants.PAYMENT_EVENTS_TOPIC);
        // The body still says in_transit: only the payout Stripe returns now may decide.
        String webhook = connectEvent("payout.paid", account, created.payoutId(), "in_transit");

        postConnectWebhook(webhook);

        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("PAID");
        assertThat(PayoutRows.column(jdbc, reference, "paid_at")).isNotNull();
        ConsumerRecord<String, String> record = awaitEvent(start, reference, KafkaConstants.EVENT_TYPE_PAYOUT_PAID);
        WireSample.of("payout-paid").assertSameShape(record.value());
        JsonNode event = json.readTree(record.value());
        assertThat(event.get("transactionReference").asString()).isEqualTo(reference);
        assertThat(event.get("providerPayoutId").asString()).isEqualTo(created.payoutId());
        assertThat(event.get("userId").asString()).isEqualTo(userId);
        assertThat(event.get("amount").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(event.get("currency").asString()).isEqualTo("PLN");

        postConnectWebhook(webhook);

        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("PAID");
        assertThat(PayoutRows.outboxRows(jdbc, reference, KafkaConstants.EVENT_TYPE_PAYOUT_PAID)).isOne();
    }

    @Test
    void aCreatedPayoutWhoseWebhookNeverCameIsSettledPaidByTheReconcilerOnceItIsOldEnough() throws Exception {
        // Guards a withdrawal paid at the bank that the wallet never hears of when the Connect webhook is lost
        // (ADR 0036): the reconciler must leave a payout younger than created-min-age to its webhook, then ask Stripe
        // about an older one and settle it through the webhook's own path, with one PayoutPaidEvent, and not ask again
        // once it is paid.
        String userId = newUserId();
        String reference = newKey();
        PayoutRows.ReadyAccount account = PayoutRows.seedReadyAccount(jdbc, userId);
        send(command(reference, userId), reference);
        Created created = createPayout(reference, account);
        doReturn(payout(created.payoutId(), "paid", reference, account, null))
                .when(stripeClient).retrievePayout(created.payoutId(), account.stripeAccountId());

        reconciler.reconcile();

        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("PAYOUT_CREATED");
        assertThat(PayoutRows.column(jdbc, reference, "last_reconciled_at")).isNull();
        verify(stripeClient, never()).retrievePayout(any(), any());

        // An hour back, past created-min-age (15 minutes in integration-test.properties). Relative, so the shift does
        // not depend on how the session's time zone reads the TIMESTAMP column.
        jdbc.update(
                "UPDATE payouts SET created_at = created_at - interval '1 hour' WHERE transaction_reference = ?",
                reference
        );
        Map<TopicPartition, Long> start = kafkaProbe.endOffsets(KafkaConstants.PAYMENT_EVENTS_TOPIC);

        reconciler.reconcile();

        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("PAID");
        assertThat(PayoutRows.column(jdbc, reference, "paid_at")).isNotNull();
        assertThat(PayoutRows.column(jdbc, reference, "last_reconciled_at")).isNotNull();
        ConsumerRecord<String, String> record = awaitEvent(start, reference, KafkaConstants.EVENT_TYPE_PAYOUT_PAID);
        WireSample.of("payout-paid").assertSameShape(record.value());
        JsonNode event = json.readTree(record.value());
        assertThat(event.get("transactionReference").asString()).isEqualTo(reference);
        assertThat(event.get("providerPayoutId").asString()).isEqualTo(created.payoutId());
        assertThat(event.get("userId").asString()).isEqualTo(userId);
        assertThat(event.get("amount").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(event.get("currency").asString()).isEqualTo("PLN");

        reconciler.reconcile();

        verify(stripeClient, times(1)).retrievePayout(created.payoutId(), account.stripeAccountId());
        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("PAID");
        assertThat(PayoutRows.outboxRows(jdbc, reference, KafkaConstants.EVENT_TYPE_PAYOUT_PAID)).isOne();
    }

    @Test
    void aFailedPayoutIsReversedAndReturnedWithItsEventsAndItsBankAccountIsSetBack() throws Exception {
        // Guards money lost between the bank and the wallet: a failed payout must start the reversal with its
        // PayoutFailedEvent, the executor must reverse the transfer and only then send the PayoutReturnedEvent that
        // credits the wallet, and the bank account the payout failed at must stop taking payouts (ADR 0036).
        String userId = newUserId();
        String reference = newKey();
        PayoutRows.ReadyAccount account = PayoutRows.seedReadyAccount(jdbc, userId);
        send(command(reference, userId), reference);
        Created created = createPayout(reference, account);
        Payout failed = payout(created.payoutId(), "failed", reference, account, "account_closed");
        doReturn(failed).when(stripeClient).retrievePayout(created.payoutId(), account.stripeAccountId());
        Map<TopicPartition, Long> start = kafkaProbe.endOffsets(KafkaConstants.PAYMENT_EVENTS_TOPIC);

        postConnectWebhook(connectEvent("payout.failed", account, created.payoutId(), "failed"));

        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("REVERSING");
        assertThat(PayoutRows.column(jdbc, reference, "reason_code")).isEqualTo("PAYOUT_FAILED");
        assertThat(PayoutRows.column(jdbc, reference, "failure_code")).isEqualTo("account_closed");
        assertThat(PayoutRows.bankState(jdbc, account.stripeAccountId())).isEqualTo("NOT_SET");
        ConsumerRecord<String, String> failedRecord =
                awaitEvent(start, reference, KafkaConstants.EVENT_TYPE_PAYOUT_FAILED);
        WireSample.of("payout-failed").assertSameShape(failedRecord.value());
        JsonNode failedEvent = json.readTree(failedRecord.value());
        assertThat(failedEvent.get("providerPayoutId").asString()).isEqualTo(created.payoutId());
        assertThat(failedEvent.get("reasonCode").asString()).isEqualTo("PAYOUT_FAILED");
        assertThat(failedEvent.get("providerFailureCode").asString()).isEqualTo("account_closed");
        assertThat(PayoutRows.outboxRows(jdbc, reference, KafkaConstants.EVENT_TYPE_PAYOUT_RETURNED)).isZero();

        String reversalId = StripeFixtures.fakeId("trr");
        doReturn(List.of(failed)).when(stripeClient).listPayouts(any(), eq(account.stripeAccountId()));
        doReturn(StripeFixtures.transfer(created.transferId(), account.stripeAccountId(), 1000, "pln", reference))
                .when(stripeClient).retrieveTransfer(created.transferId());
        doReturn(StripeFixtures.transferReversal(reversalId, 1000, "pln", reference))
                .when(stripeClient).createReversal(any(), any(), eq("wd-reversal-" + reference + "-0"));

        executor.run();

        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("RETURNED");
        assertThat(PayoutRows.column(jdbc, reference, "stripe_reversal_id")).isEqualTo(reversalId);
        ConsumerRecord<String, String> returnedRecord =
                awaitEvent(start, reference, KafkaConstants.EVENT_TYPE_PAYOUT_RETURNED);
        WireSample.of("payout-returned").assertSameShape(returnedRecord.value());
        JsonNode returnedEvent = json.readTree(returnedRecord.value());
        assertThat(returnedEvent.get("providerPayoutId").asString()).isEqualTo(created.payoutId());
        assertThat(returnedEvent.get("reasonCode").asString()).isEqualTo("PAYOUT_FAILED");
        assertThat(returnedEvent.get("userId").asString()).isEqualTo(userId);
        assertThat(PayoutRows.outboxRows(jdbc, reference, KafkaConstants.EVENT_TYPE_PAYOUT_FAILED)).isOne();
        assertThat(PayoutRows.outboxRows(jdbc, reference, KafkaConstants.EVENT_TYPE_PAYOUT_RETURNED)).isOne();
    }

    @Test
    void aTransferStripeRefusesForLackOfPlatformFundsIsReturnedWithoutAReversal() throws Exception {
        // Guards a definite refusal treated as unknown, which would hold the wallet's money until an operator came,
        // or a return that names the wrong reason: balance_insufficient on a 400 is on the transfer's refusal list,
        // so nothing left the platform and the payout is returned at once.
        String userId = newUserId();
        String reference = newKey();
        PayoutRows.ReadyAccount account = PayoutRows.seedReadyAccount(jdbc, userId);
        send(command(reference, userId), reference);
        doThrow(new InvalidRequestException(
                "Insufficient funds in the integration test's platform balance",
                null,
                "req_integration-test",
                "balance_insufficient",
                400,
                null
        )).when(stripeClient).createTransfer(any(), eq(transferKey(reference)));
        Map<TopicPartition, Long> start = kafkaProbe.endOffsets(KafkaConstants.PAYMENT_EVENTS_TOPIC);

        executor.run();

        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("RETURNED");
        assertThat(PayoutRows.column(jdbc, reference, "reason_code")).isEqualTo("PLATFORM_FUNDS_SHORT");
        assertThat(PayoutRows.column(jdbc, reference, "stripe_transfer_id")).isNull();
        assertThat(PayoutRows.bankState(jdbc, account.stripeAccountId())).isEqualTo("READY");
        ConsumerRecord<String, String> record = awaitEvent(start, reference, KafkaConstants.EVENT_TYPE_PAYOUT_RETURNED);
        WireSample.of("payout-returned").assertSameShape(record.value());
        JsonNode event = json.readTree(record.value());
        assertThat(event.get("reasonCode").asString()).isEqualTo("PLATFORM_FUNDS_SHORT");
        assertThat(event.get("providerPayoutId").isNull()).isTrue();
        assertThat(PayoutRows.outboxRows(jdbc, reference, KafkaConstants.EVENT_TYPE_PAYOUT_FAILED)).isZero();
    }

    @Test
    void aTransferWhoseOutcomeStaysUnknownUpToTheLimitIsHandedToAnOperatorWithoutAnEvent() throws Exception {
        // Guards an unknown outcome turned into a return, which would credit the wallet for money Stripe may have
        // moved, or an endless loop: every try keeps the key generation and looks for the transfer first, and the
        // last allowed try hands the row to NEEDS_ATTENTION with no event (ADR 0035).
        String userId = newUserId();
        String reference = newKey();
        PayoutRows.seedReadyAccount(jdbc, userId);
        send(command(reference, userId), reference);
        doThrow(new ApiConnectionException("Read timed out (integration test)"))
                .when(stripeClient).createTransfer(any(), eq(transferKey(reference)));
        doReturn(List.of()).when(stripeClient).listTransfers(any());
        int limit = properties.getExecutor().getUnknownAttempts();

        for (int attempt = 1; attempt < limit; attempt++) {
            executor.run();

            assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("ACCEPTED");
            assertThat(PayoutRows.column(jdbc, reference, "attempts")).isEqualTo(Integer.toString(attempt));
            PayoutRows.makeDue(jdbc, reference);
        }
        executor.run();

        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("NEEDS_ATTENTION");
        assertThat(PayoutRows.column(jdbc, reference, "attention_from")).isEqualTo("ACCEPTED");
        assertThat(PayoutRows.column(jdbc, reference, "attention_cause")).isEqualTo("UNKNOWN_LIMIT");
        assertThat(PayoutRows.column(jdbc, reference, "key_gen")).isEqualTo("0");
        verify(stripeClient, times(limit)).createTransfer(any(), eq(transferKey(reference)));
        verify(stripeClient, times(limit - 1)).listTransfers(any());
        assertThat(eventRows(reference)).isZero();
    }

    /**
     * The Stripe ids of a payout driven to {@code PAYOUT_CREATED}.
     */
    private record Created(String transferId, String payoutId) {
    }

    /**
     * Drives an accepted payout through the transfer step and the payout step, one {@code run()} each, as the
     * scheduler would.
     */
    private Created createPayout(String reference, PayoutRows.ReadyAccount account) throws Exception {
        String transferId = StripeFixtures.fakeId("tr");
        doReturn(StripeFixtures.transfer(transferId, account.stripeAccountId(), 1000, "pln", reference))
                .when(stripeClient).createTransfer(any(), eq(transferKey(reference)));
        executor.run();
        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("TRANSFERRED");

        String payoutId = StripeFixtures.fakeId("po");
        doReturn(payout(payoutId, "pending", reference, account, null))
                .when(stripeClient).createPayout(any(), eq(payoutKey(reference)), eq(account.stripeAccountId()));
        executor.run();
        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("PAYOUT_CREATED");
        return new Created(transferId, payoutId);
    }

    private static WireSample command(String reference, String userId) {
        return WireSample.of("withdrawal-requested")
                .with("eventId", UUID.randomUUID().toString())
                .with("transactionReference", reference)
                .with("userId", userId);
    }

    /**
     * Publishes the command as the wallet's outbox does and waits until the consumer group is past it.
     */
    private void send(WireSample command, String reference) {
        kafkaProbe.awaitConsumed(consumerGroup, kafkaProbe.publish(
                KafkaConstants.WALLET_WITHDRAWALS_TOPIC,
                reference,
                KafkaConstants.EVENT_TYPE_WITHDRAWAL_REQUESTED,
                command.json()
        ));
    }

    private static Payout payout(
            String payoutId,
            String status,
            String reference,
            PayoutRows.ReadyAccount account,
            String failureCode
    ) {
        return StripeFixtures.payout(payoutId, status, 1000, "pln", reference, account.bankAccountId(), failureCode);
    }

    private static String connectEvent(String type, PayoutRows.ReadyAccount account, String payoutId, String status) {
        return StripeFixtures.connectPayoutEvent(
                StripeFixtures.fakeId("evt"),
                type,
                account.stripeAccountId(),
                payoutId,
                status,
                1000,
                "pln"
        );
    }

    private void postConnectWebhook(String webhook) {
        postWebhook(CONNECT_WEBHOOK_PATH, webhook, StripeSignatures.header(webhook, CONNECT_WEBHOOK_SECRET))
                .expectStatus().isOk();
    }

    /**
     * Waits for the event the outbox sent for the reference: keyed by it, with the type in the header.
     */
    private ConsumerRecord<String, String> awaitEvent(
            Map<TopicPartition, Long> start,
            String reference,
            String eventType
    ) {
        return kafkaProbe.awaitRecord(start, r -> reference.equals(r.key()) && eventType.equals(eventType(r)));
    }

    private static String eventType(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(KafkaConstants.HEADER_EVENT_TYPE);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String transferKey(String reference) {
        return "wd-transfer-" + reference + "-0";
    }

    private static String payoutKey(String reference) {
        return "wd-payout-" + reference + "-0";
    }

    private int payoutRows(String reference) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payouts WHERE transaction_reference = ?",
                Integer.class,
                reference
        );
    }

    private int eventRows(String reference) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?",
                Integer.class,
                reference
        );
    }
}
