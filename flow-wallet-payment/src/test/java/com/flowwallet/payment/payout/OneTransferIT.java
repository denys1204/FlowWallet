package com.flowwallet.payment.payout;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.payment.support.Concurrently;
import com.flowwallet.payment.support.DatabaseFaults;
import com.flowwallet.payment.support.PaymentIntegrationTest;
import com.flowwallet.payment.support.PayoutRows;
import com.flowwallet.payment.support.StripeFixtures;
import com.flowwallet.payment.support.WireSample;
import com.stripe.exception.ApiConnectionException;
import com.stripe.model.Transfer;
import com.stripe.param.TransferListParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * One transfer per payout, whatever fails between Stripe and the database and however many executors run: a transfer
 * Stripe made but the executor could not record, or whose answer was lost on the way back, is found by its transfer
 * group on the next try and adopted, never made a second time, and two executors that select one due payout at once
 * leave its transfer to the one whose claim holds.
 * <p>
 * Starts with {@code payouts} empty, because {@link PayoutExecutor#run()} works every due payout; see
 * {@link PayoutRows}.
 */
class OneTransferIT extends PaymentIntegrationTest {
    @Autowired
    private PayoutExecutor executor;

    @BeforeEach
    void startWithNoPayouts() {
        PayoutRows.clearOnceQuiet(jdbc, kafkaProbe, consumerGroup);
    }

    @Test
    void aTransferMadeAtStripeButNeverRecordedIsAdoptedByTheNextTryInsteadOfMadeAgain() throws Exception {
        // Guards the platform paying one withdrawal twice: after a crash between Stripe's success and the TRANSFERRED
        // write, the next claim must look the transfer up by its group before it creates one (ADR 0035). The
        // idempotency key alone does not cover it, since Stripe keeps a key for only 24 hours.
        String userId = newUserId();
        String reference = newKey();
        PayoutRows.ReadyAccount account = PayoutRows.seedReadyAccount(jdbc, userId);
        accept(reference, userId);
        Transfer transfer = transfer(reference, account);
        List<Transfer> atStripe = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            atStripe.add(transfer);
            return transfer;
        }).when(stripeClient).createTransfer(any(), eq(transferKey(reference)));
        listTransfersFrom(atStripe);

        try (var fault = DatabaseFaults.failUpdate(jdbc, "payouts", "stripe_transfer_id", transfer.getId())) {
            executor.run();

            assertThat(atStripe).hasSize(1);
            assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("ACCEPTED");
            assertThat(PayoutRows.column(jdbc, reference, "stripe_transfer_id")).isNull();
            assertThat(PayoutRows.column(jdbc, reference, "transfer_attempted_at")).isNotNull();
        }
        // The failed write left the claim standing; its lease and the row's next try now lie in the past.
        PayoutRows.makeDue(jdbc, reference);

        executor.run();

        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("TRANSFERRED");
        assertThat(PayoutRows.column(jdbc, reference, "stripe_transfer_id")).isEqualTo(transfer.getId());
        assertThat(PayoutRows.column(jdbc, reference, "stripe_account_id")).isEqualTo(account.stripeAccountId());
        verify(stripeClient, times(1)).createTransfer(any(), any());
        verify(stripeClient).listTransfers(any());
        assertThat(atStripe).hasSize(1);
    }

    @Test
    void aTransferStripeMadeBeforeItsAnswerWasLostIsAdoptedByTheNextTryInsteadOfMadeAgain() throws Exception {
        // Guards the platform paying one withdrawal twice after an unknown outcome: Stripe made the transfer but the
        // connection dropped before its answer came back, so the step must keep its key generation, back off, and on
        // the next try find the transfer by its group and adopt it rather than create one (ADR 0035).
        String userId = newUserId();
        String reference = newKey();
        PayoutRows.ReadyAccount account = PayoutRows.seedReadyAccount(jdbc, userId);
        accept(reference, userId);
        Transfer transfer = transfer(reference, account);
        List<Transfer> atStripe = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            atStripe.add(transfer);
            throw new ApiConnectionException("Connection reset after the transfer was made (integration test)");
        }).when(stripeClient).createTransfer(any(), eq(transferKey(reference)));
        listTransfersFrom(atStripe);

        executor.run();

        assertThat(atStripe).hasSize(1);
        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("ACCEPTED");
        assertThat(PayoutRows.column(jdbc, reference, "stripe_transfer_id")).isNull();
        assertThat(PayoutRows.column(jdbc, reference, "attempts")).isEqualTo("1");
        assertThat(PayoutRows.column(jdbc, reference, "key_gen")).isEqualTo("0");
        PayoutRows.makeDue(jdbc, reference);

        executor.run();

        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("TRANSFERRED");
        assertThat(PayoutRows.column(jdbc, reference, "stripe_transfer_id")).isEqualTo(transfer.getId());
        assertThat(PayoutRows.column(jdbc, reference, "stripe_account_id")).isEqualTo(account.stripeAccountId());
        verify(stripeClient, times(1)).createTransfer(any(), any());
        verify(stripeClient).listTransfers(any());
        assertThat(atStripe).hasSize(1);
    }

    @Test
    void twoExecutorsRunningAtOnceMakeOneTransferForTheDuePayout() throws Exception {
        // Guards two instances paying one withdrawal twice: both select the due row, and only the conditional claim
        // on claimed_until may let one of them call Stripe (ADR 0035). Every update of this payout waits a second
        // while it holds the row's lock, so the second claim queues behind the first, sees it only once it commits,
        // and finds the row still ACCEPTED: the lease, not a status the first executor already moved, must refuse it.
        String userId = newUserId();
        String reference = newKey();
        PayoutRows.ReadyAccount account = PayoutRows.seedReadyAccount(jdbc, userId);
        accept(reference, userId);
        Transfer transfer = transfer(reference, account);
        List<Transfer> atStripe = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            atStripe.add(transfer);
            return transfer;
        }).when(stripeClient).createTransfer(any(), eq(transferKey(reference)));

        try (var delay = DatabaseFaults.delayUpdate(jdbc, "payouts", "transaction_reference", reference, 1.0)) {
            Concurrently.run(2, thread -> {
                executor.run();
                return null;
            });
        }

        assertThat(atStripe).hasSize(1);
        verify(stripeClient, times(1)).createTransfer(any(), any());
        verify(stripeClient, never()).listTransfers(any());
        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("TRANSFERRED");
        assertThat(PayoutRows.column(jdbc, reference, "stripe_transfer_id")).isEqualTo(transfer.getId());
        assertThat(PayoutRows.column(jdbc, reference, "claimed_until")).isNull();
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

    private static Transfer transfer(String reference, PayoutRows.ReadyAccount account) {
        return StripeFixtures.transfer(StripeFixtures.fakeId("tr"), account.stripeAccountId(), 1000, "pln", reference);
    }

    /**
     * Answers a transfer listing with the transfers in the list that carry the listed group, as Stripe would.
     */
    private void listTransfersFrom(List<Transfer> atStripe) throws Exception {
        doAnswer(invocation -> {
            String group = invocation.<TransferListParams>getArgument(0).getTransferGroup();
            return atStripe.stream().filter(made -> made.getTransferGroup().equals(group)).toList();
        }).when(stripeClient).listTransfers(any());
    }

    private static String transferKey(String reference) {
        return "wd-transfer-" + reference + "-0";
    }
}
