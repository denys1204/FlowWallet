package com.flowwallet.wallet.withdrawal;

import com.flowwallet.platform.exception.ApiException;
import com.flowwallet.wallet.api.InvalidAmountException;
import com.flowwallet.wallet.api.InvalidCurrencyException;
import com.flowwallet.wallet.api.WalletNotFoundException;
import com.flowwallet.wallet.balance.BalanceHistory;
import com.flowwallet.wallet.balance.BalanceHistoryRepository;
import com.flowwallet.wallet.balance.Wallet;
import com.flowwallet.wallet.balance.WalletRepository;
import com.flowwallet.wallet.deposit.PaymentUnavailableException;
import com.flowwallet.wallet.enums.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.AnnotatedElement;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The handler and the preflight are mocks and {@link WithdrawalKeys} is real over mocked repositories, so these tests
 * pin which step runs, in which order and with which values, and how the service answers what the handler returns or
 * throws. The handler's own order is pinned in {@code WithdrawalHandlerTest}, the preflight's translation in
 * {@code WithdrawalPreflightTest}.
 */
@ExtendWith(OutputCaptureExtension.class)
class WithdrawalServiceTest {
    private static final String USER = "3c9a7e52-1b4d-4f86-a0e2-5d7b9c1f3a68";
    private static final String KEY = "6F1D4C2A-8B3E-4F5A-9C7D-1E2F3A4B5C6D";
    private static final String REFERENCE = "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d";
    private static final WithdrawalTerms TERMS =
            new WithdrawalTerms(USER, "PLN", REFERENCE, new BigDecimal("75.0000"));

    private final WithdrawalHandler handler = mock(WithdrawalHandler.class);
    private final WithdrawalPreflight preflight = mock(WithdrawalPreflight.class);
    private final WalletRepository wallets = mock(WalletRepository.class);
    private final WithdrawalRepository withdrawals = mock(WithdrawalRepository.class);
    private final BalanceHistoryRepository movements = mock(BalanceHistoryRepository.class);
    private final WithdrawalService service = new WithdrawalService(
            handler,
            new WithdrawalKeys(withdrawals, movements),
            preflight,
            wallets,
            withdrawals
    );

    private final DataIntegrityViolationException violation =
            new DataIntegrityViolationException("duplicate key value violates unique constraint");

    private static WithdrawalRequest request(String amount) {
        return new WithdrawalRequest(new BigDecimal(amount));
    }

    private WithdrawalResponse withdraw() {
        return service.withdraw(USER, "PLN", KEY, request("75.00"));
    }

    /**
     * The caller's PLN wallet as the unlocked read finds it. Only its id matters to the service, which never judges
     * the balance; the balance is large to show that a refusal is replayed whatever the wallet holds now. Wallet ids
     * in this class stay above 127, where boxed Longs stop being cached, so comparing two ids with == fails.
     */
    private void callerHoldsWallet() {
        Wallet wallet = Wallet.builder()
                .id(1011L)
                .userId(USER)
                .currency("PLN")
                .balance(new BigDecimal("500.0000"))
                .build();
        when(wallets.findByUserIdAndCurrency(USER, "PLN")).thenReturn(Optional.of(wallet));
    }

    private static Withdrawal stored(long walletId, WithdrawalStatus status) {
        return Withdrawal.builder()
                .id(301L)
                .walletId(walletId)
                .transactionReference(REFERENCE)
                .amount(new BigDecimal("75.0000"))
                .status(status)
                .build();
    }

    private static Withdrawal storedRefusal(WithdrawalRefusalReason reason) {
        return Withdrawal.builder()
                .id(301L)
                .walletId(1011L)
                .transactionReference(REFERENCE)
                .amount(new BigDecimal("75.0000"))
                .status(WithdrawalStatus.REFUSED)
                .refusalReason(reason)
                .build();
    }

