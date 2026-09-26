package com.flowwallet.wallet.transfer;

import com.flowwallet.platform.exception.ApiException;
import com.flowwallet.wallet.api.InvalidAmountException;
import com.flowwallet.wallet.balance.BalanceHistory;
import com.flowwallet.wallet.balance.BalanceHistoryRepository;
import com.flowwallet.wallet.balance.Wallet;
import com.flowwallet.wallet.balance.WalletRepository;
import com.flowwallet.wallet.dto.TransferRequest;
import com.flowwallet.wallet.enums.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.lang.reflect.AnnotatedElement;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TransferServiceTest {
    private static final String CALLER = "1f0c6d0e-5d4b-4c7e-9a2f-3b8e7d6c5a41";
    private static final String RECIPIENT = "9a7b3c2d-1e0f-4a5b-8c6d-7e8f9a0b1c2d";
    private static final String KEY = "7E1855B3-4D95-4A72-A0C9-EF0D78BE2E44";
    private static final String REFERENCE = "7e1855b3-4d95-4a72-a0c9-ef0d78be2e44";

    private final TransferHandler handler = mock(TransferHandler.class);
    private final WalletRepository wallets = mock(WalletRepository.class);
    private final BalanceHistoryRepository movements = mock(BalanceHistoryRepository.class);
    private final TransferService service = new TransferService(handler, wallets, movements);

    private final DataIntegrityViolationException violation =
            new DataIntegrityViolationException("duplicate key value violates unique constraint");

    private TransferRequest request(String to, String amount) {
        return new TransferRequest(to, new BigDecimal(amount));
    }

    private TransferResponse transfer() {
        return service.transfer(CALLER, "USD", KEY, request(RECIPIENT, "25.00"));
    }

    /**
     * The caller's wallet as the read after the rollback finds it, with an id so the comparison means something.
     */
    private void callerHoldsWallet(long id) {
        Wallet wallet = Wallet.builder()
                .id(id)
                .userId(CALLER)
                .currency("USD")
                .balance(new BigDecimal("75.0000"))
                .build();
        when(wallets.findByUserIdAndCurrency(CALLER, "USD")).thenReturn(Optional.of(wallet));
    }

    private BalanceHistory committedOut(long walletId) {
        BalanceHistory out = BalanceHistory.builder()
                .walletId(walletId)
                .transactionReference(REFERENCE)
                .type(TransactionType.TRANSFER_OUT)
                .counterpartyUserId(RECIPIENT)
                .amount(new BigDecimal("25.0000"))
                .balanceBefore(new BigDecimal("100.0000"))
                .balanceAfter(new BigDecimal("75.0000"))
                .build();
        when(movements.findByTransactionReferenceAndType(REFERENCE, TransactionType.TRANSFER_OUT))
                .thenReturn(Optional.of(out));
        return out;
    }

    @Test
    void aTransferToOneselfIsRefusedBeforeAnyTransactionOpens() {
        // Guards a self-transfer slipping through a difference in letter case, and a connection taken for a
        // request that can never succeed. The caller's id arrives lower-cased from the resolver; the body
        // carries the same id upper-cased. The status must be 400: no wallet's state enters into it, and a 422
        // would tell the client to top up or pick another recipient.
        TransferRequest toSelf = request(CALLER.toUpperCase(Locale.ROOT), "25.00");

        assertThatThrownBy(() -> service.transfer(CALLER, "USD", KEY, toSelf))
                .isInstanceOf(SelfTransferException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(handler, wallets, movements);
    }

    @Test
    void anAmountOffTheCurrencysGridIsRefusedBeforeAnyTransactionOpens() {
        // Guards the precision check being skipped, or judged against anything but the path's currency. Half
        // a yen could never be paid out, so it must never reach a balance. The status must be 400, a mistake
        // in the request, and not a 422 that reads as a balance problem.
        assertThatThrownBy(() -> service.transfer(CALLER, "jpy", KEY, request(RECIPIENT, "1.5")))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessage("JPY amounts must be whole units")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(handler, wallets, movements);
    }

    @Test
    void theKeyAndTheRecipientAreLowerCasedBeforeTheHandlerSeesThem() {
        // Guards two spellings of one key becoming two transfers, and a mixed-case recipient missing a wallet
        // that exists. The currency is upper-cased and the amount brought to the ledger's scale, so the
        // command compares equal only if every value is in its canonical form.
        service.transfer(CALLER, "usd", KEY, request(RECIPIENT.toUpperCase(Locale.ROOT), "25.00"));

        ArgumentCaptor<TransferCommand> sent = ArgumentCaptor.forClass(TransferCommand.class);
        verify(handler).execute(sent.capture());
        assertThat(sent.getValue()).isEqualTo(new TransferCommand(
                CALLER, RECIPIENT, "USD", REFERENCE, new BigDecimal("25.0000")
        ));
    }

    @Test
    void aViolationForAKeyAnotherWalletHoldsIsAConflict() {
        // Guards a same-key race between two sender wallets being answered as a 500, or as a success. The
        // other wallet's transfer committed first and this one lost at the unique index.
        when(handler.execute(any())).thenThrow(violation);
        committedOut(99L);
        callerHoldsWallet(11L);

        assertThatThrownBy(this::transfer).isInstanceOf(ConflictingTransferException.class);
    }

    @Test
    void aViolationForTheCallersOwnMatchingTransferIsAnsweredAsAReplay() {
        // The backstop. Requests from one wallet should serialize on its lock and never meet at the index, but
        // if that argument ever fails, a 409 would send the client to a new key and move the money twice. The
        // service brings the request's 25.00 to the ledger's 25.0000 before this comparison, so both sides
        // share a scale here; BalanceHistoryTest.aRepeatIsJudgedByWalletRecipientAndAmountByValue pins the
        // comparison by value itself.
        when(handler.execute(any())).thenThrow(violation);
        BalanceHistory original = committedOut(11L);
        callerHoldsWallet(11L);

        assertThat(transfer()).isEqualTo(TransferResponse.of(original, "USD"));
    }

    @Test
    void aViolationNoTransferExplainsIsRethrownRatherThanReportedAsAConflict() {
        // Guards a fired balance or amount CHECK, which means a domain rule was bypassed, being reported to
        // the client as "key already used". It must surface as the original exception, and so as a 500.
        when(handler.execute(any())).thenThrow(violation);
        when(movements.findByTransactionReferenceAndType(REFERENCE, TransactionType.TRANSFER_OUT))
                .thenReturn(Optional.empty());

        assertThatThrownBy(this::transfer).isSameAs(violation);
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
                        new ObjectOptimisticLockingFailureException(Wallet.class, 11L)
                ))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lockAndVersionFailures")
    void aLockOrVersionFailureIsABusyWalletThatInvitesARetryWithTheSameKey(ConcurrencyFailureException failure) {
        // Guards these reaching the client as a 500 through the platform's last-resort handler, which hides
        // that nothing committed and a retry with the same key is safe. They are not integrity violations, so
        // the ledger is not read, and the transfer is not retried here: the client's retry is the retry.
        when(handler.execute(any())).thenThrow(failure);

        assertThatThrownBy(this::transfer)
                .isInstanceOf(TransferBusyException.class)
                .hasMessageContaining("same Idempotency-Key")
                .satisfies(busy -> {
                    assertThat(busy.getCause()).isSameAs(failure);
                    assertThat(((ApiException) busy).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });

        verify(handler, times(1)).execute(any());
        verifyNoInteractions(wallets, movements);
    }

    @Test
    void theServiceOpensNoTransactionOfItsOwn() {
        // Guards someone adding @Transactional here. The handler would then join this transaction, and after a
        // violation the reads that explain it would run on the transaction Postgres has already aborted.
        assertThat(opensTransaction(TransferService.class)).isFalse();
        assertThat(TransferService.class.getDeclaredMethods()).noneMatch(this::opensTransaction);
    }

    private boolean opensTransaction(AnnotatedElement element) {
        return AnnotatedElementUtils.hasAnnotation(
                element, org.springframework.transaction.annotation.Transactional.class
        ) || AnnotatedElementUtils.hasAnnotation(element, jakarta.transaction.Transactional.class);
    }
}
