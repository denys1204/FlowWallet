package com.flowwallet.payment.payout;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PayoutAccountStoreTest {
    private static final String USER = "0b9e5c1a-7d2f-4a3b-8c4d-5e6f7a8b9c0d";
    private static final Long SEEN_VERSION = 4L;

    private final PayoutAccountRepository accounts = mock(PayoutAccountRepository.class);
    private final PayoutAccountStore store = new PayoutAccountStore(accounts);

    private static PayoutAccount stored(long version, BankAccountState state) {
        PayoutAccount account = PayoutAccount.builder()
                .id(7L)
                .userId(USER)
                .accountKey("key-1")
                .tosAcceptedAt(Instant.EPOCH)
                .bankState(BankAccountState.NOT_SET)
                .version(version)
                .build();
        account.recordStripeAccount("acct_1");
        account.recordBankAccount("btok_1", "ba_1", "2874", "pln", state);
        return account;
    }

    private PayoutAccount locked(PayoutAccount account) {
        when(accounts.lockById(7L)).thenReturn(Optional.of(account));
        return account;
    }

    @Test
    void aChangedVersionIsRacedAndWritesNothing() {
        // Guards a refresh whose Stripe read began before another writer committed, such as the PUT or a failed
        // payout's reset, overwriting that newer state: either read may be the later one, so nothing is applied.
        PayoutAccount account = locked(stored(5L, BankAccountState.NOT_SET));

        BankRefresh result = store.applyBankState(7L, SEEN_VERSION, "ba_1", BankAccountState.READY);

        assertThat(result).isEqualTo(BankRefresh.RACED);
        assertThat(account.getBankState()).isEqualTo(BankAccountState.NOT_SET);
        verify(accounts).lockById(7L);
        verifyNoMoreInteractions(accounts);
    }

    @Test
    void aChangedVersionWinsOverAReplacedDefault() {
        // Guards a raced refresh being reported as a replaced default, which logs and drops the event: the version
        // check has to come first, so the redelivery reads Stripe again.
        locked(stored(5L, BankAccountState.READY));

        BankRefresh result = store.applyBankState(7L, SEEN_VERSION, "ba_2", BankAccountState.NOT_SET);

        assertThat(result).isEqualTo(BankRefresh.RACED);
    }

    @Test
    void aReplacedDefaultIsReplacedAndWritesNothing() {
        // Guards a refresh that read another bank account as Stripe's default writing its state over the stored one,
        // while the PUT that attached the other account is still recording its own read.
        PayoutAccount account = locked(stored(4L, BankAccountState.READY));

        BankRefresh result = store.applyBankState(7L, SEEN_VERSION, "ba_2", BankAccountState.NOT_SET);

        assertThat(result).isEqualTo(BankRefresh.REPLACED);
        assertThat(account.getBankState()).isEqualTo(BankAccountState.READY);
        assertThat(account.getExternalAccountId()).isEqualTo("ba_1");
        verify(accounts).lockById(7L);
        verifyNoMoreInteractions(accounts);
    }

    @Test
    void aMatchingDefaultSetsTheState() {
        // Guards an account event that Stripe sent for the stored bank account being dropped, which would leave the
        // account READY after Stripe made the bank account unpayable.
        PayoutAccount account = locked(stored(4L, BankAccountState.READY));

        BankRefresh result = store.applyBankState(7L, SEEN_VERSION, "ba_1", BankAccountState.NOT_SET);

        assertThat(result).isEqualTo(BankRefresh.CHANGED);
        assertThat(account.getBankState()).isEqualTo(BankAccountState.NOT_SET);
    }

    @Test
    void aMatchingDefaultInTheStoredStateIsUnchanged() {
        // Guards an event that repeats what the row already holds being reported as a change, which would log and
        // count every redelivery as work.
        PayoutAccount account = locked(stored(4L, BankAccountState.READY));

        BankRefresh result = store.applyBankState(7L, SEEN_VERSION, "ba_1", BankAccountState.READY);

        assertThat(result).isEqualTo(BankRefresh.UNCHANGED);
        assertThat(account.getBankState()).isEqualTo(BankAccountState.READY);
    }

    @Test
    void aMissingDefaultSetsTheStateWhateverBankAccountIsStored() {
        // Guards a bank account that Stripe no longer holds as a default staying READY: with no default to compare,
        // the replaced-default check cannot apply, and the withdrawal would fail at Stripe after the transfer.
        PayoutAccount account = locked(stored(4L, BankAccountState.READY));

        BankRefresh result = store.applyBankState(7L, SEEN_VERSION, null, BankAccountState.NOT_SET);

        assertThat(result).isEqualTo(BankRefresh.CHANGED);
        assertThat(account.getBankState()).isEqualTo(BankAccountState.NOT_SET);
    }

    @Test
    void aRowThatIsGoneIsADefect() {
        // Guards a refresh of a deleted row passing as applied: rows are never deleted, so a missing one is a defect
        // for the caller's 500, not an account event to drop.
        when(accounts.lockById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> store.applyBankState(7L, SEEN_VERSION, "ba_1", BankAccountState.READY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theRowOfAConnectedAccountIsFoundByItsStripeAccountId() {
        // Guards the account event's lookup using the user id or the account key, which the event does not carry.
        PayoutAccount account = stored(4L, BankAccountState.READY);
        when(accounts.findByStripeAccountId("acct_1")).thenReturn(Optional.of(account));

        assertThat(store.findByStripeAccount("acct_1")).containsSame(account);
    }

    @Test
    void aConnectedAccountNoRowHoldsIsNotFound() {
        // Guards an event for an account of another sandbox run failing the lookup instead of reporting no row, and
        // the lookup asking anything but the account's Stripe id.
        when(accounts.findByStripeAccountId("acct_other")).thenReturn(Optional.empty());

        assertThat(store.findByStripeAccount("acct_other")).isEmpty();
        verify(accounts).findByStripeAccountId("acct_other");
        verifyNoMoreInteractions(accounts);
    }
}
