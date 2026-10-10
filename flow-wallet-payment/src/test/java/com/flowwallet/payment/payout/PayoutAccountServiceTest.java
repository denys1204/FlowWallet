package com.flowwallet.payment.payout;

import com.flowwallet.payment.dto.PayoutAccountResponse;
import com.flowwallet.payment.provider.PayoutProvider;
import com.flowwallet.payment.provider.dto.PayoutAccountHolder;
import com.flowwallet.payment.provider.dto.PayoutCallResult;
import com.flowwallet.payment.provider.dto.PayoutRefusal;
import com.flowwallet.payment.provider.dto.ProviderBankAccount;
import com.flowwallet.payment.provider.exception.PayoutLookupException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    private static PayoutAccount withStoredBank(String currency, String externalAccountId, BankAccountState state) {
        PayoutAccount account = PayoutAccount.builder()
                .id(7L)
                .userId(USER)
                .accountKey("key-1")
                .tosAcceptedAt(ACCEPTED_AT)
                .bankState(BankAccountState.NOT_SET)
                .version(4L)
                .build();
        account.recordStripeAccount("acct_1");
        account.recordBankAccount("btok_1", externalAccountId, "2874", currency, state);
        return account;
    }

    private PayoutAccountService servicePayingOutInPlnAndEur() {
        PayoutProperties properties = new PayoutProperties();
        properties.setCurrencies(List.of(
                new PayoutProperties.PayoutCurrency("PLN", new BigDecimal("5.00")),
                new PayoutProperties.PayoutCurrency("EUR", new BigDecimal("5.00"))
        ));
        return new PayoutAccountService(store, provider, properties);
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
    void aBankAccountInAConfiguredPayoutCurrencyKeepsItsReadiness() {
        // Guards a payout currency that is fixed in code instead of read from the configured list: a EUR bank
        // account would be recorded as not ready on a platform that was set up to pay out in EUR.
        ProviderBankAccount euro = new ProviderBankAccount("ba_9", "1234", "EUR", true);
        when(store.findByUser(USER)).thenReturn(Optional.of(opened()));
        when(provider.attachBankAccount("acct_1", "key-1", "btok_1")).thenReturn(new PayoutCallResult.Done<>(euro));
        when(store.recordBankAccount(eq(7L), eq("btok_1"), any())).thenReturn(withBank("btok_1"));

        servicePayingOutInPlnAndEur().setBankAccount(USER, "btok_1");

        verify(store).recordBankAccount(7L, "btok_1", euro);
    }

    @Test
    void refreshMakesAPayableDefaultReady() {
        // Guards an account event that makes a bank account payable at Stripe, such as its verification, leaving the
        // stored state NOT_SET, which would refuse every withdrawal until the user attached the account again.
        when(store.findByStripeAccount("acct_1"))
                .thenReturn(Optional.of(withStoredBank("PLN", "ba_1", BankAccountState.NOT_SET)));
        when(provider.findDefaultBankAccount("acct_1", "PLN")).thenReturn(Optional.of(READY_BANK));
        when(store.applyBankState(7L, 4L, "ba_1", BankAccountState.READY)).thenReturn(BankRefresh.CHANGED);

        assertThat(service.refreshBankState("acct_1")).isEqualTo(BankRefresh.CHANGED);
    }

    @Test
    void refreshSetsNotSetForADefaultThatIsNotReady() {
        // Guards a bank account that Stripe reports as errored, or on an account whose payouts are disabled, staying
        // READY: a withdrawal would pass the preflight, move the money to the connected account and fail at the payout.
        ProviderBankAccount errored = new ProviderBankAccount("ba_1", "2874", "PLN", false);
        when(store.findByStripeAccount("acct_1"))
                .thenReturn(Optional.of(withStoredBank("PLN", "ba_1", BankAccountState.READY)));
        when(provider.findDefaultBankAccount("acct_1", "PLN")).thenReturn(Optional.of(errored));
        when(store.applyBankState(7L, 4L, "ba_1", BankAccountState.NOT_SET)).thenReturn(BankRefresh.CHANGED);

        assertThat(service.refreshBankState("acct_1")).isEqualTo(BankRefresh.CHANGED);
    }

    @Test
    void refreshSetsNotSetWhenStripeHasNoDefaultForTheCurrency() {
        // Guards a removed bank account staying READY, and a missing default being applied with an external account id
        // of null that the store would read as "no guard" for a default that Stripe does hold.
        when(store.findByStripeAccount("acct_1"))
                .thenReturn(Optional.of(withStoredBank("PLN", "ba_1", BankAccountState.READY)));
        when(provider.findDefaultBankAccount("acct_1", "PLN")).thenReturn(Optional.empty());
        when(store.applyBankState(7L, 4L, null, BankAccountState.NOT_SET)).thenReturn(BankRefresh.CHANGED);

        assertThat(service.refreshBankState("acct_1")).isEqualTo(BankRefresh.CHANGED);
    }

    @Test
    void aDefaultInACurrencyThePlatformDoesNotPayOutInNeverBecomesReady() {
        // Guards a refresh making a EUR bank account READY on a platform that pays out only in PLN, which the
        // preflight would let through and Stripe would refuse after the transfer.
        ProviderBankAccount euro = new ProviderBankAccount("ba_9", "1234", "EUR", true);
        when(store.findByStripeAccount("acct_1"))
                .thenReturn(Optional.of(withStoredBank("EUR", "ba_9", BankAccountState.READY)));
        when(provider.findDefaultBankAccount("acct_1", "EUR")).thenReturn(Optional.of(euro));
        when(store.applyBankState(7L, 4L, "ba_9", BankAccountState.NOT_SET)).thenReturn(BankRefresh.CHANGED);

        assertThat(service.refreshBankState("acct_1")).isEqualTo(BankRefresh.CHANGED);
    }

    @Test
    void aDefaultInAConfiguredPayoutCurrencyBecomesReady() {
        // Guards the refresh deciding on a hard-coded currency instead of the configured list, which would keep a
        // payable EUR bank account NOT_SET on a platform that pays out in EUR.
        ProviderBankAccount euro = new ProviderBankAccount("ba_9", "1234", "EUR", true);
        when(store.findByStripeAccount("acct_1"))
                .thenReturn(Optional.of(withStoredBank("EUR", "ba_9", BankAccountState.NOT_SET)));
        when(provider.findDefaultBankAccount("acct_1", "EUR")).thenReturn(Optional.of(euro));
        when(store.applyBankState(7L, 4L, "ba_9", BankAccountState.READY)).thenReturn(BankRefresh.CHANGED);

        assertThat(servicePayingOutInPlnAndEur().refreshBankState("acct_1")).isEqualTo(BankRefresh.CHANGED);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = BankRefresh.class, names = {"RACED", "REPLACED", "CHANGED", "UNCHANGED"})
    void refreshReportsWhatTheStoreDid(BankRefresh applied) {
        // Guards a refresh that lost a race being reported as done: the webhook answers 200 for it, so Stripe never
        // delivers the event again and the stored state stays behind Stripe's.
        when(store.findByStripeAccount("acct_1"))
                .thenReturn(Optional.of(withStoredBank("PLN", "ba_1", BankAccountState.READY)));
        when(provider.findDefaultBankAccount("acct_1", "PLN")).thenReturn(Optional.of(READY_BANK));
        when(store.applyBankState(7L, 4L, "ba_1", BankAccountState.READY)).thenReturn(applied);

        assertThat(service.refreshBankState("acct_1")).isEqualTo(applied);
    }

    @Test
    void anUnknownAccountCostsNoStripeRead() {
        // Guards a sandbox account that no row holds, such as an empty one from an earlier run, costing a Stripe read
        // for each event it keeps sending.
        when(store.findByStripeAccount("acct_other")).thenReturn(Optional.empty());

        assertThat(service.refreshBankState("acct_other")).isEqualTo(BankRefresh.UNKNOWN_ACCOUNT);

        verifyNoInteractions(provider);
        verify(store, never()).applyBankState(any(), any(), any(), any());
    }

    @Test
    void anEventForAnAccountNoRowHoldsLeavesALogLine(CapturedOutput output) {
        // Guards events about an account this service does not hold being dropped without a trace, which would hide a
        // Connect endpoint wired to the wrong platform.
        when(store.findByStripeAccount("acct_other")).thenReturn(Optional.empty());

        service.refreshBankState("acct_other");

        assertThat(output.getAll()).contains("acct_other").contains("no payout account holds");
    }

    @Test
    void anAccountWithoutABankAccountCostsNoStripeRead() {
        // Guards an event that arrives while the first PUT is still attaching a bank account: a refresh would find no
        // currency to read for and could write a state over the one the request is about to record.
        when(store.findByStripeAccount("acct_1")).thenReturn(Optional.of(opened()));

        assertThat(service.refreshBankState("acct_1")).isEqualTo(BankRefresh.NO_BANK_ACCOUNT);

        verifyNoInteractions(provider);
        verify(store, never()).applyBankState(any(), any(), any(), any());
    }

    @Test
    void aFailedStripeReadWritesNothingAndReachesTheCaller() {
        // Guards an unreadable Stripe account being recorded as one without a default bank account, which would set
        // the state NOT_SET during an outage, and an error that is swallowed so Stripe never delivers the event again.
        PayoutLookupException failure = new PayoutLookupException("Account read failed", new IllegalStateException());
        when(store.findByStripeAccount("acct_1"))
                .thenReturn(Optional.of(withStoredBank("PLN", "ba_1", BankAccountState.READY)));
        when(provider.findDefaultBankAccount("acct_1", "PLN")).thenThrow(failure);

        assertThatThrownBy(() -> service.refreshBankState("acct_1")).isSameAs(failure);

        verify(store, never()).applyBankState(any(), any(), any(), any());
    }

    @Test
    void aRefreshLogsTheAccountKeyAndNeverTheUser(CapturedOutput output) {
        // Guards a user id, which spends the user's balance, reaching the log line of an account event; the account
        // key stands in for it.
        when(store.findByStripeAccount("acct_1"))
                .thenReturn(Optional.of(withStoredBank("PLN", "ba_1", BankAccountState.NOT_SET)));
        when(provider.findDefaultBankAccount("acct_1", "PLN")).thenReturn(Optional.of(READY_BANK));
        when(store.applyBankState(7L, 4L, "ba_1", BankAccountState.READY)).thenReturn(BankRefresh.CHANGED);

        service.refreshBankState("acct_1");

        assertThat(output.getAll()).contains("key-1").contains("CHANGED").doesNotContain(USER);
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