    /**
     * The debit's ledger row as {@code NUMERIC(19,4)} gives it back.
     */
    private static BalanceHistory debit() {
        return BalanceHistory.builder()
                .id(501L)
                .walletId(1011L)
                .entryNo(6L)
                .transactionReference(REFERENCE)
                .type(TransactionType.WITHDRAWAL)
                .amount(new BigDecimal("75.0000"))
                .balanceBefore(new BigDecimal("100.0000"))
                .balanceAfter(new BigDecimal("25.0000"))
                .build();
    }

    private void ledgerHoldsTheDebit() {
        when(movements.findByTransactionReferenceAndType(REFERENCE, TransactionType.WITHDRAWAL))
                .thenReturn(Optional.of(debit()));
    }

    /**
     * The key is free when the service judges it, so the request goes on to the handler, and {@code later} is what
     * the reads after the handler find.
     */
    private void keyTakenWhileTheHandlerRan(Withdrawal later) {
        when(withdrawals.findByTransactionReference(REFERENCE)).thenReturn(Optional.empty(), Optional.of(later));
    }

    private void handlerFails(RuntimeException failure) {
        when(preflight.check(any())).thenReturn(BankAccountState.READY);
        when(handler.execute(any(), any())).thenThrow(failure);
    }

    /**
     * The service holds the withdrawals and wallets repositories only to read, and the ledger through the keys, so
     * any write through them is a row the handler's transaction should have written.
     */
    private void verifyNothingWritten() {
        verify(withdrawals, never()).save(any());
        verify(withdrawals, never()).saveAndFlush(any());
        verify(withdrawals, never()).flush();
        verify(wallets, never()).save(any());
        verify(wallets, never()).saveAndFlush(any());
        verify(movements, never()).save(any());
        verify(movements, never()).saveAndFlush(any());
        verify(movements, never()).flush();
    }

    @Test
    void theCurrencyTheKeyAndTheAmountAreNormalisedBeforeAnythingIsLookedUp() {
        // Guards two spellings of one key becoming two withdrawals, a lower-case currency missing the wallet that
        // exists, and an amount that reaches a query or the handler at another scale than the ledger's. The terms
        // compare equal only if every value is in its canonical form.
        callerHoldsWallet();
        when(preflight.check(any())).thenReturn(BankAccountState.READY);
        when(handler.execute(any(), any())).thenReturn(new WithdrawalOutcome.Debited(debit()));

        WithdrawalResponse response = service.withdraw(USER, "pln", KEY, request("75"));

        verify(wallets).findByUserIdAndCurrency(USER, "PLN");
        verify(withdrawals).findByTransactionReference("6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d");
        verify(preflight).check(new WithdrawalTerms(USER, "PLN", REFERENCE, new BigDecimal("75.0000")));
        verify(handler).execute(
                new WithdrawalTerms(USER, "PLN", REFERENCE, new BigDecimal("75.0000")),
                BankAccountState.READY
        );
        assertThat(response).isEqualTo(new WithdrawalResponse(REFERENCE, "75.00", "PLN", "25.00"));
    }

