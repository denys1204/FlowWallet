package com.flowwallet.wallet.withdrawal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class WithdrawalRejectedExceptionTest {
    @Test
    void thePaymentServicesWordsReachTheCallerAsTheDetailOfA400() {
        // Guards the relayed refusal being reworded or answered with another status. The terms (the currencies
        // paid out, the minimum and the payout grid) belong to Payment Service, so the client reads its own
        // wording, which names the rule and not the amount sent, and gets a 400 it can act on.
        // See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
        WithdrawalRejectedException exception = new WithdrawalRejectedException(
                "The smallest withdrawal in PLN is 5.00."
        );

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception.getMessage()).isEqualTo("The smallest withdrawal in PLN is 5.00.");
    }
}
