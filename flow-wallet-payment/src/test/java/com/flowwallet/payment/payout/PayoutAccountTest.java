package com.flowwallet.payment.payout;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutAccountTest {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-10-10T12:00:00Z");

    @Test
    void aReservedAccountHasNoBankAccountReadyAndKeepsItsTermsTime() {
        // Guards a payout to a user whose bank account was never attached, and a retry of the account's creation
        // sending another acceptance time under the same key, which Stripe refuses as a different request.
        PayoutAccount account = PayoutAccount.reserve("alice", "key-1", ACCEPTED_AT);

        assertThat(account.getBankState()).isEqualTo(BankAccountState.NOT_SET);
        assertThat(account.getTosAcceptedAt()).isEqualTo(ACCEPTED_AT);
        assertThat(account.getStripeAccountId()).isNull();
    }

    @Test
    void theStripeAccountIsRecordedOnceAndAnotherOneIsADefect() {
        // Guards a second connected account replacing the first, which would strand the money already transferred to
        // the first one.
        PayoutAccount account = PayoutAccount.reserve("alice", "key-1", ACCEPTED_AT);

        account.recordStripeAccount("acct_1");
        account.recordStripeAccount("acct_1");

        assertThat(account.getStripeAccountId()).isEqualTo("acct_1");
        assertThatThrownBy(() -> account.recordStripeAccount("acct_2")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anAttachedBankAccountRemembersItsTokenSoARepeatIsAnsweredFromTheRow() {
        // Guards sending a used token to Stripe again, which refuses it although the bank account is attached.
        PayoutAccount account = PayoutAccount.reserve("alice", "key-1", ACCEPTED_AT);
        account.recordStripeAccount("acct_1");

        account.recordBankAccount("btok_1", "ba_1", "2874", "pln", BankAccountState.READY);

        assertThat(account.isLastTokenApplied("btok_1")).isTrue();
        assertThat(account.isLastTokenApplied("btok_2")).isFalse();
        assertThat(account.isLastTokenApplied(null)).isFalse();
        assertThat(account.getBankState()).isEqualTo(BankAccountState.READY);
        assertThat(account.getBankLast4()).isEqualTo("2874");
    }

    @Test
    void aBankAccountIsReadyOnlyForItsOwnCurrencyWhateverTheCase() {
        // Guards Stripe's lower-case currency never matching a payout's upper-case one, which would return every
        // payout as BANK_ACCOUNT_NOT_SET, and a ready account in one currency paying out another.
        PayoutAccount account = PayoutAccount.reserve("alice", "key-1", ACCEPTED_AT);
        account.recordStripeAccount("acct_1");

        account.recordBankAccount("btok_1", "ba_1", "2874", "pln", BankAccountState.READY);

        assertThat(account.getBankCurrency()).isEqualTo("PLN");
        assertThat(account.isReadyFor("PLN")).isTrue();
        assertThat(account.isReadyFor("EUR")).isFalse();
        account.updateBankState(BankAccountState.NOT_SET);
        assertThat(account.isReadyFor("PLN")).isFalse();
    }

    @Test
    void aStateUpdateReportsWhetherItChangedAnything() {
        // Guards logging and counting an account event as a change when Stripe only repeated the state already held.
        PayoutAccount account = PayoutAccount.reserve("alice", "key-1", ACCEPTED_AT);

        assertThat(account.updateBankState(BankAccountState.NOT_SET)).isFalse();
        account.recordStripeAccount("acct_1");
        account.recordBankAccount("btok_1", "ba_1", "2874", "pln", BankAccountState.READY);
        assertThat(account.updateBankState(BankAccountState.NOT_SET)).isTrue();
        assertThat(account.getBankState()).isEqualTo(BankAccountState.NOT_SET);
    }

    @Test
    void everyBankStateFitsItsColumn() {
        // Guards a state added later that the VARCHAR(20) bank_state column would refuse.
        assertThat(Arrays.stream(BankAccountState.values()).map(Enum::name)).allMatch(name -> name.length() <= 20);
    }
}
