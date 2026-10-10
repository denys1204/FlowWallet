package com.flowwallet.wallet.withdrawal;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowwallet.contract.event.WithdrawalRequestedEvent;
import com.flowwallet.outbox.EventSerializationException;
import com.flowwallet.outbox.Outbox;
import com.flowwallet.platform.exception.ApiException;
import com.flowwallet.wallet.api.WalletNotFoundException;
import com.flowwallet.wallet.balance.BalanceHistory;
import com.flowwallet.wallet.balance.BalanceHistoryRepository;
import com.flowwallet.wallet.balance.Wallet;
import com.flowwallet.wallet.balance.WalletRepository;
import com.flowwallet.wallet.enums.TransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The repositories and the outbox are mocks behind a real {@link WithdrawalKeys}, so these tests pin the order of
 * the calls and the decisions between them, not what Postgres does with them. The lock, the visibility after a wait
 * and the unique indexes are the database's part and need a real one to prove.
 */
class WithdrawalHandlerTest {
    private static final String USER = "3c9a7e52-1b4d-4f86-a0e2-5d7b9c1f3a68";
    private static final String REFERENCE = "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d";
    private static final BigDecimal AMOUNT = new BigDecimal("75.0000");
    private static final WithdrawalTerms TERMS = new WithdrawalTerms(USER, "PLN", REFERENCE, AMOUNT);