    static Stream<Arguments> amountsOffTheGrid() {
        return Stream.of(
                Arguments.of(named("half a yen", "JPY"), "1.5", "JPY amounts must be whole units"),
                Arguments.of(
                        named("a tenth of a grosz", "pln"),
                        "75.001",
                        "PLN amounts carry at most 2 decimal places"
                ),
                Arguments.of(
                        named("more than a balance can hold", "PLN"),
                        "1000000000000000",
                        "Amount does not fit a wallet balance"
                )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("amountsOffTheGrid")
    void anAmountOffTheCurrencysGridIsRefusedBeforeAnyQueryRuns(String currency, String amount, String detail) {
        // Guards the precision check being skipped, or judged against anything but the path's currency. An amount
        // that can never be paid out must never reach a balance, and refusing it takes no connection. The status
        // must be 400, a mistake in the request, with nothing written and the key free.
        assertThatThrownBy(() -> service.withdraw(USER, currency, KEY, request(amount)))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessage(detail)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(wallets, withdrawals, movements, preflight, handler);
    }

    @Test
    void aCurrencyThatIsNotIsoIsRefusedBeforeAnyQueryRuns() {
        // Guards an unknown code reaching the wallet query, where it would read as a missing wallet, a 404 that
        // sends the client to open one.
        assertThatThrownBy(() -> service.withdraw(USER, "QQQ", KEY, request("75.00")))
                .isInstanceOf(InvalidCurrencyException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(wallets, withdrawals, movements, preflight, handler);
    }

    @Test
    void aMissingWalletIsRefusedBeforeTheKeyOrPaymentServiceIsAsked() {
        // Guards Payment Service being asked about a withdrawal from a wallet that does not exist, and a key judged
        // against a wallet id that is not there. The status must be 404 for the caller's own wallet.
        when(wallets.findByUserIdAndCurrency(USER, "PLN")).thenReturn(Optional.empty());

        assertThatThrownBy(this::withdraw)
                .isInstanceOf(WalletNotFoundException.class)
                .hasMessage("No PLN wallet")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verifyNoInteractions(withdrawals, movements, preflight, handler);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = WithdrawalStatus.class, names = {"PENDING", "PAID", "RETURN_PENDING", "RETURNED"})
    void aReplayOfADebitedWithdrawalIsAnsweredFromItsLedgerRowWithoutAskingPaymentService(WithdrawalStatus status) {
        // Guards a lost 202 whose retry asks Payment Service again: it would fail while Payment Service is down, or
        // be refused after the minimum was raised or the bank account removed, for a withdrawal that was debited.
        // The answer is the first one, rebuilt from the stored row, whatever the payout reached since.
        callerHoldsWallet();
        when(withdrawals.findByTransactionReference(REFERENCE)).thenReturn(Optional.of(stored(1011L, status)));
        ledgerHoldsTheDebit();

        assertThat(withdraw()).isEqualTo(new WithdrawalResponse(REFERENCE, "75.00", "PLN", "25.00"));

        verifyNoInteractions(preflight, handler);
    }

    @Test
    void aReplayOfARefusedWithdrawalGetsTheRecordedRefusalEvenAfterATopUp() {
        // Guards the automatic retry ADR 0033 exists to stop: the wallet now holds 500.00, and a refusal judged
        // again would pay out a withdrawal the user saw refused. The detail must say the key is spent.
        callerHoldsWallet();
        when(withdrawals.findByTransactionReference(REFERENCE))
                .thenReturn(Optional.of(storedRefusal(WithdrawalRefusalReason.INSUFFICIENT_FUNDS)));

        assertThatThrownBy(this::withdraw)
                .isInstanceOf(WithdrawalRefusedException.class)
                .hasMessage(
                        "Insufficient funds in the PLN wallet. This Idempotency-Key is spent; withdraw a smaller "
                                + "amount or top up, then use a new key."
                )
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        verifyNoInteractions(preflight, handler, movements);
    }

    @Test
    void aKeyHeldByAnotherWalletsWithdrawalIsAConflictWithoutAskingPaymentService() {
        // Guards a key another user or another currency spent reaching Payment Service and the handler, and being
        // answered with that withdrawal. The status must be 409, which sends the client to a new key.
        callerHoldsWallet();
        when(withdrawals.findByTransactionReference(REFERENCE))
                .thenReturn(Optional.of(stored(1099L, WithdrawalStatus.PENDING)));

        assertThatThrownBy(this::withdraw)
                .isInstanceOf(ConflictingWithdrawalException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        verifyNoInteractions(preflight, handler, movements);
        verifyNothingWritten();
    }

    @Test
    void theSameTermsReachThePreflightAndTheHandler() {
        // Guards Payment Service approving one amount and the wallet debiting another. The request's 75.000 is
        // brought to the ledger's scale once, and equal terms go to the preflight and to the handler, which debits
        // them and puts the amount in the command (WithdrawalHandlerTest pins that half).
        callerHoldsWallet();
        when(preflight.check(any())).thenReturn(BankAccountState.READY);
        when(handler.execute(any(), any())).thenReturn(new WithdrawalOutcome.Debited(debit()));

        service.withdraw(USER, "PLN", KEY, request("75.000"));

        ArgumentCaptor<WithdrawalTerms> checked = ArgumentCaptor.forClass(WithdrawalTerms.class);
        ArgumentCaptor<WithdrawalTerms> executed = ArgumentCaptor.forClass(WithdrawalTerms.class);
        verify(preflight).check(checked.capture());
        verify(handler).execute(executed.capture(), any());
        assertThat(checked.getValue().amount()).isEqualTo(new BigDecimal("75.0000"));
        assertThat(executed.getValue()).isEqualTo(checked.getValue());
    }

    static Stream<Arguments> preflightRefusals() {
        return Stream.of(
                Arguments.of(named(
                        "terms refused, 400",
                        new WithdrawalRejectedException("The amount is below the smallest withdrawal in this currency.")
                )),
                Arguments.of(named(
                        "Payment Service unavailable, 502",
                        new PaymentUnavailableException(
                                "Payment Service is unavailable. Nothing was debited. Retry with the same "
                                        + "Idempotency-Key."
                        )
                )),
                Arguments.of(named("reserve short, 503", new WithdrawalsUnavailableException()))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("preflightRefusals")
    void aPreflightRefusalNeverReachesTheHandler(ApiException refusal) {
        // Guards a debit after Payment Service said it cannot pay out, or could not say: these answers leave nothing
        // written and the key free, and they must reach the client as the preflight threw them.
        callerHoldsWallet();
        when(preflight.check(any())).thenThrow(refusal);

        assertThatThrownBy(this::withdraw).isSameAs(refusal);

        verifyNoInteractions(handler);
        verifyNothingWritten();
    }

    @Test
    void aBankAccountThatIsNotSetReachesTheHandlerAsNotSet() {
        // Guards the service refusing a missing bank account itself, before the lock. The refusal must be judged
        // after the key under the lock and recorded, so the service passes the reading on and answers what the
        // handler decided.
        callerHoldsWallet();
        when(preflight.check(any())).thenReturn(BankAccountState.NOT_SET);
        when(handler.execute(any(), any()))
                .thenReturn(new WithdrawalOutcome.Refused(WithdrawalRefusalReason.NO_BANK_ACCOUNT));

        assertThatThrownBy(this::withdraw).isInstanceOf(WithdrawalRefusedException.class);

        verify(handler).execute(TERMS, BankAccountState.NOT_SET);
    }

    static Stream<Arguments> recordedRefusals() {
        return Stream.of(
                Arguments.of(
                        WithdrawalRefusalReason.NO_BANK_ACCOUNT,
                        "No bank account is ready for payouts in PLN. This Idempotency-Key is spent; set a bank "
                                + "account, then use a new key."
                ),
                Arguments.of(
                        WithdrawalRefusalReason.INSUFFICIENT_FUNDS,
                        "Insufficient funds in the PLN wallet. This Idempotency-Key is spent; withdraw a smaller "
                                + "amount or top up, then use a new key."
                )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recordedRefusals")
    void aRefusalTheHandlerReturnedBecomesA422(WithdrawalRefusalReason reason, String detail) {
        // Guards a recorded refusal answered as a success, or as a status that invites a retry with the same key.
        // The detail names the currency and the reason and says the key is spent.
        callerHoldsWallet();
        when(preflight.check(any())).thenReturn(BankAccountState.READY);
        when(handler.execute(any(), any())).thenReturn(new WithdrawalOutcome.Refused(reason));

        assertThatThrownBy(this::withdraw)
                .isInstanceOf(WithdrawalRefusedException.class)
                .hasMessage(detail)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void aViolationForAKeyAnotherWalletHoldsIsAConflict() {
        // Guards a same-key race between two wallets being answered as a 500, or with the other wallet's
        // withdrawal. The other one committed while this one ran and won at the unique index.
        callerHoldsWallet();
        keyTakenWhileTheHandlerRan(stored(1099L, WithdrawalStatus.PENDING));
        handlerFails(violation);

        assertThatThrownBy(this::withdraw)
                .isInstanceOf(ConflictingWithdrawalException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aViolationForTheCallersOwnMatchingWithdrawalIsAnsweredAsAReplay() {
        // Guards the backstop answering the caller's own matching withdrawal with a 409. Requests on one wallet
        // serialize on its lock and should never meet at an index, but if that argument ever fails, a 409 would send
        // the client to a new key and move the money twice.
        callerHoldsWallet();
        keyTakenWhileTheHandlerRan(stored(1011L, WithdrawalStatus.PENDING));
        ledgerHoldsTheDebit();
        handlerFails(violation);

        assertThat(withdraw()).isEqualTo(new WithdrawalResponse(REFERENCE, "75.00", "PLN", "25.00"));
    }

    @Test
    void aViolationForTheCallersOwnRecordedRefusalIsAnsweredWithThatRefusal() {
        // Guards the backstop answering a same-key twin's recorded refusal with a 409 or a 500: the key is spent on
        // that refusal, and the same request must get the same 422.
        callerHoldsWallet();
        keyTakenWhileTheHandlerRan(storedRefusal(WithdrawalRefusalReason.NO_BANK_ACCOUNT));
        handlerFails(violation);

        assertThatThrownBy(this::withdraw)
                .isInstanceOf(WithdrawalRefusedException.class)
                .hasMessageStartingWith("No bank account is ready for payouts in PLN.");
    }

    @Test
    void aViolationNoWithdrawalExplainsIsRethrownRatherThanReportedAsAConflict() {
        // Guards a fired CHECK or an overflowed column, which means a rule was bypassed, being reported to the
        // client as "key already used". The key is read again after the rollback, and finding nothing there it
        // must surface as the original exception, and so as a 500.
        callerHoldsWallet();
        handlerFails(violation);

        assertThatThrownBy(this::withdraw).isSameAs(violation);

        verify(withdrawals, times(2)).findByTransactionReference(REFERENCE);
    }

    static Stream<Arguments> lockAndVersionFailures() {
        return Stream.of(
                Arguments.of(named("a deadlock victim", new CannotAcquireLockException("deadlock detected"))),
                Arguments.of(named(
                        "a lock wait that timed out",
                        new PessimisticLockingFailureException("canceling statement due to lock timeout")
                )),
                Arguments.of(named(
                        "a version conflict",
                        new ObjectOptimisticLockingFailureException(Wallet.class, 1011L)
                ))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lockAndVersionFailures")
    void aLockOrVersionFailureIsABusyWalletThatInvitesARetryWithTheSameKey(ConcurrencyFailureException failure) {
        // Guards these reaching the client as a 500 through the platform's last-resort handler, which hides that
        // nothing committed and a retry with the same key is safe. They are not integrity violations, so the key is
        // not read again, and the withdrawal is not retried here: the client's retry is the retry.
        callerHoldsWallet();
        handlerFails(failure);

        assertThatThrownBy(this::withdraw)
                .isInstanceOf(WithdrawalBusyException.class)
                .hasMessage("The wallet is busy and nothing was debited. Retry with the same Idempotency-Key.")
                .satisfies(busy -> {
                    assertThat(busy.getCause()).isSameAs(failure);
                    assertThat(((ApiException) busy).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });

        verify(handler, times(1)).execute(any(), any());
        verify(withdrawals, times(1)).findByTransactionReference(REFERENCE);
        verifyNoInteractions(movements);
    }

    @Test
    void theServiceOpensNoTransactionOfItsOwn() {
        // Guards someone adding @Transactional here. The handler would join it, its lock would return the wallet
        // this class read, and a credit committed in between would fail the withdrawal with a version conflict; the
        // reads that explain a violation would run on the transaction Postgres had aborted; and the preflight would
        // hold a connection while Payment Service answers.
        assertThat(opensTransaction(WithdrawalService.class)).isFalse();
        assertThat(WithdrawalService.class.getDeclaredMethods()).noneMatch(this::opensTransaction);
    }

    private boolean opensTransaction(AnnotatedElement element) {
        return AnnotatedElementUtils.hasAnnotation(element, Transactional.class)
                || AnnotatedElementUtils.hasAnnotation(element, jakarta.transaction.Transactional.class);
    }

    @Test
    void theStatusReadAnswersOnlyFromTheCallersWalletAndLowerCasesTheReference() {
        // Guards a reference typed in upper case missing its withdrawal, and a read that skips the caller's wallet.
        // The reason shown is the one that applies to the status.
        callerHoldsWallet();
        Withdrawal returning = stored(1011L, WithdrawalStatus.PENDING);
        returning.markReturnPending("PAYOUT_FAILED");
        when(withdrawals.findByTransactionReference(REFERENCE)).thenReturn(Optional.of(returning));

        WithdrawalStatusResponse status = service.read(USER, "pln", KEY);

        assertThat(status).isEqualTo(new WithdrawalStatusResponse(
                REFERENCE,
                "75.00",
                "PLN",
                "RETURN_PENDING",
                "PAYOUT_FAILED"
        ));
        verify(wallets).findByUserIdAndCurrency(USER, "PLN");
        verifyNoInteractions(preflight, handler, movements);
    }

    static Stream<Arguments> unknownWithdrawals() {
        return Stream.of(
                Arguments.of(named("no withdrawal under the reference", Optional.empty())),
                Arguments.of(named("another wallet's withdrawal", Optional.of(stored(1099L, WithdrawalStatus.PAID))))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unknownWithdrawals")
    void anotherWalletsWithdrawalIsReadAsUnknown(Optional<Withdrawal> found) {
        // Guards one user reading another's withdrawal by its reference, and a different answer for it than for an
        // unknown reference, which would tell a caller that a key is in use elsewhere.
        callerHoldsWallet();
        when(withdrawals.findByTransactionReference(REFERENCE)).thenReturn(found);

        assertThatThrownBy(() -> service.read(USER, "PLN", REFERENCE))
                .isInstanceOf(WithdrawalNotFoundException.class)
                .hasMessage("No such withdrawal in the PLN wallet")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aStatusReadOfACurrencyThatIsNotIsoIsRefusedBeforeAnyQueryRuns() {
        // Guards an unknown code reaching the wallet query on the read, where it would be answered as a missing
        // wallet, a 404 that sends the client to open one.
        assertThatThrownBy(() -> service.read(USER, "QQQ", REFERENCE))
                .isInstanceOf(InvalidCurrencyException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(wallets, withdrawals, movements);
    }

    @Test
    void theStatusReadOfAMissingWalletIsAMissingWalletBeforeAnyWithdrawalIsRead() {
        // Guards a missing wallet answered with the withdrawal's 404, whose detail would send the client looking for
        // a reference instead of a wallet, and a withdrawals query for a caller who holds nothing.
        when(wallets.findByUserIdAndCurrency(USER, "PLN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.read(USER, "PLN", REFERENCE))
                .isInstanceOf(WalletNotFoundException.class)
                .hasMessage("No PLN wallet");

        verifyNoInteractions(withdrawals);
    }

    @Test
    void noLogLineNamesTheUser(CapturedOutput output) {
        // Guards the user id, the only credential (ADR 0003), reaching the lines that explain a violation: the race
        // lost to another wallet and the backstop's replay. The reference already identifies the attempt (ADR 0027).
        callerHoldsWallet();
        when(withdrawals.findByTransactionReference(REFERENCE)).thenReturn(
                Optional.empty(),
                Optional.of(stored(1099L, WithdrawalStatus.PENDING)),
                Optional.empty(),
                Optional.of(stored(1011L, WithdrawalStatus.PENDING))
        );
        ledgerHoldsTheDebit();
        handlerFails(violation);

        assertThatThrownBy(this::withdraw).isInstanceOf(ConflictingWithdrawalException.class);
        assertThat(withdraw()).isEqualTo(new WithdrawalResponse(REFERENCE, "75.00", "PLN", "25.00"));

        assertThat(output.getAll())
                .contains("Idempotency-Key " + REFERENCE + " went to another wallet's withdrawal")
                .contains("Withdrawal " + REFERENCE + " reached the unique index")
                .doesNotContain(USER);
    }
}
