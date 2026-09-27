package com.flowwallet.wallet.transfer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowwallet.platform.exception.ApiException;
import com.flowwallet.wallet.api.WalletNotFoundException;
import com.flowwallet.wallet.balance.BalanceHistory;
import com.flowwallet.wallet.balance.BalanceHistoryRepository;
import com.flowwallet.wallet.balance.InsufficientFundsException;
import com.flowwallet.wallet.balance.Wallet;
import com.flowwallet.wallet.balance.WalletRepository;
import com.flowwallet.wallet.enums.TransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * The repositories are mocks, so these tests pin the order of the calls and the decisions between them, not
 * what Postgres does with them. The locks, the visibility after a wait and the unique index are the
 * database's part and need a real one to prove.
 */
class TransferHandlerTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(TransferHandler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void attachLogAppender() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogAppender() {
        logger.detachAppender(logs);
    }

    /**
     * Sorts before {@link #BOB}, so which of the two is locked first is known in every test.
     */
    private static final String ALICE = "1f0c6d0e-5d4b-4c7e-9a2f-3b8e7d6c5a41";
    private static final String BOB = "9a7b3c2d-1e0f-4a5b-8c6d-7e8f9a0b1c2d";
    private static final String CAROL = "5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9";
    private static final String REFERENCE = "7e1855b3-4d95-4a72-a0c9-ef0d78be2e44";
    private static final BigDecimal AMOUNT = new BigDecimal("25.0000");

    private final WalletRepository wallets = mock(WalletRepository.class);
    private final BalanceHistoryRepository movements = mock(BalanceHistoryRepository.class);
    private final TransferHandler handler = new TransferHandler(wallets, movements);

    /**
     * Built with an id, because {@link Wallet#open} leaves it null. The handler passes the sender wallet's id
     * to {@code isRepeatOf}, so without one a replay could never match, and the legs would carry no wallet id
     * to check. The balance has the scale a {@code NUMERIC(19,4)} column gives it.
     */
    private Wallet holds(long id, String userId, String balance) {
        return holds(id, userId, balance, 0L);
    }

    private Wallet holds(long id, String userId, String balance, long lastEntryNo) {
        Wallet wallet = Wallet.builder()
                .id(id)
                .userId(userId)
                .currency("USD")
                .balance(new BigDecimal(balance))
                .version(0L)
                .lastEntryNo(lastEntryNo)
                .build();
        when(wallets.lockByUserIdAndCurrency(userId, "USD")).thenReturn(Optional.of(wallet));
        return wallet;
    }

    private void holdsNoWallet(String userId) {
        when(wallets.lockByUserIdAndCurrency(userId, "USD")).thenReturn(Optional.empty());
    }

    private void keyAlreadyMade(BalanceHistory out) {
        when(movements.findByTransactionReferenceAndType(REFERENCE, TransactionType.TRANSFER_OUT))
                .thenReturn(Optional.of(out));
    }

    private static BalanceHistory storedOut(long walletId, String recipient, String amount) {
        return BalanceHistory.builder()
                .id(501L)
                .walletId(walletId)
                .transactionReference(REFERENCE)
                .type(TransactionType.TRANSFER_OUT)
                .counterpartyUserId(recipient)
                .amount(new BigDecimal(amount))
                .balanceBefore(new BigDecimal("100.0000"))
                .balanceAfter(new BigDecimal("100.0000").subtract(new BigDecimal(amount)))
                .build();
    }

    private TransferCommand command(String sender, String recipient) {
        return new TransferCommand(sender, recipient, "USD", REFERENCE, AMOUNT);
    }

    private void verifyNothingWritten() {
        verify(movements, never()).save(any());
        verify(movements, never()).saveAndFlush(any());
        verify(wallets, never()).save(any());
        verify(wallets, never()).saveAndFlush(any());
    }

    @ParameterizedTest(name = "from {0} to {1}")
    @CsvSource({
            ALICE + ", " + BOB,
            BOB + ", " + ALICE
    })
    void bothWalletsAreLockedInAscendingUserIdOrderWhicheverWayTheMoneyGoes(String sender, String recipient) {
        // Guards the deadlock between concurrent A to B and B to A. If each locked its sender first, each
        // would hold one row and wait for the other's, until Postgres aborted one of them. Also guards any
        // other read of a wallet in this transaction: one before the locks would leave a managed instance for
        // the lock query to return, and contention would end in version conflicts instead of waits.
        holds(11L, ALICE, "100.0000");
        holds(12L, BOB, "100.0000");

        handler.execute(command(sender, recipient));

        InOrder order = inOrder(wallets);
        order.verify(wallets).lockByUserIdAndCurrency(ALICE, "USD");
        order.verify(wallets).lockByUserIdAndCurrency(BOB, "USD");
        verify(wallets, times(2)).lockByUserIdAndCurrency(anyString(), anyString());
        verifyNoMoreInteractions(wallets);
    }

    @Test
    void theKeyIsJudgedOnlyAfterBothLocksAreHeld() {
        // Guards a same-key retry passing an unlocked check while the original is still in flight, then
        // reaching the debit or the unique index. The sender sorts last here, so a lookup placed right after
        // the first lock would run before the sender's lock is held.
        holds(11L, ALICE, "100.0000");
        holds(12L, BOB, "100.0000");

        handler.execute(command(BOB, ALICE));

        InOrder order = inOrder(wallets, movements);
        order.verify(wallets).lockByUserIdAndCurrency(ALICE, "USD");
        order.verify(wallets).lockByUserIdAndCurrency(BOB, "USD");
        order.verify(movements).findByTransactionReferenceAndType(REFERENCE, TransactionType.TRANSFER_OUT);
    }

    @ParameterizedTest(name = "from {0} to {1}")
    @CsvSource({
            ALICE + ", " + BOB,
            BOB + ", " + ALICE
    })
    void aRetryOfACompletedTransferReturnsTheOriginalEvenWhenTheBalanceNoLongerCoversIt(
            String sender,
            String recipient
    ) {
        // The worst bug here: a 422 for a transfer that succeeded. The original spent the whole balance, and
        // its response was lost. The retry must be judged by the key before the funds, and move nothing. Run
        // both ways, because comparing against the first-locked wallet instead of the sender's would still
        // pass when the sender sorts first, and answer a real replay with 409 when it sorts last.
        Wallet from = holds(11L, sender, "0.0000");
        holds(12L, recipient, "30.0000");
        BalanceHistory original = storedOut(11L, recipient, "25.0000");
        keyAlreadyMade(original);

        TransferResponse response = handler.execute(command(sender, recipient));

        assertThat(response).isEqualTo(TransferResponse.of(original, "USD"));
        assertThat(from.getBalance()).isEqualByComparingTo("0");
        verifyNothingWritten();
    }

    static Stream<Arguments> otherTransfersUnderTheKey() {
        return Stream.of(
                Arguments.of(named("another amount", storedOut(11L, BOB, "30.0000"))),
                Arguments.of(named("another recipient", storedOut(11L, CAROL, "25.0000"))),
                Arguments.of(named("another sender wallet", storedOut(99L, BOB, "25.0000"))),
                Arguments.of(named("a transfer the caller received under this key", storedOut(99L, ALICE, "25.0000")))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherTransfersUnderTheKey")
    void aKeyAlreadyUsedForADifferentTransferIsAConflict(BalanceHistory other) {
        // Guards a corrected retry being handed the old transfer as a success, and a recipient that reuses a
        // key read from its own history being answered with someone else's transfer. The status must stay
        // 409, the one answer on this path that sends the client to a new key.
        Wallet alice = holds(11L, ALICE, "100.0000");
        holds(12L, BOB, "100.0000");
        keyAlreadyMade(other);

        assertThatThrownBy(() -> handler.execute(command(ALICE, BOB)))
                .isInstanceOf(ConflictingTransferException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(alice.getBalance()).isEqualByComparingTo("100");
        verifyNothingWritten();
    }

    @ParameterizedTest(name = "from {0} to {1}")
    @CsvSource({
            ALICE + ", " + BOB,
            BOB + ", " + ALICE
    })
    void aCallerWithoutAWalletIsNotFoundWhicheverIdSortsFirst(String sender, String recipient) {
        // Guards the precedence of the refusals depending on the lock order, and the ledger being read for a
        // caller who holds nothing. Both locks are still taken, because both come before any decision. The
        // status must be 404, which on this path always means the caller's own wallet.
        holdsNoWallet(sender);
        holds(12L, recipient, "100.0000");

        assertThatThrownBy(() -> handler.execute(command(sender, recipient)))
                .isInstanceOf(WalletNotFoundException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(wallets, times(2)).lockByUserIdAndCurrency(anyString(), anyString());
        verifyNoInteractions(movements);
    }

    @ParameterizedTest(name = "from {0} to {1}")
    @CsvSource({
            ALICE + ", " + BOB,
            BOB + ", " + ALICE
    })
    void anUnaffordableTransferIsRefusedBeforeTheRecipientIsLookedAt(String sender, String recipient) {
        // Guards the order that limits what a caller learns about others. With insufficient funds and no
        // recipient wallet at once, the answer must be about the funds: otherwise any caller could ask, for
        // free, whether a user holds a wallet in this currency.
        holds(11L, sender, "10.0000");
        holdsNoWallet(recipient);

        assertThatThrownBy(() -> handler.execute(command(sender, recipient)))
                .isInstanceOf(InsufficientFundsException.class);

        verifyNothingWritten();
    }

    @ParameterizedTest(name = "from {0} to {1}")
    @CsvSource({
            ALICE + ", " + BOB,
            BOB + ", " + ALICE
    })
    void aRecipientWithoutAWalletIsRefusedAndNothingIsCreatedOrWritten(String sender, String recipient) {
        // Guards a wallet opened as a side effect of a transfer, and money debited with nowhere to go. The
        // debit is made in memory only, and no statement runs before the refusal, so the rollback discards it
        // with nothing written.
        // Run both ways, because taking the recipient from the wrong lock would find the sender's own wallet
        // when the recipient sorts first. The status must be 422: a 404 on this path means the caller's own
        // wallet is missing.
        holds(11L, sender, "100.0000");
        holdsNoWallet(recipient);

        assertThatThrownBy(() -> handler.execute(command(sender, recipient)))
                .isInstanceOf(RecipientHasNoWalletException.class)
                .hasMessage("The recipient holds no USD wallet")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        verifyNothingWritten();
        verify(movements, never()).flush();
        verify(wallets, never()).flush();
        // Guards the sender's and the recipient's user ids leaking into the log line: they are the only
        // credential (ADR 0003), and the sending wallet's own id already identifies the attempt (ADR 0027).
        assertThat(logs.list)
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getFormattedMessage()).doesNotContain(sender, recipient).contains("Wallet 11");
                });
    }

    @ParameterizedTest(name = "from {0} to {1}")
    @CsvSource({
            ALICE + ", " + BOB,
            BOB + ", " + ALICE
    })
    void aTransferMovesTheMoneyAndWritesBothLegsUnderOneReference(String sender, String recipient) {
        // Guards balances drifting from the ledger, counterparties that are swapped or missing, and wrong
        // before and after values on either leg. The receipt is the sender's, at the ledger's scale. Run both
        // ways, because a mix-up between the first-locked wallet and the recipient shows only when the sender
        // sorts last: the sender would pay itself and the recipient would get nothing.
        Wallet from = holds(11L, sender, "100.0000");
        Wallet to = holds(12L, recipient, "5.0000");

        TransferResponse response = handler.execute(command(sender, recipient));

        assertThat(from.getBalance()).isEqualByComparingTo("75");
        assertThat(to.getBalance()).isEqualByComparingTo("30");

        ArgumentCaptor<BalanceHistory> out = ArgumentCaptor.forClass(BalanceHistory.class);
        ArgumentCaptor<BalanceHistory> in = ArgumentCaptor.forClass(BalanceHistory.class);
        verify(movements).save(out.capture());
        verify(movements).saveAndFlush(in.capture());

        assertThat(out.getValue().getType()).isEqualTo(TransactionType.TRANSFER_OUT);
        assertThat(out.getValue().getWalletId()).isEqualTo(11L);
        assertThat(out.getValue().getCounterpartyUserId()).isEqualTo(recipient);
        assertThat(out.getValue().getBalanceBefore()).isEqualByComparingTo("100");
        assertThat(out.getValue().getBalanceAfter()).isEqualByComparingTo("75");

        assertThat(in.getValue().getType()).isEqualTo(TransactionType.TRANSFER_IN);
        assertThat(in.getValue().getWalletId()).isEqualTo(12L);
        assertThat(in.getValue().getCounterpartyUserId()).isEqualTo(sender);
        assertThat(in.getValue().getBalanceBefore()).isEqualByComparingTo("5");
        assertThat(in.getValue().getBalanceAfter()).isEqualByComparingTo("30");

        assertThat(out.getValue().getTransactionReference()).isEqualTo(REFERENCE);
        assertThat(in.getValue().getTransactionReference()).isEqualTo(REFERENCE);

        // Compared with equals, so the scale is pinned as well as the value.
        assertThat(response).isEqualTo(new TransferResponse(
                REFERENCE, recipient, new BigDecimal("25.0000"), "USD", new BigDecimal("75.0000")
        ));
        // The balances change on the managed entities and reach the database through the flush.
        verify(wallets, never()).save(any());
        // Guards both sides' user ids leaking into the completed-transfer log line; the wallet ids already
        // identify both legs (docs/adr/0027-user-ids-stay-out-of-logs-and-provider-metadata.md).
        assertThat(logs.list)
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getFormattedMessage())
                            .doesNotContain(sender, recipient)
                            .contains("wallet 11")
                            .contains("wallet 12");
                });
    }

    @ParameterizedTest(name = "from {0} to {1}")
    @CsvSource({
            ALICE + ", " + BOB,
            BOB + ", " + ALICE
    })
    void eachLegTakesTheNextEntryNumberOfItsOwnWallet(String sender, String recipient) {
        // Guards a leg numbered from the other wallet's counter, or from the first-locked wallet whatever its
        // role, and a transfer that advances one counter but not the other. Either would give a leg a number
        // its wallet already used, or leave a wallet's counter behind its newest row. The counters differ so
        // that a mix-up shows, and the test runs both ways because the lock order swaps the wallets.
        Wallet from = holds(11L, sender, "100.0000", 1L);
        Wallet to = holds(12L, recipient, "5.0000", 6L);

        handler.execute(command(sender, recipient));

        ArgumentCaptor<BalanceHistory> out = ArgumentCaptor.forClass(BalanceHistory.class);
        ArgumentCaptor<BalanceHistory> in = ArgumentCaptor.forClass(BalanceHistory.class);
        verify(movements).save(out.capture());
        verify(movements).saveAndFlush(in.capture());
        assertThat(out.getValue().getEntryNo()).isEqualTo(2L);
        assertThat(in.getValue().getEntryNo()).isEqualTo(7L);
        assertThat(from.getLastEntryNo()).isEqualTo(2L);
        assertThat(to.getLastEntryNo()).isEqualTo(7L);
    }

    @Test
    void theOutgoingLegIsPersistedBeforeTheIncomingOneAndFlushedOnce() {
        // Guards the order of the index entries, which keeps two transfers racing on one key from different
        // wallets out of a cycle, and an extra flush between the legs that would take them in another order.
        holds(11L, ALICE, "100.0000");
        holds(12L, BOB, "5.0000");

        handler.execute(command(ALICE, BOB));

        InOrder order = inOrder(movements);
        order.verify(movements).save(argThat(leg -> leg.getType() == TransactionType.TRANSFER_OUT));
        order.verify(movements).saveAndFlush(argThat(leg -> leg.getType() == TransactionType.TRANSFER_IN));
        verify(movements, times(1)).save(any());
        verify(movements, times(1)).saveAndFlush(any());
        verify(movements, never()).flush();
        verify(wallets, never()).flush();
        verify(wallets, never()).saveAndFlush(any());
    }

    @Test
    void aViolationAtTheFlushReachesTheServiceAsItWasThrown() {
        // Guards a catch here turning a fired CHECK into a 409, the rejected PaymentTransactionStore.reserve
        // shape. TransferService tells a key lost at the unique index from a rule the code let through only by
        // reading the ledger after the rollback, and it gets that chance only if the violation reaches it as
        // it was thrown.
        holds(11L, ALICE, "100.0000");
        holds(12L, BOB, "5.0000");
        DataIntegrityViolationException violation = new DataIntegrityViolationException(
                "new row for relation \"wallets\" violates check constraint \"wallets_balance_not_negative\""
        );
        when(movements.saveAndFlush(any())).thenThrow(violation);

        assertThatThrownBy(() -> handler.execute(command(ALICE, BOB))).isSameAs(violation);
    }

    @Test
    void aLockFailureReachesTheServiceAsItWasThrown() {
        // Guards a catch here swallowing or rewrapping a deadlock or a lock timeout. TransferService maps it to
        // a 503 that invites a retry with the same key, which is safe only because nothing committed.
        holds(11L, ALICE, "100.0000");
        CannotAcquireLockException deadlock = new CannotAcquireLockException("deadlock detected");
        when(wallets.lockByUserIdAndCurrency(BOB, "USD")).thenThrow(deadlock);

        assertThatThrownBy(() -> handler.execute(command(ALICE, BOB))).isSameAs(deadlock);

        verifyNoInteractions(movements);
    }

    @Test
    void theMoneyTransactionIsPinnedToReadCommitted() throws NoSuchMethodException {
        // Guards the isolation being removed or changed. Under REPEATABLE READ a same-key retry that waited
        // on the sender's lock would not see the legs the original committed, and the lock itself would fail
        // with a serialization error after any wait.
        Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(
                TransferHandler.class.getMethod("execute", TransferCommand.class), Transactional.class
        );

        assertThat(transactional).isNotNull();
        assertThat(transactional.isolation()).isEqualTo(Isolation.READ_COMMITTED);
    }
}
