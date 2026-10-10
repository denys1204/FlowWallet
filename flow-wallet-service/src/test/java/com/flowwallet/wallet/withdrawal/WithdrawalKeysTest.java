package com.flowwallet.wallet.withdrawal;

import com.flowwallet.platform.exception.ApiException;
import com.flowwallet.wallet.balance.BalanceHistory;
import com.flowwallet.wallet.balance.BalanceHistoryRepository;
import com.flowwallet.wallet.enums.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The repositories are mocks, so these tests pin the judgement made from the rows they return. Which rows a read
 * sees, before the lock or after it, is the callers' part and is pinned in {@code WithdrawalServiceTest} and
 * {@code WithdrawalHandlerTest}.
 */
class WithdrawalKeysTest {
    private static final String REFERENCE = "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d";
    // Above 127, where boxed Longs stop being cached, so comparing two ids with == instead of equals fails.
    private static final long WALLET_ID = 1011L;
    private static final BigDecimal AMOUNT = new BigDecimal("75.0000");

    private final WithdrawalRepository withdrawals = mock(WithdrawalRepository.class);
    private final BalanceHistoryRepository movements = mock(BalanceHistoryRepository.class);
    private final WithdrawalKeys keys = new WithdrawalKeys(withdrawals, movements);

    private static Withdrawal stored(long walletId, String amount, WithdrawalStatus status) {
        return Withdrawal.builder()
                .id(301L)
                .walletId(walletId)
                .transactionReference(REFERENCE)
                .amount(new BigDecimal(amount))
                .status(status)
                .build();
    }

    private static Withdrawal storedRefusal(long walletId, WithdrawalRefusalReason reason) {
        return Withdrawal.builder()
                .id(301L)
                .walletId(walletId)
                .transactionReference(REFERENCE)
                .amount(new BigDecimal("75.0000"))
                .status(WithdrawalStatus.REFUSED)
                .refusalReason(reason)
                .build();
    }

    private static BalanceHistory ledgerRow(long walletId, TransactionType type) {
        return BalanceHistory.builder()
                .id(501L)
                .walletId(walletId)
                .entryNo(6L)
                .transactionReference(REFERENCE)
                .type(type)
                .amount(new BigDecimal("75.0000"))
                .balanceBefore(new BigDecimal("100.0000"))
                .balanceAfter(new BigDecimal("25.0000"))
                .build();
    }

    private void keyHolds(Withdrawal withdrawal) {
        when(withdrawals.findByTransactionReference(REFERENCE)).thenReturn(Optional.of(withdrawal));
    }

    @Test
    void noRowMeansAFreshKey() {
        // Guards a key a deposit or a transfer used being judged taken for a withdrawal, which would answer a valid
        // withdrawal with someone else's receipt or a 409. Only the withdrawals table decides; the ledger holds
        // movements of other kinds under the same reference here and must not be read.
        when(movements.findByTransactionReferenceAndType(eq(REFERENCE), any()))
                .thenReturn(Optional.of(ledgerRow(WALLET_ID, TransactionType.DEPOSIT)));

        assertThat(keys.judge(REFERENCE, WALLET_ID, AMOUNT)).isEmpty();

        verify(withdrawals).findByTransactionReference(REFERENCE);
        verifyNoInteractions(movements);
    }