    private final Logger logger = (Logger) LoggerFactory.getLogger(WithdrawalHandler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    private final WalletRepository wallets = mock(WalletRepository.class);
    private final BalanceHistoryRepository movements = mock(BalanceHistoryRepository.class);
    private final WithdrawalRepository withdrawals = mock(WithdrawalRepository.class);
    private final Outbox outbox = mock(Outbox.class);
    private final WithdrawalHandler handler = new WithdrawalHandler(
            wallets,
            movements,
            withdrawals,
            new WithdrawalKeys(withdrawals, movements),
            outbox
    );

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
     * The caller's PLN wallet as the lock query returns it, with an id so the rows can carry it and a balance at the
     * scale a {@code NUMERIC(19,4)} column gives it. Wallet ids in this class stay above 127, where boxed Longs stop
     * being cached, so comparing two ids with == fails.
     */
    private Wallet holds(String balance, long lastEntryNo) {
        Wallet wallet = Wallet.builder()
                .id(1011L)
                .userId(USER)
                .currency("PLN")
                .balance(new BigDecimal(balance))
                .version(3L)
                .lastEntryNo(lastEntryNo)
                .build();
        when(wallets.lockByUserIdAndCurrency(USER, "PLN")).thenReturn(Optional.of(wallet));
        return wallet;
    }

    private void keyHolds(Withdrawal withdrawal) {
        when(withdrawals.findByTransactionReference(REFERENCE)).thenReturn(Optional.of(withdrawal));
    }

    private static Withdrawal committed(long walletId, WithdrawalStatus status) {
        return Withdrawal.builder()
                .id(301L)
                .walletId(walletId)
                .transactionReference(REFERENCE)
                .amount(new BigDecimal("75.0000"))
                .status(status)
                .build();
    }

    private static Withdrawal committedRefusal(long walletId, WithdrawalRefusalReason reason) {
        return Withdrawal.builder()
                .id(301L)
                .walletId(walletId)
                .transactionReference(REFERENCE)
                .amount(new BigDecimal("75.0000"))
                .status(WithdrawalStatus.REFUSED)
                .refusalReason(reason)
                .build();
    }

    private BalanceHistory committedDebit() {
        BalanceHistory debit = BalanceHistory.builder()
                .id(501L)
                .walletId(1011L)
                .entryNo(5L)
                .transactionReference(REFERENCE)
                .type(TransactionType.WITHDRAWAL)
                .amount(new BigDecimal("75.0000"))
                .balanceBefore(new BigDecimal("100.0000"))
                .balanceAfter(new BigDecimal("25.0000"))
                .build();
        when(movements.findByTransactionReferenceAndType(REFERENCE, TransactionType.WITHDRAWAL))
                .thenReturn(Optional.of(debit));
        return debit;
    }

    private void verifyNothingWritten() {
        verify(movements, never()).save(any());
        verify(movements, never()).saveAndFlush(any());
        verify(withdrawals, never()).save(any());
        verify(withdrawals, never()).saveAndFlush(any());
        verify(withdrawals, never()).flush();
        verify(wallets, never()).save(any());
        verify(wallets, never()).saveAndFlush(any());
        verifyNoInteractions(outbox);
    }

    private Withdrawal savedRefusal() {
        ArgumentCaptor<Withdrawal> saved = ArgumentCaptor.forClass(Withdrawal.class);
        verify(withdrawals).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    private WithdrawalRequestedEvent sentCommand() {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(outbox).enqueue(anyString(), anyString(), anyString(), event.capture());
        return (WithdrawalRequestedEvent) event.getValue();
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of(
                        named("no bank account", BankAccountState.NOT_SET),
                        "500.0000",
                        WithdrawalRefusalReason.NO_BANK_ACCOUNT
                ),
                Arguments.of(
                        named("insufficient funds", BankAccountState.READY),
                        "74.9900",
                        WithdrawalRefusalReason.INSUFFICIENT_FUNDS
                )
        );
    }

    @Test
    void theWalletIsLockedFirstAndNeverLoadedWithoutTheLock() {
        // Guards a wallet read before the lock, or a second one after it. The lock query would return the managed
        // instance an earlier read left behind, and Hibernate would fail the withdrawal with a version conflict, a
        // 503, whenever a credit committed in between. The key is judged only once the lock is held.
        holds("100.0000", 4L);

        handler.execute(TERMS, BankAccountState.READY);

        InOrder order = inOrder(wallets, withdrawals, movements, outbox);
        order.verify(wallets).lockByUserIdAndCurrency(USER, "PLN");
        order.verify(withdrawals).findByTransactionReference(REFERENCE);
        order.verify(movements).save(any());
        verifyNoMoreInteractions(wallets);
    }

    @Test
    void aWalletMissingUnderTheLockIsNotFoundAndNothingIsJudgedOrWritten() {
        // Guards a key judged or a row written for a wallet the lock did not find. The withdrawals row needs a wallet
        // id, and the status must be 404, which on this path means the caller's own wallet.
        when(wallets.lockByUserIdAndCurrency(USER, "PLN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.execute(TERMS, BankAccountState.READY))
                .isInstanceOf(WalletNotFoundException.class)
                .hasMessage("No PLN wallet")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verifyNoInteractions(withdrawals, movements, outbox);
    }

    @Test
    void aWithdrawalCommittedUnderTheKeyWhileThisOneWaitedIsAnsweredAsAReplayAndWritesNothing() {
        // Guards the worst bug here: a same-key twin that waited on the lock debiting a second time, or being refused
        // for a withdrawal that happened. This twin's preflight saw no bank account and the balance no longer covers
        // the amount, so judging the bank account or the funds before the key would answer a 422 for a withdrawal
        // that is on its way. It must replay the original's ledger row and move nothing.
        Wallet wallet = holds("25.0000", 5L);
        keyHolds(committed(1011L, WithdrawalStatus.PENDING));
        BalanceHistory original = committedDebit();

        WithdrawalOutcome outcome = handler.execute(TERMS, BankAccountState.NOT_SET);

        assertThat(outcome).isEqualTo(new WithdrawalOutcome.Debited(original));
        assertThat(wallet.getBalance()).isEqualByComparingTo("25");
        assertThat(wallet.getLastEntryNo()).isEqualTo(5L);
        verifyNothingWritten();
    }

    @Test
    void aRefusalCommittedUnderTheKeyIsReplayedEvenWhenTheBankAccountIsNowReady() {
        // Guards the automatic retry ADR 0033 exists to stop: the user saw a 422, then set a bank account and topped
        // up, and a client retried the old key. It must get the recorded refusal and never pay out.
        Wallet wallet = holds("500.0000", 5L);
        keyHolds(committedRefusal(1011L, WithdrawalRefusalReason.NO_BANK_ACCOUNT));

        WithdrawalOutcome outcome = handler.execute(TERMS, BankAccountState.READY);

        assertThat(outcome).isEqualTo(new WithdrawalOutcome.Refused(WithdrawalRefusalReason.NO_BANK_ACCOUNT));
        assertThat(wallet.getBalance()).isEqualByComparingTo("500");
        verifyNothingWritten();
    }

    static Stream<Arguments> otherWalletsWithdrawals() {
        return Stream.of(
                Arguments.of(named("its debit", committed(1099L, WithdrawalStatus.PENDING))),
                Arguments.of(named("its refusal", committedRefusal(1099L, WithdrawalRefusalReason.INSUFFICIENT_FUNDS)))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherWalletsWithdrawals")
    void aKeyHeldByAnotherWalletIsAConflictAndWritesNothing(Withdrawal other) {
        // Guards another user's or another currency's withdrawal being answered as this caller's, and a debit made
        // under a key that already names a withdrawal elsewhere. The status must be 409.
        Wallet wallet = holds("100.0000", 5L);
        keyHolds(other);

        assertThatThrownBy(() -> handler.execute(TERMS, BankAccountState.READY))
                .isInstanceOf(ConflictingWithdrawalException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(wallet.getBalance()).isEqualByComparingTo("100");
        verifyNothingWritten();
    }

    @Test
    void aBankAccountThatIsNotSetIsRecordedAsRefusedBeforeTheFundsAreJudged() {
        // Guards the order ADR 0033 sets: with no bank account and too little money at once, the recorded reason
        // must be the bank account, which the user has to fix first. The refusal is written for the caller's wallet
        // under the key, with the amount at the ledger's scale.
        Wallet wallet = holds("10.0000", 5L);

        WithdrawalOutcome outcome = handler.execute(TERMS, BankAccountState.NOT_SET);

        assertThat(outcome).isEqualTo(new WithdrawalOutcome.Refused(WithdrawalRefusalReason.NO_BANK_ACCOUNT));
        Withdrawal refusal = savedRefusal();
        assertThat(refusal.getStatus()).isEqualTo(WithdrawalStatus.REFUSED);
        assertThat(refusal.getRefusalReason()).isEqualTo(WithdrawalRefusalReason.NO_BANK_ACCOUNT);
        assertThat(refusal.getWalletId()).isEqualTo(1011L);
        assertThat(refusal.getTransactionReference()).isEqualTo(REFERENCE);
        assertThat(refusal.getAmount()).isEqualTo(new BigDecimal("75.0000"));
        assertThat(wallet.getBalance()).isEqualByComparingTo("10");
        assertThat(wallet.getLastEntryNo()).isEqualTo(5L);
    }

    @Test
    void anUnaffordableWithdrawalIsRecordedAsRefusedAndLeavesTheBalanceAndEntryNumberAsTheyWere() {
        // Guards a refusal that still changed the wallet: an UPDATE of the balance, or an entry number taken for a
        // ledger row that is never written, which would leave a gap in the wallet's history. One hundredth short is
        // enough to refuse.
        Wallet wallet = holds("74.9900", 5L);

        WithdrawalOutcome outcome = handler.execute(TERMS, BankAccountState.READY);

        assertThat(outcome).isEqualTo(new WithdrawalOutcome.Refused(WithdrawalRefusalReason.INSUFFICIENT_FUNDS));
        Withdrawal refusal = savedRefusal();
        assertThat(refusal.getStatus()).isEqualTo(WithdrawalStatus.REFUSED);
        assertThat(refusal.getRefusalReason()).isEqualTo(WithdrawalRefusalReason.INSUFFICIENT_FUNDS);
        assertThat(refusal.getWalletId()).isEqualTo(1011L);
        assertThat(wallet.getBalance()).isEqualTo(new BigDecimal("74.9900"));
        assertThat(wallet.getLastEntryNo()).isEqualTo(5L);
        verify(movements, never()).save(any());
        verify(movements, never()).saveAndFlush(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void aRefusalIsReturnedRatherThanThrownSoItsRowCommits(
            BankAccountState bankAccount,
            String balance,
            WithdrawalRefusalReason reason
    ) {
        // Guards a refusal thrown out of the transactional method. The rollback would discard the REFUSED row and
        // free the key, and a retry after a top-up would then pay out. The row is flushed here, so a violation it
        // causes fires inside the transaction, where the service can explain it.
        holds(balance, 5L);

        assertThatCode(() -> handler.execute(TERMS, bankAccount)).doesNotThrowAnyException();

        assertThat(savedRefusal().getRefusalReason()).isEqualTo(reason);
        verify(withdrawals, never()).save(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void aRefusalSendsNoCommand(BankAccountState bankAccount, String balance, WithdrawalRefusalReason reason) {
        // Guards a payout of money that was never debited: a refusal writes no ledger row and queues nothing for
        // Payment Service.
        holds(balance, 5L);

        assertThat(handler.execute(TERMS, bankAccount)).isEqualTo(new WithdrawalOutcome.Refused(reason));

        verifyNoInteractions(outbox);
        verify(movements, never()).save(any());
        verify(movements, never()).saveAndFlush(any());
    }

    @Test
    void aWithdrawalSavesTheLedgerRowTheWithdrawalAndTheCommandInThatOrderBeforeOneFlush() {
        // Guards the order of the unique index entries. Same-key withdrawals on two wallets must take the ledger's
        // (reference, type) entry before the withdrawals' reference entry, or two of them could wait on each other.
        // An extra flush, or a saveAndFlush, would insert a row before the next one is saved and change that order,
        // and every constraint must fire inside the one flush after the command is queued.
        holds("100.0000", 5L);

        handler.execute(TERMS, BankAccountState.READY);

        InOrder order = inOrder(movements, withdrawals, outbox);
        order.verify(movements).save(argThat(row -> row.getType() == TransactionType.WITHDRAWAL));
        order.verify(withdrawals).save(argThat(row -> row.getStatus() == WithdrawalStatus.PENDING));
        order.verify(outbox).enqueue(anyString(), anyString(), anyString(), any());
        order.verify(withdrawals).flush();
        verify(withdrawals, times(1)).flush();
        verify(movements, never()).flush();
        verify(movements, never()).saveAndFlush(any());
        verify(withdrawals, never()).saveAndFlush(any());
        verify(wallets, never()).save(any());
        verify(wallets, never()).flush();
    }

    @Test
    void aWithdrawalDebitsTheWalletAndAnswersWithTheLedgerRowItWrote() {
        // Guards a receipt built from anything but the debit's own row, which a replay rebuilds from the stored copy,
        // and a balance that drifts from the ledger. The withdrawals row is PENDING for the caller's wallet under the
        // key, at the ledger's scale.
        Wallet wallet = holds("100.0000", 5L);

        WithdrawalOutcome outcome = handler.execute(TERMS, BankAccountState.READY);

        ArgumentCaptor<BalanceHistory> debit = ArgumentCaptor.forClass(BalanceHistory.class);
        verify(movements).save(debit.capture());
        assertThat(outcome).isEqualTo(new WithdrawalOutcome.Debited(debit.getValue()));
        assertThat(wallet.getBalance()).isEqualByComparingTo("25");
        assertThat(debit.getValue().getType()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(debit.getValue().getWalletId()).isEqualTo(1011L);
        assertThat(debit.getValue().getTransactionReference()).isEqualTo(REFERENCE);
        assertThat(debit.getValue().getAmount()).isEqualTo(new BigDecimal("75.0000"));
        assertThat(debit.getValue().getBalanceBefore()).isEqualByComparingTo("100");
        assertThat(debit.getValue().getBalanceAfter()).isEqualByComparingTo("25");
        assertThat(debit.getValue().getCounterpartyUserId()).isNull();
        assertThat(debit.getValue().getEventId()).isNull();

        ArgumentCaptor<Withdrawal> withdrawal = ArgumentCaptor.forClass(Withdrawal.class);
        verify(withdrawals).save(withdrawal.capture());
        assertThat(withdrawal.getValue().getStatus()).isEqualTo(WithdrawalStatus.PENDING);
        assertThat(withdrawal.getValue().getWalletId()).isEqualTo(1011L);
        assertThat(withdrawal.getValue().getTransactionReference()).isEqualTo(REFERENCE);
        assertThat(withdrawal.getValue().getAmount()).isEqualTo(new BigDecimal("75.0000"));
        assertThat(withdrawal.getValue().getRefusalReason()).isNull();
    }

    @Test
    void theLedgerRowTakesTheWalletsNextEntryNumber() {
        // Guards a debit numbered from a stale counter, or a counter left behind its newest row. Either would give
        // the next movement a number the wallet already used, and the history's cursor would skip or repeat a row.
        Wallet wallet = holds("100.0000", 5L);

        handler.execute(TERMS, BankAccountState.READY);

        ArgumentCaptor<BalanceHistory> debit = ArgumentCaptor.forClass(BalanceHistory.class);
        verify(movements).save(debit.capture());
        assertThat(debit.getValue().getEntryNo()).isEqualTo(6L);
        assertThat(wallet.getLastEntryNo()).isEqualTo(6L);
    }

    @Test
    void theCommandCarriesTheLockedWalletsUserIdAndCurrencyTheDebitedAmountAndNoWalletId() {
        // Guards a command Payment Service would dead-letter for its shape, or whose payout events could not find the
        // debited wallet again: they come back with the command's user id and currency, and the consumer locks by
        // them. The lock query makes the row equal the terms in Postgres; the stub returns a row that differs, so
        // the test can tell which one the command was built from. Every component of the contract is pinned, so a
        // wallet id added to it would fail here.
        Wallet locked = Wallet.builder()
                .id(1011L)
                .userId("8d2e4f6a-0b1c-4d3e-9f5a-7b6c8d9e0f1a")
                .currency("EUR")
                .balance(new BigDecimal("100.0000"))
                .lastEntryNo(5L)
                .build();
        when(wallets.lockByUserIdAndCurrency(USER, "PLN")).thenReturn(Optional.of(locked));

        handler.execute(TERMS, BankAccountState.READY);

        WithdrawalRequestedEvent command = sentCommand();
        assertThat(command).isEqualTo(new WithdrawalRequestedEvent(
                command.eventId(),
                1,
                "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d",
                "8d2e4f6a-0b1c-4d3e-9f5a-7b6c8d9e0f1a",
                new BigDecimal("75.0000"),
                "EUR"
        ));
    }

    @Test
    void theCommandIsKeyedByTheReferenceTypedByItsHeaderValueUnderTheWithdrawalAggregate() {
        // Guards a record key other than the reference, which would spread one withdrawal's resends over partitions,
        // and an event type header Payment Service's listener does not dispatch on, which it would dead-letter. The
        // values are written out because they are what crosses the wire.
        holds("100.0000", 5L);

        handler.execute(TERMS, BankAccountState.READY);

        verify(outbox).enqueue(
                eq("Withdrawal"),
                eq("6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d"),
                eq("WithdrawalRequestedEvent"),
                any(WithdrawalRequestedEvent.class)
        );
    }

    @Test
    void eachCommandGetsAFreshEventIdAndTheContractsSchemaVersion() {
        // Guards a fixed or reused event id, which Payment Service's barrier would take for a redelivery and drop,
        // and an id derived from the reference, which would collide with another message about it. The id is
        // written into the payload, so the outbox's resends keep it.
        holds("200.0000", 5L);
        String secondReference = "a3b5c7d9-e1f2-4a3b-8c4d-5e6f7a8b9c0d";

        handler.execute(TERMS, BankAccountState.READY);
        handler.execute(new WithdrawalTerms(USER, "PLN", secondReference, AMOUNT), BankAccountState.READY);

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(outbox, times(2)).enqueue(anyString(), anyString(), anyString(), events.capture());
        WithdrawalRequestedEvent first = (WithdrawalRequestedEvent) events.getAllValues().get(0);
        WithdrawalRequestedEvent second = (WithdrawalRequestedEvent) events.getAllValues().get(1);
        assertThat(first.eventId())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                .isNotEqualTo(second.eventId())
                .isNotEqualTo(REFERENCE);
        assertThat(second.eventId())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                .isNotEqualTo(secondReference);
        assertThat(first.schemaVersion()).isEqualTo(1);
        assertThat(second.schemaVersion()).isEqualTo(1);
    }

    @Test
    void aFailingEnqueueReachesTheServiceAsItWasThrown() {
        // Guards a catch here committing the debit without its command, which would leave money debited that Payment
        // Service never hears of. The exception must leave the transactional method so the debit rolls back with it.
        holds("100.0000", 5L);
        EventSerializationException failure = new EventSerializationException(
                "Failed to serialize WithdrawalRequestedEvent",
                new IllegalStateException("no serializer")
        );
        doThrow(failure).when(outbox).enqueue(anyString(), anyString(), anyString(), any());

        assertThatThrownBy(() -> handler.execute(TERMS, BankAccountState.READY)).isSameAs(failure);

        verify(withdrawals, never()).flush();
        assertThat(logs.list).noneMatch(line -> line.getFormattedMessage().startsWith("Debited"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BankAccountState.class)
    void aViolationAtTheFlushReachesTheServiceAsItWasThrown(BankAccountState bankAccount) {
        // Guards a catch here turning a fired CHECK into a 409, or a lost race into a 500. WithdrawalService tells a
        // key another wallet won at a unique index from a rule the code let through only by reading again after the
        // rollback, and it gets that chance only if the violation reaches it as it was thrown. READY flushes the
        // debit's rows, NOT_SET the refusal's. The INFO line about a debit runs only after the flush went through.
        holds("100.0000", 5L);
        DataIntegrityViolationException violation = new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"withdrawals_reference_key\""
        );
        doThrow(violation).when(withdrawals).flush();
        when(withdrawals.saveAndFlush(any())).thenThrow(violation);

        assertThatThrownBy(() -> handler.execute(TERMS, bankAccount)).isSameAs(violation);

        assertThat(logs.list).isEmpty();
    }

    @Test
    void aLockFailureReachesTheServiceAsItWasThrown() {
        // Guards a catch here swallowing or rewrapping a deadlock or a lock timeout. WithdrawalService maps it to a
        // 503 that invites a retry with the same key, which is safe only because nothing ran before the lock.
        CannotAcquireLockException deadlock = new CannotAcquireLockException("deadlock detected");
        when(wallets.lockByUserIdAndCurrency(USER, "PLN")).thenThrow(deadlock);

        assertThatThrownBy(() -> handler.execute(TERMS, BankAccountState.READY)).isSameAs(deadlock);

        verifyNoInteractions(withdrawals, movements, outbox);
    }

    @Test
    void theMoneyTransactionIsPinnedToReadCommitted() throws NoSuchMethodException {
        // Guards the isolation being removed or changed. Under REPEATABLE READ a same-key twin that waited on the
        // lock would not see the withdrawal the first one committed, and the lock itself would fail with a
        // serialization error after any wait.
        Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(
                WithdrawalHandler.class.getMethod("execute", WithdrawalTerms.class, BankAccountState.class),
                Transactional.class
        );

        assertThat(transactional).isNotNull();
        assertThat(transactional.isolation()).isEqualTo(Isolation.READ_COMMITTED);
    }

    @Test
    void theHandlerCannotCallPaymentService() {
        // Guards a network call made while the wallet lock is held, which would make every same-wallet request wait
        // on Payment Service and Stripe. The preflight runs before the transaction and the command leaves through
        // the outbox after the commit, so the handler depends on the repositories, the key judgement and the outbox
        // and on nothing else: a client of Payment Service, or anything that holds one, cannot be added unnoticed.
        Class<?>[] collaborators = {
                WalletRepository.class,
                BalanceHistoryRepository.class,
                WithdrawalRepository.class,
                WithdrawalKeys.class,
                Outbox.class
        };
        List<Class<?>> fieldTypes = Arrays.stream(WithdrawalHandler.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getType)
                .toList();
        Constructor<?>[] constructors = WithdrawalHandler.class.getDeclaredConstructors();

        assertThat(fieldTypes).containsExactlyInAnyOrder(collaborators);
        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).containsExactlyInAnyOrder(collaborators);
    }

    /**
     * A debit, a refusal for no bank account, a refusal for funds and the replay of the debit, so that every path
     * that logs runs once.
     */
    private void everyKindOfOutcomeRunsOnce() {
        holds("100.0000", 5L);
        var noBankAccount = new WithdrawalTerms(USER, "PLN", "b4c6d8e0-f2a3-4b4c-9d5e-6f7a8b9c0d1e", AMOUNT);
        var unaffordable = new WithdrawalTerms(USER, "PLN", "c5d7e9f1-a3b4-4c5d-8e6f-7a8b9c0d1e2f", AMOUNT);

        handler.execute(TERMS, BankAccountState.READY);
        handler.execute(noBankAccount, BankAccountState.NOT_SET);
        handler.execute(unaffordable, BankAccountState.READY);
        keyHolds(committed(1011L, WithdrawalStatus.PENDING));
        committedDebit();
        handler.execute(TERMS, BankAccountState.READY);
    }

    @Test
    void noLogLineNamesTheUser() {
        // Guards the user id, the only credential (ADR 0003), reaching a log line on the debit, refusal or replay
        // path; the wallet id and the reference already identify the attempt (ADR 0027). Each path logs one line, so
        // none of them passes the check by logging nothing.
        everyKindOfOutcomeRunsOnce();

        assertThat(logs.list)
                .hasSize(4)
                .allSatisfy(line -> assertThat(line.getFormattedMessage()).doesNotContain(USER));
    }

    @Test
    void eachOutcomeLogsOneLineThatNamesTheWalletAndTheReference() {
        // Guards a line that loses the wallet id or the reference, which are all an operator has to follow one
        // attempt through the logs (ADR 0027), and a path that logs twice or not at all.
        everyKindOfOutcomeRunsOnce();

        assertThat(logs.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        "Debited 75.0000 PLN from wallet 1011 for withdrawal 6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d",
                        "Refused withdrawal b4c6d8e0-f2a3-4b4c-9d5e-6f7a8b9c0d1e from wallet 1011: NO_BANK_ACCOUNT",
                        "Refused withdrawal c5d7e9f1-a3b4-4c5d-8e6f-7a8b9c0d1e2f from wallet 1011: INSUFFICIENT_FUNDS",
                        "Withdrawal 6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d was already decided; "
                                + "answering with the original"
                );
    }
}
