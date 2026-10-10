package com.flowwallet.wallet.withdrawal;

import com.flowwallet.wallet.balance.Wallet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WithdrawalTest {
    private static final String REFERENCE = "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d";

    private final Wallet wallet = Wallet.builder()
            .id(11L)
            .userId("alice")
            .currency("PLN")
            .balance(new BigDecimal("100.0000"))
            .build();

    private Withdrawal pending() {
        return Withdrawal.pending(wallet, REFERENCE, new BigDecimal("75.0000"));
    }

    @Test
    void aDebitedWithdrawalStartsPendingWithNoReason() {
        // Guards a new withdrawal carrying a refusal reason, which the CHECK refuses, or a status other than PENDING,
        // which the first payout event could no longer raise.
        Withdrawal withdrawal = pending();

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.PENDING);
        assertThat(withdrawal.getWalletId()).isEqualTo(11L);
        assertThat(withdrawal.getRefusalReason()).isNull();
        assertThat(withdrawal.getReturnReason()).isNull();
    }

    @Test
    void aRefusedWithdrawalKeepsItsReasonAndNeedsOne() {
        // Guards a refusal stored without its reason, which the CHECK refuses and a replay could not repeat.
        Withdrawal refused = Withdrawal.refused(
                wallet,
                REFERENCE,
                new BigDecimal("75.00"),
                WithdrawalRefusalReason.NO_BANK_ACCOUNT
        );

        assertThat(refused.getStatus()).isEqualTo(WithdrawalStatus.REFUSED);
        assertThat(refused.getRefusalReason()).isEqualTo(WithdrawalRefusalReason.NO_BANK_ACCOUNT);
        assertThatThrownBy(() -> Withdrawal.refused(wallet, REFERENCE, new BigDecimal("75.00"), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aPayoutEventOnlyMovesAWithdrawalUpTheRanking() {
        // Guards a late event undoing a later status: a Paid that arrives after the failure would show money that
        // is on its way back as paid.
        Withdrawal withdrawal = pending();

        assertThat(withdrawal.markPaid()).isTrue();
        assertThat(withdrawal.markPaid()).isFalse();
        assertThat(withdrawal.markReturnPending("PAYOUT_FAILED")).isTrue();
        assertThat(withdrawal.markPaid()).isFalse();
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.RETURN_PENDING);
        assertThat(withdrawal.markReturned("PAYOUT_FAILED")).isTrue();
        assertThat(withdrawal.markReturnPending("PAYOUT_FAILED")).isFalse();
        assertThat(withdrawal.markReturned("PAYOUT_FAILED")).isFalse();
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.RETURNED);
    }

    @Test
    void aReturnCanArriveWithNoEarlierPayoutEvent() {
        // Guards a return from a payout that never left the platform, such as one refused for a short reserve,
        // which has no Paid or Failed event before it.
        Withdrawal withdrawal = pending();

        assertThat(withdrawal.markReturned("PLATFORM_FUNDS_SHORT")).isTrue();
        assertThat(withdrawal.getReturnReason()).isEqualTo("PLATFORM_FUNDS_SHORT");
    }

    @Test
    void theReasonIsRecordedOnlyWhenTheStatusRises() {
        // Guards a stale event overwriting the reason the client sees.
        Withdrawal withdrawal = pending();
        withdrawal.markReturned("PAYOUT_FAILED");

        withdrawal.markReturnPending("BANK_ACCOUNT_REJECTED");

        assertThat(withdrawal.getReturnReason()).isEqualTo("PAYOUT_FAILED");
    }

    @Test
    void theReasonShownIsThatOfTheEventThatLastRaisedTheStatus() {
        // Guards the reason a client sees after an operator returned a failed payout: why the money came back, not
        // why the payout failed.
        Withdrawal withdrawal = pending();

        withdrawal.markReturnPending("PAYOUT_FAILED");
        withdrawal.markReturned("OPERATOR_RETURNED");

        assertThat(withdrawal.getReturnReason()).isEqualTo("OPERATOR_RETURNED");
    }

    @Test
    void aReasonCodeOfAtMost32CapitalsAndUnderscoresIsStoredAsItCame() {
        // Guards the open set of reason codes: a code Payment Service adds later must not become OTHER, and one the
        // VARCHAR(32) column cannot hold must.
        assertThat(Withdrawal.storedReasonCode("PLATFORM_FUNDS_SHORT")).isEqualTo("PLATFORM_FUNDS_SHORT");
        assertThat(Withdrawal.storedReasonCode("A".repeat(32))).isEqualTo("A".repeat(32));
        assertThat(Withdrawal.storedReasonCode("A".repeat(33))).isEqualTo(Withdrawal.OTHER_REASON);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"payout_failed", "PAYOUT FAILED", "PAYOUT-FAILED", "ZWROT_ŻĄDANIA"})
    void anythingElseIsStoredAsOther(String reasonCode) {
        // Guards a producer's value that a client reading the reason could not tell from a code.
        assertThat(Withdrawal.storedReasonCode(reasonCode)).isEqualTo(Withdrawal.OTHER_REASON);
    }

    @Test
    void aRefusedWithdrawalNeverChanges() {
        // Guards a payout event for a refused withdrawal changing its status: no money ever left for it.
        Withdrawal refused = Withdrawal.refused(
                wallet,
                REFERENCE,
                new BigDecimal("75.00"),
                WithdrawalRefusalReason.INSUFFICIENT_FUNDS
        );

        assertThatThrownBy(refused::markPaid).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> refused.markReturned("PAYOUT_FAILED")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRepeatIsTheSameWalletAndAmountWhateverTheScale() {
        // A stored amount comes back from NUMERIC(19,4) as 75.0000, and under equals a retry that sent 75.00 would
        // count as another withdrawal.
        Withdrawal withdrawal = pending();

        assertThat(withdrawal.isRepeatOf(11L, new BigDecimal("75.00"))).isTrue();
        assertThat(withdrawal.isRepeatOf(12L, new BigDecimal("75.00"))).isFalse();
        assertThat(withdrawal.isRepeatOf(11L, new BigDecimal("75.01"))).isFalse();
    }

    @Test
    void everyStatusAndRefusalReasonFitsItsColumn() {
        // Guards a constant added later that the VARCHAR(20) status or VARCHAR(32) reason column would refuse.
        assertThat(Arrays.stream(WithdrawalStatus.values()).map(Enum::name)).allMatch(name -> name.length() <= 20);
        assertThat(Arrays.stream(WithdrawalRefusalReason.values()).map(Enum::name))
                .allMatch(name -> name.length() <= 32);
    }
}