    static Stream<Arguments> otherWithdrawalsUnderTheKey() {
        return Stream.of(
                Arguments.of(named("another wallet's debit", stored(1099L, "75.0000", WithdrawalStatus.PENDING))),
                Arguments.of(named("another amount", stored(WALLET_ID, "80.0000", WithdrawalStatus.PENDING))),
                Arguments.of(named(
                        "another wallet's refusal",
                        storedRefusal(1099L, WithdrawalRefusalReason.INSUFFICIENT_FUNDS)
                ))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherWithdrawalsUnderTheKey")
    void otherTermsAreAConflict(Withdrawal other) {
        // Guards a corrected retry being handed the old withdrawal as its answer, and a key another user or another
        // currency spent being answered with that withdrawal's receipt or refusal. The terms are judged before the
        // status, so even a refusal of another wallet is a 409, which describes nobody's withdrawal.
        keyHolds(other);

        assertThatThrownBy(() -> keys.judge(REFERENCE, WALLET_ID, AMOUNT))
                .isInstanceOf(ConflictingWithdrawalException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        verifyNoInteractions(movements);
    }

    @Test
    void amountsAreComparedByValue() {
        // Guards a comparison with equals: the stored amount comes back from NUMERIC(19,4) as 75.0000, and a retry
        // that compared 75 against it by scale would get a 409 and be sent to a new key, which moves the money twice.
        keyHolds(storedRefusal(WALLET_ID, WithdrawalRefusalReason.NO_BANK_ACCOUNT));

        assertThat(keys.judge(REFERENCE, WALLET_ID, new BigDecimal("75")))
                .contains(new WithdrawalOutcome.Refused(WithdrawalRefusalReason.NO_BANK_ACCOUNT));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(WithdrawalRefusalReason.class)
    void aRefusedWithdrawalReplaysItsReason(WithdrawalRefusalReason reason) {
        // Guards a recorded refusal being read as a free key, so an automatic retry after a top-up or a bank account
        // set later would pay out a withdrawal the user saw refused. A refusal debited nothing, so the ledger has no
        // row to read.
        keyHolds(storedRefusal(WALLET_ID, reason));

        assertThat(keys.judge(REFERENCE, WALLET_ID, AMOUNT)).contains(new WithdrawalOutcome.Refused(reason));

        verifyNoInteractions(movements);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = WithdrawalStatus.class, names = {"PENDING", "PAID", "RETURN_PENDING", "RETURNED"})
    void aDebitedWithdrawalReplaysItsOwnLedgerRow(WithdrawalStatus status) {
        // Guards a replay answered from anything but the debit's own ledger row, and a status the payout reached
        // since then changing the answer: a paid or returned withdrawal still replays its 202, never a refusal or a
        // second debit.
        keyHolds(stored(WALLET_ID, "75.0000", status));
        BalanceHistory debit = ledgerRow(WALLET_ID, TransactionType.WITHDRAWAL);
        when(movements.findByTransactionReferenceAndType(REFERENCE, TransactionType.WITHDRAWAL))
                .thenReturn(Optional.of(debit));

        Optional<WithdrawalOutcome> outcome = keys.judge(REFERENCE, WALLET_ID, AMOUNT);

        assertThat(outcome).containsInstanceOf(WithdrawalOutcome.Debited.class);
        assertThat(((WithdrawalOutcome.Debited) outcome.orElseThrow()).debit()).isSameAs(debit);
    }

    static Stream<Arguments> missingLedgerRows() {
        return Stream.of(
                Arguments.of(named("no ledger row", Optional.empty())),
                Arguments.of(named(
                        "a ledger row in another wallet",
                        Optional.of(ledgerRow(1099L, TransactionType.WITHDRAWAL))
                ))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingLedgerRows")
    void aDebitedWithdrawalWithoutItsLedgerRowInItsWalletIsADefectNotAReplay(Optional<BalanceHistory> row) {
        // Guards a receipt rebuilt from another wallet's movement, which would show the caller someone else's
        // balance, and a missing row answered as a 409 or a free key, which would invite a second debit. The
        // withdrawal and its ledger row commit together, so either case is a defect and must surface as a 500.
        keyHolds(stored(WALLET_ID, "75.0000", WithdrawalStatus.PENDING));
        when(movements.findByTransactionReferenceAndType(REFERENCE, TransactionType.WITHDRAWAL)).thenReturn(row);

        assertThatThrownBy(() -> keys.judge(REFERENCE, WALLET_ID, AMOUNT))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Withdrawal " + REFERENCE + " has no WITHDRAWAL ledger row in its wallet");
    }

    @Test
    void onlyTheWithdrawalLedgerTypeIsEverRead() {
        // Guards a receipt built from the return's credit, which shares the reference once the money came back: a
        // returned withdrawal holds both rows, and only the WITHDRAWAL one is the debit the 202 describes.
        keyHolds(stored(WALLET_ID, "75.0000", WithdrawalStatus.RETURNED));
        BalanceHistory debit = ledgerRow(WALLET_ID, TransactionType.WITHDRAWAL);
        BalanceHistory credit = ledgerRow(WALLET_ID, TransactionType.WITHDRAWAL_RETURNED);
        when(movements.findByTransactionReferenceAndType(REFERENCE, TransactionType.WITHDRAWAL))
                .thenReturn(Optional.of(debit));
        when(movements.findByTransactionReferenceAndType(REFERENCE, TransactionType.WITHDRAWAL_RETURNED))
                .thenReturn(Optional.of(credit));

        Optional<WithdrawalOutcome> outcome = keys.judge(REFERENCE, WALLET_ID, AMOUNT);

        assertThat(((WithdrawalOutcome.Debited) outcome.orElseThrow()).debit()).isSameAs(debit);
        verify(movements).findByTransactionReferenceAndType(REFERENCE, TransactionType.WITHDRAWAL);
        verifyNoMoreInteractions(movements);
    }
}
