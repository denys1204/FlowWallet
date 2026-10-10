package com.flowwallet.payment.payout;

import com.flowwallet.payment.dto.PayoutAccountResponse;
import com.flowwallet.payment.provider.PayoutProvider;
import com.flowwallet.payment.provider.dto.PayoutAccountHolder;
import com.flowwallet.payment.provider.dto.PayoutCallResult;
import com.flowwallet.payment.provider.dto.PayoutRefusal;
import com.flowwallet.payment.provider.dto.ProviderBankAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class PayoutAccountServiceTest {
    private static final String USER = "0b9e5c1a-7d2f-4a3b-8c4d-5e6f7a8b9c0d";
    private static final Instant ACCEPTED_AT = Instant.parse("2026-10-10T08:00:00Z");
    private static final ProviderBankAccount READY_BANK = new ProviderBankAccount("ba_1", "2874", "PLN", true);

    private final PayoutAccountStore store = mock(PayoutAccountStore.class);
    private final PayoutProvider provider = mock(PayoutProvider.class);
    private final PayoutAccountService service = new PayoutAccountService(store, provider, new PayoutProperties());

    private static PayoutAccount reserved() {
        return PayoutAccount.builder()
                .id(7L)
                .userId(USER)
                .accountKey("key-1")
                .tosAcceptedAt(ACCEPTED_AT)
                .bankState(BankAccountState.NOT_SET)
                .build();
    }

    private static PayoutAccount opened() {
        PayoutAccount account = reserved();
        account.recordStripeAccount("acct_1");
        return account;
    }

    private static PayoutAccount withBank(String token) {
        PayoutAccount account = opened();
        account.recordBankAccount(token, "ba_1", "2874", "pln", BankAccountState.READY);
        return account;
    }

    @Test
    void theFirstRequestReservesTheRowOpensTheAccountAndAttachesTheToken() {
        // Guards opening the account under anything but the row's key and acceptance time, which a retry would send
        // differently, and an account opened without the configured identity.
        when(store.findByUser(USER)).thenReturn(Optional.empty());
        when(store.reserve(eq(USER), any())).thenReturn(reserved());
        when(provider.createAccount(eq("key-1"), eq(ACCEPTED_AT), any()))
                .thenReturn(new PayoutCallResult.Done<>("acct_1"));
        when(store.recordStripeAccount(7L, "acct_1")).thenReturn(opened());
        when(provider.attachBankAccount("acct_1", "key-1", "btok_1"))
                .thenReturn(new PayoutCallResult.Done<>(READY_BANK));
        when(store.recordBankAccount(7L, "btok_1", READY_BANK)).thenReturn(withBank("btok_1"));

        PayoutAccountResponse response = service.setBankAccount(USER, "btok_1");

        assertThat(response).isEqualTo(new PayoutAccountResponse("READY", "2874", "PLN"));
        ArgumentCaptor<PayoutAccountHolder> holder = ArgumentCaptor.forClass(PayoutAccountHolder.class);
        verify(provider).createAccount(eq("key-1"), eq(ACCEPTED_AT), holder.capture());
        assertThat(holder.getValue().country()).isEqualTo("PL");
        assertThat(holder.getValue().merchantCategoryCode()).isEqualTo("5734");
    }

    @Test
    void anAccountAlreadyOpenedOnlyGetsTheNewToken() {
        // Guards opening a second connected account for a user who replaces their bank account.
        when(store.findByUser(USER)).thenReturn(Optional.of(withBank("btok_old")));
        when(provider.attachBankAccount("acct_1", "key-1", "btok_new"))
                .thenReturn(new PayoutCallResult.Done<>(READY_BANK));
        when(store.recordBankAccount(7L, "btok_new", READY_BANK)).thenReturn(withBank("btok_new"));

        service.setBankAccount(USER, "btok_new");

        verify(provider, never()).createAccount(anyString(), any(), any());
        verify(store, never()).reserve(anyString(), any());
    }

    @Test
    void theTokenLastAppliedIsAnsweredFromTheRow() {
        // Guards sending a used token to Stripe again: Stripe refuses a token's second use, so a client retrying a
        // request that succeeded would be told its bank account was refused.
        when(store.findByUser(USER)).thenReturn(Optional.of(withBank("btok_1")));

        assertThat(service.setBankAccount(USER, "btok_1")).isEqualTo(new PayoutAccountResponse("READY", "2874", "PLN"));

        verifyNoInteractions(provider);
    }

    @Test
    void aLostReservationContinuesWithTheWinnersRow() {
        // Guards two first requests opening two accounts: the loser must use the winner's key, which Stripe answers
        // with the same account.
        when(store.findByUser(USER)).thenReturn(Optional.empty(), Optional.of(reserved()));
        when(store.reserve(eq(USER), any())).thenThrow(new DataIntegrityViolationException("payout_accounts_user_key"));
        when(provider.createAccount(eq("key-1"), eq(ACCEPTED_AT), any()))
                .thenReturn(new PayoutCallResult.Done<>("acct_1"));
        when(store.recordStripeAccount(7L, "acct_1")).thenReturn(opened());
        when(provider.attachBankAccount("acct_1", "key-1", "btok_1"))
                .thenReturn(new PayoutCallResult.Done<>(READY_BANK));
        when(store.recordBankAccount(7L, "btok_1", READY_BANK)).thenReturn(withBank("btok_1"));

        assertThat(service.setBankAccount(USER, "btok_1").state()).isEqualTo("READY");
    }

    @Test
    void aRefusedTokenIsA400AndRecordsNothing() {
        // Guards a refused token being recorded as the one applied, which would answer every later request with it
        // from the row as if it had worked.
        when(store.findByUser(USER)).thenReturn(Optional.of(opened()));
        when(provider.attachBankAccount("acct_1", "key-1", "btok_1"))
                .thenReturn(new PayoutCallResult.Refused<>(PayoutRefusal.BANK_ACCOUNT, "token_already_used"));

        assertThatThrownBy(() -> service.setBankAccount(USER, "btok_1")).isInstanceOf(BankTokenRefusedException.class);

        verify(store, never()).recordBankAccount(any(), anyString(), any());
    }

    @Test
    void anUnsettledAttachIsA502ThatLeavesTheTokenFreeToRetry() {
        // Guards recording a bank account Stripe may not hold, and a 400 that would send the user to a new token
        // although the same one, retried under its key, may already be attached.
        when(store.findByUser(USER)).thenReturn(Optional.of(opened()));
        when(provider.attachBankAccount("acct_1", "key-1", "btok_1"))
                .thenReturn(new PayoutCallResult.Unknown<>("ApiConnectionException"));

        assertThatThrownBy(() -> service.setBankAccount(USER, "btok_1"))
                .isInstanceOf(PayoutAccountUnavailableException.class);

        verify(store, never()).recordBankAccount(any(), anyString(), any());
    }

    @Test
    void anAccountStripeDidNotOpenIsA502AndNoTokenIsSent() {
        // Guards a refusal of Payment Service's own account parameters reaching the user as a 400 they cannot fix,
        // and a token attached to no account.
        when(store.findByUser(USER)).thenReturn(Optional.of(reserved()));
        when(provider.createAccount(eq("key-1"), eq(ACCEPTED_AT), any()))
                .thenReturn(new PayoutCallResult.Unknown<>("InvalidRequestException, status 400"));

        assertThatThrownBy(() -> service.setBankAccount(USER, "btok_1"))
                .isInstanceOf(PayoutAccountUnavailableException.class);

        verify(provider, never()).attachBankAccount(anyString(), anyString(), anyString());
    }

    @Test
    void aBankAccountOutsideThePayoutCurrenciesIsRecordedButNotReady() {
        // Guards a EUR bank account reported READY on a service that pays out only in PLN: the withdrawal would pass
        // the bank account check and fail at Stripe after the transfer.
        ProviderBankAccount euro = new ProviderBankAccount("ba_9", "1234", "EUR", true);
        when(store.findByUser(USER)).thenReturn(Optional.of(opened()));
        when(provider.attachBankAccount("acct_1", "key-1", "btok_1")).thenReturn(new PayoutCallResult.Done<>(euro));
        when(store.recordBankAccount(eq(7L), eq("btok_1"), any())).thenReturn(withBank("btok_1"));

        service.setBankAccount(USER, "btok_1");

        verify(store).recordBankAccount(7L, "btok_1", new ProviderBankAccount("ba_9", "1234", "EUR", false));
    }

    @Test
    void theTermsAreAcceptedAtAWholeSecond() {
        // Guards a retry of the account creation sending another acceptance time than the first: the column rounds
        // to microseconds, Stripe takes seconds, and a time near a second's end would round into the next one.
        when(store.findByUser(USER)).thenReturn(Optional.empty());
        when(store.reserve(eq(USER), any())).thenReturn(reserved());
        when(provider.createAccount(anyString(), any(), any())).thenReturn(new PayoutCallResult.Unknown<>("timeout"));

        assertThatThrownBy(() -> service.setBankAccount(USER, "btok_1"))
                .isInstanceOf(PayoutAccountUnavailableException.class);

        ArgumentCaptor<Instant> acceptedAt = ArgumentCaptor.forClass(Instant.class);
        verify(store).reserve(eq(USER), acceptedAt.capture());
        assertThat(acceptedAt.getValue().getNano()).isZero();
    }

    @Test
    void aUserWithoutARowHasNoBankAccount() {
        // Guards a GET for a user who never set a bank account failing instead of saying so.
        when(store.findByUser(USER)).thenReturn(Optional.empty());

        assertThat(service.bankAccount(USER)).isEqualTo(new PayoutAccountResponse("NOT_SET", null, null));
    }

    @Test
    void noLogLineNamesTheUser(CapturedOutput output) {
        // Guards a user id, which spends the user's balance, reaching a log line; the account key stands in for it.
        when(store.findByUser(USER)).thenReturn(Optional.of(reserved()));
        when(provider.createAccount(eq("key-1"), eq(ACCEPTED_AT), any()))
                .thenReturn(new PayoutCallResult.Done<>("acct_1"));
        when(store.recordStripeAccount(7L, "acct_1")).thenReturn(opened());
        when(provider.attachBankAccount("acct_1", "key-1", "btok_1"))
                .thenReturn(new PayoutCallResult.Refused<>(PayoutRefusal.BANK_ACCOUNT, "resource_missing"));

        assertThatThrownBy(() -> service.setBankAccount(USER, "btok_1")).isInstanceOf(BankTokenRefusedException.class);

        assertThat(output.getAll()).contains("key-1").doesNotContain(USER);
    }
}
