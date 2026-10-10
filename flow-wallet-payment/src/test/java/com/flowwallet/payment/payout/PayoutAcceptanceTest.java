package com.flowwallet.payment.payout;

import com.flowwallet.contract.event.WithdrawalRequestedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PayoutAcceptanceTest {
    private static final String REFERENCE = "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d";

    private final PayoutRepository payouts = mock(PayoutRepository.class);
    private final PayoutOutboxService outbox = mock(PayoutOutboxService.class);
    private final PayoutAcceptance acceptance =
            new PayoutAcceptance(payouts, new PayoutTerms(new PayoutProperties()), outbox);

    private WithdrawalRequestedEvent command(String amount, String currency) {
        return new WithdrawalRequestedEvent("evt-1", 1, REFERENCE, "alice", new BigDecimal(amount), currency);
    }

    private Payout saved() {
        ArgumentCaptor<Payout> payout = ArgumentCaptor.forClass(Payout.class);
        verify(payouts).saveAndFlush(payout.capture());
        return payout.getValue();
    }

    @Test
    void aCommandOnTheTermsIsAcceptedAndDueForItsTransfer() {
        // Guards an accepted payout the executor never picks up, or one with terms other than the command's.
        acceptance.accept(command("75.00", "pln"));

        Payout payout = saved();
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.ACCEPTED);
        assertThat(payout.getTransactionReference()).isEqualTo(REFERENCE);
        assertThat(payout.getUserId()).isEqualTo("alice");
        assertThat(payout.getAmount()).isEqualByComparingTo("75.00");
        assertThat(payout.getCurrency()).isEqualTo("PLN");
        assertThat(payout.getNextAttemptAt()).isNotNull();
        verifyNoInteractions(outbox);
    }

    @Test
    void aCommandOffTheTermsIsInsertedAndReturnedWithItsEventInOneTransaction() {
        // Guards debited money that never comes back: a command for a currency taken off the list after its
        // preflight has to be returned through the event path, and the row has to exist first, so a redelivery
        // meets the reference instead of returning the money twice.
        acceptance.accept(command("75.00", "EUR"));

        Payout payout = saved();
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.RETURNED);
        assertThat(payout.getReasonCode()).isEqualTo(PayoutReason.TERMS_REFUSED);
        var order = inOrder(payouts, outbox);
        order.verify(payouts).saveAndFlush(any());
        order.verify(outbox).publishReturned(any(), any());
    }

    @Test
    void aRepeatedReferenceIsRaisedBeforeAnyEventIsQueued() {
        // Guards a second Returned event for a reference already returned, which the wallet would refuse as a
        // duplicate, and a violation swallowed here instead of classified by the listener after the rollback.
        when(payouts.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("payouts_reference_key"));

        assertThatThrownBy(() -> acceptance.accept(command("75.00", "EUR")))
                .isInstanceOf(DataIntegrityViolationException.class);

        verifyNoInteractions(outbox);
    }
}
