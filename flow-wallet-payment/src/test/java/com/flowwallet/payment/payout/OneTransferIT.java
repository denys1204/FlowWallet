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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

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
 * leave its transfer to the one whose claim holds. After an operator reverses stray transfers in full and requeues, the
 * step ignores them: it adopts the one transfer left, or moves to the next key generation only once Stripe answers
 * the old key with a transfer that is back (ADR 0045).
 * <p>
 * Starts with {@code payouts} empty, because {@link PayoutExecutor#run()} works every due payout; see
 * {@link PayoutRows}.
 */
class OneTransferIT extends PaymentIntegrationTest {
    @Autowired
    private PayoutExecutor executor;

    @Autowired
    private PayoutOperations operations;

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

    @Test
    void aRequeueAfterTheStrayTransferIsReversedAdoptsTheOneLeftAndMakesNoOther() throws Exception {
        // Guards a payout an operator can only return: a stray transfer beside the executor's own sends it to MULTIPLE,
        // and once the stray is reversed in full by hand, the requeue must adopt the executor's transfer, not meet
        // MULTIPLE again or make a third (ADR 0045).
        String userId = newUserId();
        String reference = newKey();
        PayoutRows.ReadyAccount account = PayoutRows.seedReadyAccount(jdbc, userId);
        accept(reference, userId);
        Transfer own = transfer(reference, account);
        Transfer stray = transfer(reference, account);
        List<Transfer> atStripe = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            atStripe.add(own);
            throw new ApiConnectionException("Connection reset after the transfer was made (integration test)");
        }).when(stripeClient).createTransfer(any(), eq(transferKey(reference, 0)));
        listTransfersFrom(atStripe, made -> true);

        executor.run();
        atStripe.add(stray);
        PayoutRows.makeDue(jdbc, reference);
        executor.run();

        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("NEEDS_ATTENTION");
        assertThat(PayoutRows.column(jdbc, reference, "attention_cause")).isEqualTo("MULTIPLE");

        reverseInFull(atStripe, stray);
        operations.act(reference, PayoutAction.REQUEUE);
        PayoutRows.makeDue(jdbc, reference);
        executor.run();

        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("TRANSFERRED");
        assertThat(PayoutRows.column(jdbc, reference, "stripe_transfer_id")).isEqualTo(own.getId());
        assertThat(PayoutRows.column(jdbc, reference, "key_gen")).isEqualTo("0");
        verify(stripeClient, times(1)).createTransfer(any(), any());
    }

    @Test
    void aRequeueAfterEveryTransferIsReversedPaysOutUnderTheNextKeyGeneration() throws Exception {
        // Guards adopting Stripe's replay of a reversed transfer, which shows nothing reversed and would send the
        // payout on with no money in the connected account, and a fresh key taken before the old one answered
        // (ADR 0045).
        String userId = newUserId();
        String reference = newKey();
        PayoutRows.ReadyAccount account = PayoutRows.seedReadyAccount(jdbc, userId);
        accept(reference, userId);
        Transfer own = transfer(reference, account);
        Transfer stray = StripeFixtures.transfer(
                StripeFixtures.fakeId("tr"),
                account.stripeAccountId(),
                700,
                "pln",
                reference
        );
        Transfer next = transfer(reference, account);
        List<Transfer> atStripe = new CopyOnWriteArrayList<>();
        replayAfterALostAnswer(reference, own, atStripe);
        doAnswer(invocation -> {
            atStripe.add(next);
            return next;
        }).when(stripeClient).createTransfer(any(), eq(transferKey(reference, 1)));
        listTransfersFrom(atStripe, made -> true);

        executor.run();
        atStripe.add(stray);
        PayoutRows.makeDue(jdbc, reference);
        executor.run();
        assertThat(PayoutRows.column(jdbc, reference, "attention_cause")).isEqualTo("MULTIPLE");

        reverseInFull(atStripe, own);
        reverseInFull(atStripe, stray);
        operations.act(reference, PayoutAction.REQUEUE);
        PayoutRows.makeDue(jdbc, reference);
        executor.run();

        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("ACCEPTED");
        assertThat(PayoutRows.column(jdbc, reference, "key_gen")).isEqualTo("1");
        assertThat(PayoutRows.column(jdbc, reference, "claimed_until")).isNull();
        assertThat(PayoutRows.outboxRows(jdbc, reference, KafkaConstants.EVENT_TYPE_PAYOUT_RETURNED)).isZero();
        verify(stripeClient, never()).createTransfer(any(), eq(transferKey(reference, 1)));

        executor.run();

        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("TRANSFERRED");
        assertThat(PayoutRows.column(jdbc, reference, "stripe_transfer_id")).isEqualTo(next.getId());
        verify(stripeClient, times(1)).createTransfer(any(), eq(transferKey(reference, 1)));
        assertThat(atStripe).filteredOn(made -> made.getAmountReversed() < made.getAmount()).hasSize(1);
    }

    @ParameterizedTest(name = "the listing shows the new transfer: {0}")
    @ValueSource(booleans = {true, false})
    void anUnknownCreateUnderTheOldKeyAfterARequeueIsAdoptedNotMadeUnderTheNextKey(boolean listed) throws Exception {
        // Guards a key generation raised on an unknown answer, or on a listing that does not show the transfer the old
        // key made: a create under the next key would land a second transfer beside it (ADR 0045).
        String userId = newUserId();
        String reference = newKey();
        PayoutRows.ReadyAccount account = PayoutRows.seedReadyAccount(jdbc, userId);
        accept(reference, userId);
        Transfer stray = StripeFixtures.transfer(
                StripeFixtures.fakeId("tr"),
                account.stripeAccountId(),
                700,
                "pln",
                reference
        );
        Transfer made = transfer(reference, account);
        List<Transfer> atStripe = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> switch (calls.incrementAndGet()) {
            case 1 -> throw new ApiConnectionException("Connection refused before the request ran (integration test)");
            case 2 -> {
                atStripe.add(made);
                throw new ApiConnectionException("Connection reset after the transfer was made (integration test)");
            }
            default -> made;
        }).when(stripeClient).createTransfer(any(), eq(transferKey(reference, 0)));
        listTransfersFrom(atStripe, found -> listed || !found.getId().equals(made.getId()));

        executor.run();
        atStripe.add(stray);
        PayoutRows.makeDue(jdbc, reference);
        executor.run();
        assertThat(PayoutRows.column(jdbc, reference, "attention_cause")).isEqualTo("MISMATCH");

        reverseInFull(atStripe, stray);
        operations.act(reference, PayoutAction.REQUEUE);
        PayoutRows.makeDue(jdbc, reference);
        executor.run();
        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("ACCEPTED");
        assertThat(PayoutRows.column(jdbc, reference, "key_gen")).isEqualTo("0");
        PayoutRows.makeDue(jdbc, reference);
        executor.run();

        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("TRANSFERRED");
        assertThat(PayoutRows.column(jdbc, reference, "stripe_transfer_id")).isEqualTo(made.getId());
        assertThat(PayoutRows.column(jdbc, reference, "key_gen")).isEqualTo("0");
        verify(stripeClient, never()).createTransfer(any(), eq(transferKey(reference, 1)));
    }

    @Test
    void aSpentKeyWithABankAccountNoLongerReadyReturnsThePayoutWithOneReturnedEvent() throws Exception {
        // Guards a fresh transfer to an account nobody can be paid out from once every transfer is back, and a return
        // without the event that credits the wallet (ADR 0045).
        String userId = newUserId();
        String reference = newKey();
        PayoutRows.ReadyAccount account = PayoutRows.seedReadyAccount(jdbc, userId);
        accept(reference, userId);
        Transfer own = transfer(reference, account);
        Transfer stray = transfer(reference, account);
        List<Transfer> atStripe = new CopyOnWriteArrayList<>();
        replayAfterALostAnswer(reference, own, atStripe);
        listTransfersFrom(atStripe, made -> true);

        executor.run();
        atStripe.add(stray);
        PayoutRows.makeDue(jdbc, reference);
        executor.run();
        reverseInFull(atStripe, own);
        reverseInFull(atStripe, stray);
        operations.act(reference, PayoutAction.REQUEUE);
        jdbc.update(
                "UPDATE payout_accounts SET bank_state = 'NOT_SET' WHERE stripe_account_id = ?",
                account.stripeAccountId()
        );
        PayoutRows.makeDue(jdbc, reference);
        executor.run();

        assertThat(PayoutRows.column(jdbc, reference, "status")).isEqualTo("RETURNED");
        assertThat(PayoutRows.column(jdbc, reference, "reason_code")).isEqualTo("BANK_ACCOUNT_NOT_SET");
        assertThat(PayoutRows.outboxRows(jdbc, reference, KafkaConstants.EVENT_TYPE_PAYOUT_RETURNED)).isEqualTo(1);
        verify(stripeClient, times(2)).createTransfer(any(), eq(transferKey(reference, 0)));
        verify(stripeClient, never()).createTransfer(any(), eq(transferKey(reference, 1)));
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
        listTransfersFrom(atStripe, made -> true);
    }

    /**
     * The same, showing only the transfers the filter lets through, as a listing that misses one would.
     */
    private void listTransfersFrom(List<Transfer> atStripe, Predicate<Transfer> shown) throws Exception {
        doAnswer(invocation -> {
            String group = invocation.<TransferListParams>getArgument(0).getTransferGroup();
            return atStripe.stream()
                    .filter(made -> made.getTransferGroup().equals(group))
                    .filter(shown)
                    .toList();
        }).when(stripeClient).listTransfers(any());
    }

    /**
     * Key generation 0 makes the transfer and loses its answer; every later create under it gets Stripe's replay, the
     * transfer as it was created, whatever happened to it since.
     */
    private void replayAfterALostAnswer(String reference, Transfer own, List<Transfer> atStripe) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                atStripe.add(own);
                throw new ApiConnectionException("Connection reset after the transfer was made (integration test)");
            }
            return own;
        }).when(stripeClient).createTransfer(any(), eq(transferKey(reference, 0)));
    }

    /**
     * Replaces the transfer at Stripe with a copy whose reversals reach its amount, as a full reversal in the Dashboard
     * leaves it; the original object stays as Stripe's replay shows it.
     */
    private static void reverseInFull(List<Transfer> atStripe, Transfer transfer) {
        Transfer reversed = StripeFixtures.transfer(
                transfer.getId(),
                transfer.getDestination(),
                transfer.getAmount(),
                transfer.getCurrency(),
                transfer.getTransferGroup().substring("wd-".length())
        );
        reversed.setAmountReversed(transfer.getAmount());
        atStripe.replaceAll(made -> made.getId().equals(transfer.getId()) ? reversed : made);
    }

    private static String transferKey(String reference) {
        return transferKey(reference, 0);
    }

    private static String transferKey(String reference, int keyGen) {
        return "wd-transfer-" + reference + "-" + keyGen;
    }
}
