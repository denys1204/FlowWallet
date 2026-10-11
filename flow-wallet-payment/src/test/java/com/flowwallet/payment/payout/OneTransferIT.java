package com.flowwallet.payment.payout;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.payment.support.DatabaseFaults;
import com.flowwallet.payment.support.PaymentIntegrationTest;
import com.flowwallet.payment.support.PayoutRows;
import com.flowwallet.payment.support.StripeFixtures;
import com.flowwallet.payment.support.WireSample;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * One transfer per payout, whatever fails between Stripe and the database: a transfer Stripe made but the executor
 * could not record is found by its transfer group on the next try and adopted, never made a second time.
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

        Transfer transfer = StripeFixtures.transfer(
                StripeFixtures.fakeId("tr"),
                account.stripeAccountId(),
                1000,
                "pln",
                reference
        );
        List<Transfer> atStripe = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            atStripe.add(transfer);
            return transfer;
        }).when(stripeClient).createTransfer(any(), eq("wd-transfer-" + reference + "-0"));
        doAnswer(invocation -> {
            String group = invocation.<TransferListParams>getArgument(0).getTransferGroup();
            return atStripe.stream().filter(made -> made.getTransferGroup().equals(group)).toList();
        }).when(stripeClient).listTransfers(any());

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
}
