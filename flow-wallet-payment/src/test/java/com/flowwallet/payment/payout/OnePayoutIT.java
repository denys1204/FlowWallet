package com.flowwallet.payment.payout;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.payment.support.PaymentIntegrationTest;
import com.flowwallet.payment.support.PayoutRows;
import com.flowwallet.payment.support.StripeFixtures;
import com.flowwallet.payment.support.WireSample;
import com.stripe.exception.ApiConnectionException;
import com.stripe.model.Payout;
import com.stripe.param.PayoutListParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * One payout to the bank per withdrawal: a payout Stripe made whose answer was lost on the way back is found among the
 * connected account's payouts on the next try and adopted, never made a second time.
 * <p>
 * Starts with {@code payouts} empty, because {@link PayoutExecutor#run()} works every due payout; see
 * {@link PayoutRows}.
 */
class OnePayoutIT extends PaymentIntegrationTest {
    @Autowired
    private PayoutExecutor executor;

    @BeforeEach
    void startWithNoPayouts() {
        PayoutRows.clearOnceQuiet(jdbc, kafkaProbe, consumerGroup);
    }

    @Test
    void aPayoutStripeMadeBeforeItsAnswerWasLostIsAdoptedByTheNextTryInsteadOfMadeAgain() throws Exception {
        // Guards a second payout of one withdrawal from the connected account to the bank after an unknown outcome:
        // Stripe made the payout but the connection dropped before its answer came back, so the step must keep its key
        // generation, back off, and on the next try list the account's payouts and adopt the one that carries the
        // reference rather than create one (ADR 0035).
        String userId = newUserId();
        String reference = newKey();
        PayoutRows.ReadyAccount account = PayoutRows.seedReadyAccount(jdbc, userId);
        accept(reference, userId);
        String transferId = StripeFixtures.fakeId("tr");
        doReturn(StripeFixtures.transfer(transferId, account.stripeAccountId(), 1000, "pln", reference))
                .when(stripeClient).createTransfer(any(), eq("wd-transfer-" + reference + "-0"));
        executor.run();
        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("TRANSFERRED");

        Payout payout = StripeFixtures.payout(
                StripeFixtures.fakeId("po"),
                "pending",
                1000,
                "pln",
                reference,
                account.bankAccountId(),
                null
        );
        List<Payout> atStripe = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            atStripe.add(payout);
            throw new ApiConnectionException("Connection reset after the payout was made (integration test)");
        }).when(stripeClient).createPayout(any(), eq("wd-payout-" + reference + "-0"), eq(account.stripeAccountId()));
        doAnswer(invocation -> List.copyOf(atStripe))
                .when(stripeClient).listPayouts(any(), eq(account.stripeAccountId()));

        executor.run();

        assertThat(atStripe).hasSize(1);
        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("TRANSFERRED");
        assertThat(PayoutRows.column(jdbc, reference, "stripe_payout_id")).isNull();
        assertThat(PayoutRows.column(jdbc, reference, "attempts")).isEqualTo("1");
        assertThat(PayoutRows.column(jdbc, reference, "key_gen")).isEqualTo("0");
        PayoutRows.makeDue(jdbc, reference);

        executor.run();

        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("PAYOUT_CREATED");
        assertThat(PayoutRows.column(jdbc, reference, "stripe_payout_id")).isEqualTo(payout.getId());
        verify(stripeClient, times(1)).createPayout(any(), any(), any());
        ArgumentCaptor<PayoutListParams> listed = ArgumentCaptor.forClass(PayoutListParams.class);
        verify(stripeClient).listPayouts(listed.capture(), eq(account.stripeAccountId()));
        // The listing reaches an hour back from the transfer, which was made moments ago, so a payout created just
        // before the TRANSFERRED write is found too.
        long anHourAgo = Instant.now().minus(Duration.ofHours(1)).getEpochSecond();
        assertThat(((PayoutListParams.Created) listed.getValue().getCreated()).getGte())
                .isBetween(anHourAgo - 120, anHourAgo);
        assertThat(atStripe).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?",
                Integer.class,
                reference
        )).isZero();
    }

    /**
     * Publishes the withdrawal command as the wallet's outbox does and waits until it is accepted.
     */
    private void accept(String reference, String userId) {
        kafkaProbe.awaitConsumed(consumerGroup, kafkaProbe.publish(
                KafkaConstants.WALLET_WITHDRAWALS_TOPIC,
                reference,
                KafkaConstants.EVENT_TYPE_WITHDRAWAL_REQUESTED,
                WireSample.of("withdrawal-requested")
                        .with("eventId", UUID.randomUUID().toString())
                        .with("transactionReference", reference)
                        .with("userId", userId)
                        .json()
        ));
        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("ACCEPTED");
    }
}
