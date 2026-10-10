package com.flowwallet.payment.webhook;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowwallet.payment.payout.BankRefresh;
import com.flowwallet.payment.payout.PayoutAccountService;
import com.flowwallet.payment.payout.PayoutSettler;
import com.flowwallet.payment.payout.Settlement;
import com.flowwallet.payment.provider.PayoutProvider;
import com.flowwallet.payment.provider.dto.ConnectEvent;
import com.flowwallet.payment.provider.dto.ProviderPayoutStatus;
import com.flowwallet.payment.provider.exception.InvalidWebhookSignatureException;
import com.flowwallet.payment.provider.exception.PayoutLookupException;
import com.flowwallet.payment.provider.exception.WebhookProcessingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConnectWebhookServiceTest {
    private static final String PAYLOAD = "{\"id\":\"evt_1\"}";
    private static final Map<String, String> HEADERS = Map.of("Stripe-Signature", "t=1,v1=00");

    private final PayoutProvider provider = mock(PayoutProvider.class);
    private final PayoutSettler settler = mock(PayoutSettler.class);
    private final PayoutAccountService accounts = mock(PayoutAccountService.class);
    private final ConnectWebhookService service = new ConnectWebhookService(provider, settler, accounts);
    private final Logger logger = (Logger) LoggerFactory.getLogger(ConnectWebhookService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogAppender() {
        logger.detachAppender(logs);
        logger.setLevel(null);
    }

    @Test
    void theBodyAndHeadersAreHandedToTheProviderUnchanged() {
        // Guards a payload or a header the service altered: the signature covers the exact body, so every delivery
        // would be refused.
        when(provider.readConnectEvent(PAYLOAD, HEADERS)).thenReturn(ignored("payout.created", null));

        service.process(PAYLOAD, HEADERS);

        verify(provider).readConnectEvent(PAYLOAD, HEADERS);
    }

    @Test
    void aPayoutEventIsSettledOnTheEventsAccount() {
        // Guards a payout read on the platform, or on another account, which Stripe would not find, and a payout
        // event that also refreshes a bank account.
        when(provider.readConnectEvent(PAYLOAD, HEADERS))
                .thenReturn(new ConnectEvent.PayoutChanged("evt_1", "payout.paid", "acct_1", "po_1"));

        service.process(PAYLOAD, HEADERS);

        verify(settler).settle("acct_1", "po_1");
        verifyNoInteractions(accounts);
    }

    @ParameterizedTest(name = "an account event that finds {0} is answered without a failure")
    @EnumSource(value = BankRefresh.class, names = "RACED", mode = EnumSource.Mode.EXCLUDE)
    void anAccountEventRefreshesThatAccount(BankRefresh result) {
        // Guards an account event that settles a payout, a refresh of another account, and a refresh outcome that
        // needs no redelivery, such as an unknown account, being answered with a failure.
        when(provider.readConnectEvent(PAYLOAD, HEADERS))
                .thenReturn(new ConnectEvent.AccountChanged("evt_1", "account.updated", "acct_1"));
        when(accounts.refreshBankState("acct_1")).thenReturn(result);

        assertThatCode(() -> service.process(PAYLOAD, HEADERS)).doesNotThrowAnyException();

        verify(accounts).refreshBankState("acct_1");
        verifyNoInteractions(settler);
    }

    @ParameterizedTest(name = "a payout that settles as {0} is answered without a failure")
    @EnumSource(Settlement.Kind.class)
    void everySettlementKindIsAnsweredWithoutAFailure(Settlement.Kind kind) {
        // Guards a settlement that changes nothing, such as differing terms, being answered with a failure: a
        // redelivery cannot change what Stripe holds, so Stripe would retry for days with nothing to fix.
        when(provider.readConnectEvent(PAYLOAD, HEADERS))
                .thenReturn(new ConnectEvent.PayoutChanged("evt_1", "payout.failed", "acct_1", "po_1"));
        when(settler.settle("acct_1", "po_1")).thenReturn(
                new Settlement(kind, "ref-1", ProviderPayoutStatus.FAILED, null, null, false, null)
        );

        assertThatCode(() -> service.process(PAYLOAD, HEADERS)).doesNotThrowAnyException();
    }

    @Test
    void aFailedReadOfAPayoutIsAProcessingFailure() {
        // Guards an unreadable payout being answered 2xx, which would stop Stripe redelivering an event that was
        // never applied.
        PayoutLookupException lookupFailure = new PayoutLookupException("timeout", new RuntimeException());
        when(provider.readConnectEvent(PAYLOAD, HEADERS))
                .thenReturn(new ConnectEvent.PayoutChanged("evt_1", "payout.paid", "acct_1", "po_1"));
        when(settler.settle("acct_1", "po_1")).thenThrow(lookupFailure);

        assertThatThrownBy(() -> service.process(PAYLOAD, HEADERS))
                .isInstanceOf(WebhookProcessingException.class)
                .hasMessageContaining("evt_1")
                .hasCause(lookupFailure);
    }

    @Test
    void aFailedReadOfAnAccountIsAProcessingFailure() {
        // Guards an unreadable account being answered 2xx, which would leave a bank account marked READY after
        // Stripe stopped paying out to it.
        PayoutLookupException lookupFailure = new PayoutLookupException("timeout", new RuntimeException());
        when(provider.readConnectEvent(PAYLOAD, HEADERS))
                .thenReturn(new ConnectEvent.AccountChanged("evt_1", "account.updated", "acct_1"));
        when(accounts.refreshBankState("acct_1")).thenThrow(lookupFailure);

        assertThatThrownBy(() -> service.process(PAYLOAD, HEADERS))
                .isInstanceOf(WebhookProcessingException.class)
                .hasMessageContaining("evt_1")
                .hasCause(lookupFailure);
    }

    @Test
    void aRacedRefreshIsAProcessingFailure() {
        // Guards a refresh that raced another writer being answered 2xx: neither read is known to be the newer one,
        // so only a redelivery that reads again can settle the state.
        when(provider.readConnectEvent(PAYLOAD, HEADERS))
                .thenReturn(new ConnectEvent.AccountChanged("evt_1", "external_account.updated", "acct_1"));
        when(accounts.refreshBankState("acct_1")).thenReturn(BankRefresh.RACED);

        assertThatThrownBy(() -> service.process(PAYLOAD, HEADERS))
                .isInstanceOf(WebhookProcessingException.class)
                .hasMessageContaining("evt_1");
    }

    @Test
    void aDatabaseFailureIsNotWrappedSoTheHandlerAnswers503() {
        // Guards every failure being rewrapped as a 500: an unreachable database keeps the 503 that ADR 0025 gives
        // every endpoint, which Stripe also redelivers.
        DataAccessResourceFailureException unreachable = new DataAccessResourceFailureException("db down");
        when(provider.readConnectEvent(PAYLOAD, HEADERS))
                .thenReturn(new ConnectEvent.PayoutChanged("evt_1", "payout.paid", "acct_1", "po_1"));
        when(settler.settle("acct_1", "po_1")).thenThrow(unreachable);

        assertThatThrownBy(() -> service.process(PAYLOAD, HEADERS)).isSameAs(unreachable);
    }

    @Test
    void aRefusedSignatureTouchesNothing() {
        // The provider verifies the signature; its refusal must reach the caller as the 400 it is, before any payout
        // or account is read or written.
        InvalidWebhookSignatureException refused = new InvalidWebhookSignatureException("Invalid Stripe signature");
        when(provider.readConnectEvent(PAYLOAD, HEADERS)).thenThrow(refused);

        assertThatThrownBy(() -> service.process(PAYLOAD, HEADERS)).isSameAs(refused);
        verifyNoInteractions(settler, accounts);
    }

    @ParameterizedTest(name = "{0} ignored with the defect {1} touches nothing")
    @CsvSource(
            nullValues = "NULL",
            value = {"payout.created, NULL", "account.updated, no connected account", "payout.paid, no payout id"}
    )
    void anIgnoredEventTouchesNothing(String type, String defect) {
        // Guards an event the service does not act on, such as payout.created or an event without a connected
        // account, reaching a payout or a bank account.
        when(provider.readConnectEvent(PAYLOAD, HEADERS)).thenReturn(ignored(type, defect));

        assertThatCode(() -> service.process(PAYLOAD, HEADERS)).doesNotThrowAnyException();

        verifyNoInteractions(settler, accounts);
    }

    @Test
    void anIgnoredEventWithoutADefectIsLoggedAtDebugOnly() {
        // Guards Stripe's routine events, such as payout.created, filling the log at INFO or WARN.
        when(provider.readConnectEvent(PAYLOAD, HEADERS)).thenReturn(ignored("payout.created", null));

        service.process(PAYLOAD, HEADERS);

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).contains("evt_1", "payout.created");
        });
    }

    @Test
    void anIgnoredEventWithADefectIsLoggedAtWarnWithTheDefect() {
        // Guards a handled event type that cannot be acted on, such as one without a connected account, being
        // dropped unseen.
        when(provider.readConnectEvent(PAYLOAD, HEADERS)).thenReturn(ignored("payout.paid", "no connected account"));

        service.process(PAYLOAD, HEADERS);

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("evt_1", "payout.paid", "no connected account");
        });
    }

    private static ConnectEvent ignored(String type, String defect) {
        return new ConnectEvent.Ignored("evt_1", type, defect);
    }
}
