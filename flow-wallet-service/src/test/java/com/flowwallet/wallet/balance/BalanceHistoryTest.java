package com.flowwallet.wallet.balance;

import com.flowwallet.wallet.enums.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BalanceHistoryTest {
    private static final String REFERENCE = "7e1855b3-4d95-4a72-a0c9-ef0d78be2e44";

    /**
     * Built with an id, because {@link Wallet#open} leaves it null. The legs would then carry no wallet id to
     * check, and {@code isRepeatOf} would throw on the stored null, which it does by design rather than match
     * it, so the comparison of sender wallets would never be exercised.
     */
    private Wallet wallet(long id, String userId, String balance) {
        return Wallet.builder()
                .id(id)
                .userId(userId)
                .currency("USD")
                .balance(new BigDecimal(balance))
                .build();
    }

    @Test
    void theOutgoingLegNamesTheRecipientAndTheIncomingLegNamesTheSender() {
        // Guards swapped counterparties, a wrong sign in balanceAfter on either leg, legs that do not share the
        // reference, and an event id on a movement that never came from payment.events.
        Wallet alice = wallet(11L, "alice", "100.0000");
        Wallet bob = wallet(12L, "bob", "5.0000");
        BigDecimal amount = new BigDecimal("25.0000");

        BalanceHistory out = BalanceHistory.transferOut(alice, REFERENCE, "bob", amount, alice.debit(amount));
        BalanceHistory in = BalanceHistory.transferIn(bob, REFERENCE, "alice", amount, bob.credit(amount));

        assertThat(out.getType()).isEqualTo(TransactionType.TRANSFER_OUT);
        assertThat(out.getWalletId()).isEqualTo(11L);
        assertThat(out.getCounterpartyUserId()).isEqualTo("bob");
        assertThat(out.getBalanceBefore()).isEqualByComparingTo("100");
        assertThat(out.getBalanceAfter()).isEqualByComparingTo("75").isEqualByComparingTo(alice.getBalance());

        assertThat(in.getType()).isEqualTo(TransactionType.TRANSFER_IN);
        assertThat(in.getWalletId()).isEqualTo(12L);
        assertThat(in.getCounterpartyUserId()).isEqualTo("alice");
        assertThat(in.getBalanceBefore()).isEqualByComparingTo("5");
        assertThat(in.getBalanceAfter()).isEqualByComparingTo("30").isEqualByComparingTo(bob.getBalance());

        assertThat(List.of(out, in)).allSatisfy(leg -> {
            assertThat(leg.getTransactionReference()).isEqualTo(REFERENCE);
            assertThat(leg.getAmount()).isEqualByComparingTo("25");
            assertThat(leg.getEventId()).isNull();
        });
    }

    @Test
    void aRepeatIsJudgedByWalletRecipientAndAmountByValue() {
        // equals() on BigDecimal would turn every genuine retry into a 409: the stored amount reads back as
        // 25.0000 and the retry may send 25.00. The other cases guard a transfer from another wallet, even the
        // same user's, being taken for a repeat, and a leg that is not a TRANSFER_OUT answering at all.
        Wallet alice = wallet(11L, "alice", "100.0000");
        Wallet bob = wallet(12L, "bob", "5.0000");
        BigDecimal stored = new BigDecimal("25.0000");
        BalanceHistory out = BalanceHistory.transferOut(alice, REFERENCE, "bob", stored, new BigDecimal("100.0000"));
        BalanceHistory in = BalanceHistory.transferIn(bob, REFERENCE, "alice", stored, new BigDecimal("5.0000"));
        BalanceHistory deposit = BalanceHistory.deposit(alice, REFERENCE, "evt-1", stored, BigDecimal.ZERO);

        assertThat(out.isRepeatOf(11L, "bob", new BigDecimal("25.00"))).isTrue();

        assertThat(out.isRepeatOf(11L, "bob", new BigDecimal("25.01"))).isFalse();
        assertThat(out.isRepeatOf(11L, "carol", new BigDecimal("25.00"))).isFalse();
        assertThat(out.isRepeatOf(13L, "bob", new BigDecimal("25.00"))).isFalse();
        assertThat(in.isRepeatOf(12L, "alice", new BigDecimal("25.00"))).isFalse();
        assertThat(deposit.isRepeatOf(11L, "bob", new BigDecimal("25.00"))).isFalse();
    }
}
