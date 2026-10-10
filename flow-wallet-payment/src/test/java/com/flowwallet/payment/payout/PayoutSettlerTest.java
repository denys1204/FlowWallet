package com.flowwallet.payment.payout;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowwallet.payment.provider.PayoutProvider;
import com.flowwallet.payment.provider.dto.ProviderPayout;
import com.flowwallet.payment.provider.dto.ProviderPayoutStatus;
import com.flowwallet.payment.provider.exception.PayoutLookupException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PayoutSettlerTest {
    private static final String ACCOUNT = "acct_1";
    private static final String PAYOUT = "po_1";
    private static final String REF = "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d";

    private final PayoutProvider provider = mock(PayoutProvider.class);
    private final PayoutStore store = mock(PayoutStore.class);
    private final PayoutSettler settler = new PayoutSettler(provider, store);
    private final Logger logger = (Logger) LoggerFactory.getLogger(PayoutSettler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogAppenderAndRestoreTheLevel() {
        logger.detachAppender(logs);
        logger.setLevel(null);
    }

    @Test
    void thePayoutIsReadOnTheAccountAndHandedToTheStore() {
        // Guards a settle that applies something other than what Stripe holds for this account, or that stamps the
        // transition with a time other than the moment of the settle.
        ProviderPayout found = payout(ProviderPayoutStatus.PAID);
        Settlement applied = settlement(Settlement.Kind.MOVED, PayoutStatus.PAYOUT_CREATED, PayoutStatus.PAID, false);
        when(provider.retrievePayout(ACCOUNT, PAYOUT)).thenReturn(found);
        when(store.settle(any(), any(), any())).thenReturn(applied);
        Instant before = Instant.now();

        Settlement result = settler.settle(ACCOUNT, PAYOUT);

        Instant after = Instant.now();
        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        verify(store).settle(eq(ACCOUNT), eq(found), now.capture());
        assertThat(now.getValue()).isBetween(before, after);
        assertThat(result).isSameAs(applied);
    }

    @Test
    void aFailedReadReachesTheCallerAndTheStoreIsNeverCalled() {
        // Guards a lookup failure that is swallowed, or that still writes: the webhook answers 500 on it and the
        // reconciler counts an error, and both need the exception to do so.
        when(provider.retrievePayout(ACCOUNT, PAYOUT)).thenThrow(new PayoutLookupException("down", null));

        assertThatThrownBy(() -> settler.settle(ACCOUNT, PAYOUT)).isInstanceOf(PayoutLookupException.class);

        verifyNoInteractions(store);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void aFailureInTheStoreReachesTheCallerAndLogsNothing() {
        // Guards a failed write reported as a settlement: the caller would answer 200 or count a move for a row that
        // did not change.
        when(provider.retrievePayout(ACCOUNT, PAYOUT)).thenReturn(payout(ProviderPayoutStatus.PAID));
        when(store.settle(any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> settler.settle(ACCOUNT, PAYOUT)).isInstanceOf(IllegalStateException.class);

        assertThat(logs.list).isEmpty();
    }

    @ParameterizedTest(name = "{0} is logged at {1}")
    @MethodSource("oneLinePerKind")
    void eachKindIsLoggedAtItsLevelWithOnlyTheReferenceAndStripeIds(
            Settlement settlement,
            Level level,
            String message
    ) {
        // Guards a contradiction or a disagreement hidden below WARN or ERROR, a repeat that is noisy at INFO, and a
        // line that carries more than the reference and Stripe's ids: the message is compared in full, so a user id
        // added to any of them fails here (docs/adr/0027-user-ids-stay-out-of-logs-and-provider-metadata.md).
        when(provider.retrievePayout(ACCOUNT, PAYOUT)).thenReturn(payout(ProviderPayoutStatus.PAID));
        when(store.settle(any(), any(), any())).thenReturn(settlement);

        Settlement result = settler.settle(ACCOUNT, PAYOUT);

        assertThat(result).isSameAs(settlement);
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(level);
            assertThat(event.getFormattedMessage()).isEqualTo(message);
        });
    }

    @Test
    void aBankResetIsLoggedAfterTheMove() {
        // Guards a bank account set back to NOT_SET with no trace: the user then cannot withdraw until the account is
        // set again, and an operator looks for the reason in the log.
        ProviderPayout found = payout(ProviderPayoutStatus.FAILED);
        Settlement applied = settlement(
                Settlement.Kind.MOVED,
                PayoutStatus.PAYOUT_CREATED,
                PayoutStatus.REVERSING,
                true
        );
        when(provider.retrievePayout(ACCOUNT, PAYOUT)).thenReturn(found);
        when(store.settle(any(), any(), any())).thenReturn(applied);

        settler.settle(ACCOUNT, PAYOUT);

        assertThat(logs.list).extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage).containsExactly(
                tuple(Level.INFO, "Payout " + REF + " settled by Stripe's status FAILED: PAYOUT_CREATED to REVERSING"),
                tuple(Level.INFO, "Payout " + REF + " failed at its bank account, which is set back to NOT_SET")
        );
    }

    @Test
    void aBankResetOnARepeatedFailureIsStillLogged() {
        // Guards the reset line tied to a move: a repeated failed status resets the stored account again, and the
        // log must say so even though the row did not move.
        ProviderPayout found = payout(ProviderPayoutStatus.FAILED);
        Settlement applied = settlement(Settlement.Kind.REPEATED, PayoutStatus.REVERSING, PayoutStatus.REVERSING, true);
        when(provider.retrievePayout(ACCOUNT, PAYOUT)).thenReturn(found);
        when(store.settle(any(), any(), any())).thenReturn(applied);

        settler.settle(ACCOUNT, PAYOUT);

        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "Payout " + REF + " already shows Stripe's status FAILED",
                "Payout " + REF + " failed at its bank account, which is set back to NOT_SET"
        );
    }

    private static Stream<Arguments> oneLinePerKind() {
        return Stream.of(
                Arguments.of(
                        settlement(Settlement.Kind.MOVED, PayoutStatus.PAYOUT_CREATED, PayoutStatus.PAID, false),
                        Level.INFO,
                        "Payout " + REF + " settled by Stripe's status PAID: PAYOUT_CREATED to PAID"
                ),
                Arguments.of(
                        settlement(Settlement.Kind.REPEATED, PayoutStatus.PAID, PayoutStatus.PAID, false),
                        Level.DEBUG,
                        "Payout " + REF + " already shows Stripe's status PAID"
                ),
                Arguments.of(
                        settlement(Settlement.Kind.CONTRADICTED, PayoutStatus.REVERSING, PayoutStatus.REVERSING, false),
                        Level.WARN,
                        "Payout " + REF + " is REVERSING, which Stripe's status PAID cannot move"
                ),
                Arguments.of(
                        new Settlement(
                                Settlement.Kind.DIFFERING_TERMS,
                                REF,
                                ProviderPayoutStatus.PAID,
                                PayoutStatus.PAYOUT_CREATED,
                                PayoutStatus.PAYOUT_CREATED,
                                false,
                                "amount, currency"
                        ),
                        Level.ERROR,
                        "Stripe payout po_1 on account acct_1 disagrees with payout " + REF
                                + " on its amount, currency; nothing was changed"
                ),
                Arguments.of(
                        new Settlement(
                                Settlement.Kind.UNKNOWN_PAYOUT,
                                REF,
                                ProviderPayoutStatus.PAID,
                                null,
                                null,
                                false,
                                null
                        ),
                        Level.WARN,
                        "Stripe payout po_1 on account acct_1 belongs to no payout"
                )
        );
    }

    private static ProviderPayout payout(ProviderPayoutStatus status) {
        return new ProviderPayout(PAYOUT, status, null, REF, new BigDecimal("75.00"), "PLN", "ba_1");
    }

    private static Settlement settlement(Settlement.Kind kind, PayoutStatus from, PayoutStatus to, boolean bankReset) {
        return new Settlement(kind, REF, ProviderPayoutStatus.PAID, from, to, bankReset, null);
    }
}
