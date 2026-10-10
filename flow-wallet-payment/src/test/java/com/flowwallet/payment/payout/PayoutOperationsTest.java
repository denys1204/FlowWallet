package com.flowwallet.payment.payout;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowwallet.payment.provider.PayoutProvider;
import com.flowwallet.payment.provider.dto.ProviderPayout;
import com.flowwallet.payment.provider.dto.ProviderPayoutStatus;
import com.flowwallet.payment.provider.dto.ProviderTransfer;
import com.flowwallet.payment.provider.exception.PayoutLookupException;
import com.flowwallet.platform.exception.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.stubbing.OngoingStubbing;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PayoutOperationsTest {
    private static final String REF = "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d";
    private static final Long ID = 42L;
    private static final Long VERSION = 7L;
    private static final BigDecimal AMOUNT = new BigDecimal("75.0000");
    private static final String ACCOUNT = "acct_1";
    private static final String TRANSFER = "tr_1";
    private static final String PAYOUT = "po_1";
    private static final String DETAIL = "InvalidRequestException, status 400; request-id: req_unit-test";
    private static final Instant CREATED_AT = Instant.parse("2026-10-01T07:59:00Z");
    private static final Instant TRANSFERRED_AT = Instant.parse("2026-10-01T08:00:00Z");

    /**
     * An hour before the transfer: where the executor starts its listing of the transfer's payouts.
     */
    private static final Instant LISTED_SINCE = Instant.parse("2026-10-01T07:00:00Z");

    /**
     * A hand-over whose quiet period is over on any clock the tests run on.
     */
    private static final Instant HANDED_OVER = Instant.parse("2026-10-01T09:00:30Z");

    /**
     * A hand-over ahead of any clock the tests run on, so its quiet period has not passed whatever the time.
     */
    private static final Instant HANDED_OVER_LATER = Instant.parse("2100-01-01T09:00:30Z");

    private final PayoutStore store = mock(PayoutStore.class);
    private final PayoutProvider provider = mock(PayoutProvider.class);
    private final PayoutProperties properties = new PayoutProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PayoutOperations operations = new PayoutOperations(store, provider, properties, meters);
    private final Logger logger = (Logger) LoggerFactory.getLogger(PayoutOperations.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        when(store.requeue(eq(REF), any())).thenReturn(applied(requeued(PayoutStatus.TRANSFERRED)));
        when(store.operatorReturn(eq(ID), eq(VERSION), any())).thenReturn(applied(returned()));
        when(store.operatorReverse(eq(ID), eq(VERSION), any())).thenReturn(applied(sentToReversal()));
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogAppender() {
        logger.detachAppender(logs);
    }

    @ParameterizedTest(name = "from {0}")
    @EnumSource(value = PayoutStatus.class, names = {"ACCEPTED", "TRANSFERRED", "REVERSING"})
    void aRequeueAsksStripeNothing(PayoutStatus from) {
        // Guards a requeue that waits on Stripe: it is the action that works while Stripe is down, because the step it
        // returns to looks up what Stripe holds before it creates anything.
        rowIs(waitingFrom(from));
        when(store.requeue(eq(REF), any())).thenReturn(applied(requeued(from)));

        PayoutOperations.Result result = operations.act(REF, PayoutAction.REQUEUE);

        assertThat(result.payout()).isEqualTo(requeued(from));
        assertThat(result.leftInConnectedAccount()).isNull();
        verify(store).requeue(eq(REF), any());
        verifyNoInteractions(provider);
    }

    @Test
    void aRequeueDoesNotWaitOutTheQuietPeriod() {
        // Guards a requeue held back after the hand-over: the step keeps its key generation, so a request of the last
        // try that Stripe still runs answers the next try with the same object, and no wait is needed.
        rowIs(waitingFrom(PayoutStatus.ACCEPTED, HANDED_OVER_LATER));
        when(store.requeue(eq(REF), any())).thenReturn(applied(requeued(PayoutStatus.ACCEPTED)));

        PayoutOperations.Result result = operations.act(REF, PayoutAction.REQUEUE);

        assertThat(result.payout().status()).isEqualTo(PayoutStatus.ACCEPTED);
        verifyNoInteractions(provider);
    }

    @ParameterizedTest
    @EnumSource(PayoutAction.class)
    void anUnknownReferenceIsRefusedBeforeStripeIsAskedOrARowIsWritten(PayoutAction action) {
        // Guards an action on a reference no row has that still reaches Stripe or a write, or a detail that quotes
        // the reference back (docs/adr/0026-problem-details-never-quote-rejected-input.md).
        when(store.findForOperator(REF)).thenReturn(Optional.empty());

        ApiException refusal = refusalOf(action);

        assertThat(refusal).isInstanceOf(PayoutNotFoundException.class).hasMessage("No payout has this reference.");
        assertThat(refusal.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(provider);
        verifyNoWrites();
    }

    @ParameterizedTest(name = "{0} of a payout in {1}")
    @MethodSource("payoutsThatAreNotWaiting")
    void anActionOnAPayoutThatIsNotWaitingIsRefusedWithItsStatus(
            PayoutAction action,
            PayoutSnapshot row,
            String detail
    ) {
        // Guards an operator write on a payout the executor or an earlier action already moved on, and a repeat after
        // a lost answer that cannot tell what the first call did: the detail names the status the row is in.
        rowIs(row);

        ApiException refusal = refusalOf(action);

        assertThat(refusal).isInstanceOf(PayoutActionRefusedException.class).hasMessage(detail);
        assertThat(refusal.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        verifyNoInteractions(provider);
        verifyNoWrites();
    }

    @ParameterizedTest
    @EnumSource(PayoutAction.class)
    void aPayoutThatLeftNeedsAttentionBeforeTheWriteIsRefusedWithItsNewStatus(PayoutAction action) {
        // Guards a write reported as done when another operator or instance moved the row first: the store found it
        // in another status under its lock, and the answer says which.
        rowPassesTheChecksOf(action);
        whenWritten(action).thenReturn(
                new OperatorWrite(OperatorWrite.Kind.NOT_WAITING, inStatus(PayoutStatus.RETURNED))
        );

        ApiException refusal = refusalOf(action);

        assertThat(refusal).isInstanceOf(PayoutActionRefusedException.class).hasMessage(
                "The payout is RETURNED, not NEEDS_ATTENTION; nothing was changed. It was returned with "
                        + "OPERATOR_RETURNED."
        );
        assertThat(logs.list).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(PayoutAction.class)
    void aPayoutThatVanishedBeforeTheWriteIsNotFound(PayoutAction action) {
        // Guards a write that reads a missing row as applied and answers 200 for a payout nobody moved.
        rowPassesTheChecksOf(action);
        whenWritten(action).thenReturn(OperatorWrite.notFound());

        assertThatThrownBy(() -> operations.act(REF, action)).isInstanceOf(PayoutNotFoundException.class);

        assertThat(logs.list).isEmpty();
    }

    @Test
    void aReturnFromTheTransferStepIsRefusedBeforeStripeIsAsked() {
        // Guards a wallet credited while its money sits in a connected account that a payout can still take to the
        // bank: a reversal can bring it back, so the detail points to reverse.
        rowIs(waitingFrom(PayoutStatus.TRANSFERRED));

        ApiException refusal = refusalOf(PayoutAction.RETURN_WITHOUT_REVERSAL);

        assertThat(refusal).isInstanceOf(PayoutActionRefusedException.class).hasMessage(
                "Transfer tr_1 sits in connected account acct_1, and a reversal can still bring it back. Use reverse "
                        + "once no payout of it may reach the bank, or requeue."
        );
        assertThat(refusal.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        verifyNoInteractions(provider);
        verifyNoWrites();
    }

    @ParameterizedTest(name = "{0} from {1}")
    @CsvSource({
            "RETURN_WITHOUT_REVERSAL, TRANSFERRED",
            "REVERSE, ACCEPTED",
            "REVERSE, REVERSING"
    })
    void aRefusalByTheStepComesBeforeTheQuietPeriod(PayoutAction action, PayoutStatus from) {
        // Guards an operator told to retry after the quiet period for an action the row's step never allows: the
        // retry would be refused anyway, so the answer names the action to use at once.
        rowIs(waitingFrom(from, HANDED_OVER_LATER));

        ApiException refusal = refusalOf(action);

        assertThat(refusal).isInstanceOf(PayoutActionRefusedException.class);
        verifyNoInteractions(provider);
    }

    @ParameterizedTest(name = "from {0}")
    @EnumSource(value = PayoutStatus.class, names = {"ACCEPTED", "REVERSING"})
    void aReturnWithinTheQuietPeriodIsRefusedBeforeStripeIsAsked(PayoutStatus from) {
        // Guards a return decided while a request of the last try, which timed out on this side, may still be
        // running at Stripe: the check would miss the transfer or payout it creates, and the wallet would be credited
        // for money that left.
        rowIs(waitingFrom(from, HANDED_OVER_LATER));

        ApiException refusal = refusalOf(PayoutAction.RETURN_WITHOUT_REVERSAL);

        assertThat(refusal).isInstanceOf(PayoutActionBusyException.class).hasMessage(
                "The payout entered NEEDS_ATTENTION at 2100-01-01T09:00:30Z, and a Stripe request of its last try "
                        + "may still be running. Nothing was changed; retry after 2100-01-01T09:01:30Z."
        );
        assertThat(refusal.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(provider);
        verifyNoWrites();
    }

    @Test
    void theQuietPeriodLastsTheExecutorsUnknownBackoff() {
        // Guards a quiet period hard-coded apart from the setting it shares its premise with: the executor's first
        // retry after an unknown outcome waits as long, for the same request to finish.
        properties.getExecutor().setUnknownBackoff(Duration.ofMinutes(5));
        rowIs(waitingFrom(PayoutStatus.ACCEPTED, HANDED_OVER_LATER));

        ApiException refusal = refusalOf(PayoutAction.RETURN_WITHOUT_REVERSAL);

        assertThat(refusal).hasMessageEndingWith("retry after 2100-01-01T09:05:30Z.");
    }

    @ParameterizedTest(name = "{1} of {0} reversed")
    @CsvSource({
            "75.00, 0.00",
            "75.00, 25.00",
            "10.00, 0.00",
            "100.00, 75.00"
    })
    void aReturnFromAcceptedIsRefusedByATransferNotReversedInFull(String amount, String reversed) {
        // Guards a wallet credited while a transfer of the payout's group still holds money in a connected account.
        // Each transfer is measured against its own amount, so a stray one with other terms blocks, and so does one
        // whose reversals reach the payout's amount but not its own.
        rowIs(waitingFrom(PayoutStatus.ACCEPTED));
        List<ProviderTransfer> group = List.of(transfer("tr_9", "75.00", "75.00"), transfer("tr_2", amount, reversed));
        when(provider.findTransfers(REF)).thenReturn(group);

        ApiException refusal = refusalOf(PayoutAction.RETURN_WITHOUT_REVERSAL);

        assertThat(refusal).isInstanceOf(PayoutActionRefusedException.class).hasMessage(
                "Stripe holds transfer tr_2 in the payout's group, not reversed in full. Requeue adopts it if it is "
                        + "the only one and has the payout's terms; otherwise reverse it in the Dashboard, then return "
                        + "the payout."
        );
        assertThat(refusal.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        verifyNoWrites();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void aReturnFromAcceptedPassesWhenEveryTransferInTheGroupIsReversedInFull() {
        // Guards the exit for MULTIPLE and MISMATCH from ACCEPTED once the stray transfers are reversed by hand: a
        // check that refuses on any transfer would leave such a row waiting for good.
        rowIs(waitingFrom(PayoutStatus.ACCEPTED));
        List<ProviderTransfer> group = List.of(transfer("tr_1", "75.00", "75.00"), transfer("tr_2", "10.00", "10.0"));
        when(provider.findTransfers(REF)).thenReturn(group);

        PayoutOperations.Result result = operations.act(REF, PayoutAction.RETURN_WITHOUT_REVERSAL);

        assertThat(result.payout()).isEqualTo(returned());
        assertThat(result.leftInConnectedAccount()).isNull();
        verify(store).operatorReturn(eq(ID), eq(VERSION), any());
        verify(provider).findTransfers(REF);
        verifyNoMoreInteractions(provider);
    }

    @Test
    void aReturnFromAcceptedWithNoTransferReturnsThePayout() {
        // Guards the plain case of a return from ACCEPTED: the money never left the platform, so the group lookup
        // is the only Stripe read and the wallet gets its money back.
        rowIs(waitingFrom(PayoutStatus.ACCEPTED));
        when(provider.findTransfers(REF)).thenReturn(List.of());

        PayoutOperations.Result result = operations.act(REF, PayoutAction.RETURN_WITHOUT_REVERSAL);

        assertThat(result.payout()).isEqualTo(returned());
        verify(store).operatorReturn(eq(ID), eq(VERSION), any());
        verify(provider).findTransfers(REF);
        verifyNoMoreInteractions(provider);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listingsWithAPayoutThatMayReachTheBank")
    void aReturnFromReversingIsRefusedWhileAPayoutMayReachTheBank(List<ProviderPayout> listed, String detail) {
        // Guards a wallet credited while a payout of the reference is pending, in transit or paid: the bank and the
        // wallet would both get the money. A status this service does not know counts as one that may reach the bank.
        rowIs(waitingFrom(PayoutStatus.REVERSING));
        when(provider.findPayouts(ACCOUNT, REF, LISTED_SINCE)).thenReturn(listed);

        ApiException refusal = refusalOf(PayoutAction.RETURN_WITHOUT_REVERSAL);

        assertThat(refusal).isInstanceOf(PayoutActionRefusedException.class).hasMessage(detail);
        assertThat(refusal.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        verify(provider, never()).retrieveTransfer(any());
        verifyNoWrites();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listingsWithNoPayoutThatMayReachTheBank")
    void aReturnFromReversingPassesWhenEveryPayoutFailedOrWasCanceled(
            List<ProviderPayout> listed,
            @Nullable String ownPayout
    ) {
        // Guards a return refused for good after the payout failed and its reversal kept being refused: failed and
        // canceled are final at Stripe, so neither can still reach the bank.
        rowIs(reversingWith(ownPayout));
        when(provider.findPayouts(ACCOUNT, REF, LISTED_SINCE)).thenReturn(listed);
        transferReversed("0.00");

        PayoutOperations.Result result = operations.act(REF, PayoutAction.RETURN_WITHOUT_REVERSAL);

        assertThat(result.payout()).isEqualTo(returned());
        verify(store).operatorReturn(eq(ID), eq(VERSION), any());
        verify(provider, never()).retrievePayout(any(), any());
    }

    @Test
    void aReturnFromReversingRetrievesTheRowsOwnPayoutWhenTheListingLacksIt() {
        // Guards a paid payout the listing misses, because Stripe's list lags or the payout's metadata was edited: the
        // row recorded its id, so it is read by id and its status refuses the return.
        rowIs(waitingFrom(PayoutStatus.REVERSING));
        listed(payout("po_2", ProviderPayoutStatus.FAILED));
        when(provider.retrievePayout(ACCOUNT, PAYOUT)).thenReturn(payout(PAYOUT, ProviderPayoutStatus.PAID));

        ApiException refusal = refusalOf(PayoutAction.RETURN_WITHOUT_REVERSAL);

        assertThat(refusal).hasMessage(
                "Stripe payout po_1 for this payout is PAID and may reach the bank; nothing was changed. Requeue once "
                        + "Stripe reports it failed or canceled; a paid one needs correction by hand."
        );
        verify(provider).retrievePayout(ACCOUNT, PAYOUT);
        verifyNoWrites();
    }

    @Test
    void aReturnFromReversingPassesWhenTheRowsOwnPayoutReadByIdFailed() {
        // Guards the read by id refusing on its own: a payout missing from the listing that Stripe reports failed
        // cannot reach the bank, and the return goes ahead.
        rowIs(waitingFrom(PayoutStatus.REVERSING));
        listed();
        when(provider.retrievePayout(ACCOUNT, PAYOUT)).thenReturn(payout(PAYOUT, ProviderPayoutStatus.FAILED));
        transferReversed("0.00");

        PayoutOperations.Result result = operations.act(REF, PayoutAction.RETURN_WITHOUT_REVERSAL);

        assertThat(result.payout()).isEqualTo(returned());
        verify(provider).retrievePayout(ACCOUNT, PAYOUT);
    }

    @Test
    void theRowsOwnPayoutIsNotReadByIdWhenTheListingHasIt() {
        // Guards a third Stripe read on every return from REVERSING, each up to the call budget on the request thread:
        // the read by id is there for an anomaly only.
        rowIs(waitingFrom(PayoutStatus.REVERSING));
        listed(payout(PAYOUT, ProviderPayoutStatus.FAILED));
        transferReversed("0.00");

        operations.act(REF, PayoutAction.RETURN_WITHOUT_REVERSAL);

        verify(provider, never()).retrievePayout(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"75.00", "75.0000", "80.00"})
    void aReturnFromReversingIsRefusedWhenTheTransferIsReversedInFull(String reversed) {
        // Guards a false OPERATOR_RETURNED: the money is back on the platform, and a requeue lets the reversal step
        // record the reversal with the payout's own reason instead of a loss that did not happen. Reversals beyond the
        // payout's amount count as full as well, and would otherwise leave a negative loss.
        rowIs(waitingFrom(PayoutStatus.REVERSING));
        listed(payout(PAYOUT, ProviderPayoutStatus.FAILED));
        transferReversed(reversed);

        ApiException refusal = refusalOf(PayoutAction.RETURN_WITHOUT_REVERSAL);

        assertThat(refusal).isInstanceOf(PayoutActionRefusedException.class).hasMessage(
                "Transfer tr_1 is reversed in full. Requeue, and the reversal step records it with the payout's own "
                        + "reason."
        );
        assertThat(refusal.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        verifyNoWrites();
    }

    @ParameterizedTest(name = "{0} reversed leaves {1}")
    @CsvSource({
            "0.00, 75.00",
            "25.00, 50.00",
            "74.99, 0.01"
    })
    void aReturnFromReversingReportsWhatStaysInTheConnectedAccount(String reversed, String left) {
        // Guards the platform's loss reported wrong: it is the payout's amount less what the transfer's reversals
        // already brought back, not the whole amount and not the transfer's own reversed part.
        rowIs(waitingFrom(PayoutStatus.REVERSING));
        listed(payout(PAYOUT, ProviderPayoutStatus.FAILED));
        transferReversed(reversed);

        PayoutOperations.Result result = operations.act(REF, PayoutAction.RETURN_WITHOUT_REVERSAL);

        assertThat(result.leftInConnectedAccount()).isEqualByComparingTo(left);
    }

    @ParameterizedTest(name = "{0} from {1}")
    @CsvSource({
            "RETURN_WITHOUT_REVERSAL, REVERSING",
            "REVERSE, TRANSFERRED"
    })
    void theOperatorListsPayoutsFromAnHourBeforeTheTransferAsTheExecutorDoes(PayoutAction action, PayoutStatus from) {
        // Guards a window narrower than the executor's: it would miss a payout the executor's own listing counts and
        // let a return or a reversal pass while that payout may reach the bank.
        rowIs(waitingWithoutPayout(from));
        transferReversed("0.00");

        operations.act(REF, action);

        verify(provider).findPayouts(ACCOUNT, REF, LISTED_SINCE);
        assertThat(LISTED_SINCE).isEqualTo(PayoutSteps.payoutsListedSince(TRANSFERRED_AT));
    }

    @ParameterizedTest(name = "{2} fails")
    @MethodSource("stripeReadsThatFail")
    void aStripeReadThatFailsAnswersBadGatewayAndWritesNothing(
            PayoutAction action,
            PayoutStatus from,
            Consumer<PayoutProvider> failing
    ) {
        // Guards a decision on a half-done check, or a lookup failure answered as a refusal or a 500: nothing is
        // written until every read has succeeded, and the operator retries.
        rowIs(waitingFrom(from));
        listed(payout("po_2", ProviderPayoutStatus.FAILED));
        when(provider.retrievePayout(ACCOUNT, PAYOUT)).thenReturn(payout(PAYOUT, ProviderPayoutStatus.FAILED));
        transferReversed("0.00");
        failing.accept(provider);

        ApiException refusal = refusalOf(action);

        assertThat(refusal).isInstanceOf(PayoutCheckUnavailableException.class)
                .hasMessage("Stripe could not be asked about the payout. Nothing was changed; retry.")
                .hasCauseInstanceOf(PayoutLookupException.class);
        assertThat(refusal.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        verifyNoWrites();
        assertThat(counted(action.metricName(), "stripe_unavailable")).isEqualTo(1.0);
    }

    @ParameterizedTest
    @EnumSource(value = PayoutAction.class, names = {"RETURN_WITHOUT_REVERSAL", "REVERSE"})
    void aRowThatChangedWhileStripeWasAskedAnswersRetryAndWritesNothing(PayoutAction action) {
        // Guards a decision on a stale read: a requeue, a try and a second hand-over between the Stripe check and the
        // write leave the row waiting again, and only its version shows the check described another state.
        rowPassesTheChecksOf(action);
        whenWritten(action).thenReturn(
                new OperatorWrite(OperatorWrite.Kind.CHANGED, waitingFrom(fromWhichItPasses(action)))
        );

        ApiException refusal = refusalOf(action);

        assertThat(refusal).isInstanceOf(PayoutActionBusyException.class)
                .hasMessage("The payout changed while Stripe was asked. Nothing was changed; read it again.");
        assertThat(refusal.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(store).findForOperator(REF);
        verifyWritten(action);
        verifyNoMoreInteractions(store);
        assertThat(logs.list).isEmpty();
        assertThat(counted(action.metricName(), "retry")).isEqualTo(1.0);
    }

    @ParameterizedTest
    @EnumSource(value = PayoutAction.class, names = {"RETURN_WITHOUT_REVERSAL", "REVERSE"})
    void theWritePassesTheVersionItReadBeforeAskingStripe(PayoutAction action) {
        // Guards a write that checks the version of a second read made after Stripe answered: it would match a row
        // that changed during the check, and the version check would hold nothing.
        PayoutStatus from = action == PayoutAction.REVERSE ? PayoutStatus.TRANSFERRED : PayoutStatus.ACCEPTED;
        when(store.findForOperator(REF)).thenReturn(
                Optional.of(snapshot(VERSION, PayoutStatus.NEEDS_ATTENTION, from, null, null, HANDED_OVER)),
                Optional.of(snapshot(VERSION + 1, PayoutStatus.NEEDS_ATTENTION, from, null, null, HANDED_OVER))
        );

        operations.act(REF, action);

        verify(store).findForOperator(REF);
        verifyWritten(action);
    }

    @ParameterizedTest
    @EnumSource(PayoutAction.class)
    void theWriteIsGivenTheTimeOfTheAction(PayoutAction action) {
        // Guards a write stamped with a fixed time, the hand-over's or a clock read before the Stripe check: the
        // store makes the step due at the time it is given and dates the event of a return or a reversal by it.
        rowPassesTheChecksOf(action);
        Instant before = Instant.now();

        operations.act(REF, action);

        Instant after = Instant.now();
        assertThat(writtenAt(action)).isBetween(before, after);
    }

    @Test
    void everyStripeReadOfAReturnFromReversingEndsBeforeTheWriteBegins() {
        // Guards a Stripe read inside the write's transaction, which would hold a connection and the row's lock for up
        // to the call budget, and a read after the write that could not change the decision any more.
        rowIs(waitingFrom(PayoutStatus.REVERSING));
        listed();
        when(provider.retrievePayout(ACCOUNT, PAYOUT)).thenReturn(payout(PAYOUT, ProviderPayoutStatus.FAILED));
        transferReversed("0.00");

        operations.act(REF, PayoutAction.RETURN_WITHOUT_REVERSAL);

        InOrder order = inOrder(store, provider);
        order.verify(store).findForOperator(REF);
        order.verify(provider).findPayouts(ACCOUNT, REF, LISTED_SINCE);
        order.verify(provider).retrievePayout(ACCOUNT, PAYOUT);
        order.verify(provider).retrieveTransfer(TRANSFER);
        order.verify(store).operatorReturn(eq(ID), eq(VERSION), any());
        verifyNoMoreInteractions(provider);
    }

    @Test
    void theGroupLookupOfAReturnFromAcceptedEndsBeforeTheWriteBegins() {
        // Guards the transfer lookup moved into or after the write's transaction.
        rowIs(waitingFrom(PayoutStatus.ACCEPTED));

        operations.act(REF, PayoutAction.RETURN_WITHOUT_REVERSAL);

        InOrder order = inOrder(store, provider);
        order.verify(store).findForOperator(REF);
        order.verify(provider).findTransfers(REF);
        order.verify(store).operatorReturn(eq(ID), eq(VERSION), any());
        verifyNoMoreInteractions(provider);
    }

    @Test
    void thePayoutListingOfAReversalEndsBeforeTheWriteBegins() {
        // Guards the payout listing moved into or after the write's transaction.
        rowIs(waitingFrom(PayoutStatus.TRANSFERRED));
        listed();

        operations.act(REF, PayoutAction.REVERSE);

        InOrder order = inOrder(store, provider);
        order.verify(store).findForOperator(REF);
        order.verify(provider).findPayouts(ACCOUNT, REF, LISTED_SINCE);
        order.verify(store).operatorReverse(eq(ID), eq(VERSION), any());
        verifyNoMoreInteractions(provider);
    }

    @ParameterizedTest(name = "{0} from {1}")
    @CsvSource({
            "REQUEUE, TRANSFERRED",
            "RETURN_WITHOUT_REVERSAL, ACCEPTED",
            "RETURN_WITHOUT_REVERSAL, REVERSING",
            "REVERSE, TRANSFERRED"
    })
    void theOperatorNeverCallsAStripeCreate(PayoutAction action, PayoutStatus from) {
        // Guards a create outside the executor's claimed steps: only they hold the lease, the keys and the lookup
        // before a retry, and a waiting row stays safe to judge only while nothing new starts for its reference.
        rowIs(waitingFrom(from));
        listed(payout(PAYOUT, ProviderPayoutStatus.FAILED));
        transferReversed("0.00");

        operations.act(REF, action);

        verify(provider, never()).createTransfer(any(), any(), any(), any(), anyInt());
        verify(provider, never()).createPayout(any(), any(), any(), any(), anyInt());
        verify(provider, never()).reverseTransfer(any(), any(), any(), any(), anyInt());
        verify(provider, never()).createAccount(any(), any(), any());
        verify(provider, never()).attachBankAccount(any(), any(), any());
    }

    @Test
    void theOperationsHoldNoTransaction() {
        // Guards someone adding @Transactional here: every Stripe read would then hold a database connection and run
        // inside the transaction that writes, and the version check would compare a row with itself
        // (docs/adr/0006-short-transactions-across-bean-boundaries.md).
        assertThat(opensTransaction(PayoutOperations.class)).isFalse();
        assertThat(PayoutOperations.class.getDeclaredMethods()).noneMatch(this::opensTransaction);
    }

    @ParameterizedTest(name = "from {0}")
    @CsvSource(delimiter = '|', value = {
            "ACCEPTED  | Nothing was transferred. Use requeue or return-without-reversal.",
            "REVERSING | The payout is already being reversed. Requeue retries the reversal."
    })
    void aReversalFromAcceptedOrReversingIsRefusedWithTheActionToUse(PayoutStatus from, String detail) {
        // Guards a reversal of a transfer that does not exist, or a second reversal path beside the executor's own:
        // the detail names the action that applies to the step.
        rowIs(waitingFrom(from));

        ApiException refusal = refusalOf(PayoutAction.REVERSE);

        assertThat(refusal).isInstanceOf(PayoutActionRefusedException.class).hasMessage(detail);
        assertThat(refusal.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        verifyNoInteractions(provider);
        verifyNoWrites();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "PENDING      | Stripe payout po_1 for this payout is PENDING, has the payout's amount and currency, "
                    + "and may reach the bank; nothing was changed. Requeue, and the payout step adopts it.",
            "IN_TRANSIT   | Stripe payout po_1 for this payout is IN_TRANSIT, has the payout's amount and "
                    + "currency, and may reach the bank; nothing was changed. Requeue, and the payout step adopts it.",
            "PAID         | Stripe payout po_1 for this payout is PAID, has the payout's amount and currency, "
                    + "and may reach the bank; nothing was changed. Requeue, and the payout step adopts it.",
            "UNRECOGNIZED | Stripe payout po_1 for this payout is UNRECOGNIZED, has the payout's amount and "
                    + "currency, and may reach the bank; nothing was changed. Requeue, and the payout step adopts it."
    })
    void aReversalPointsToTheRequeueWhenTheOnlyLivePayoutHasThePayoutsTerms(
            ProviderPayoutStatus status,
            String detail
    ) {
        // Guards money that reached a bank being reversed out of the connected account, and a refusal that sends the
        // operator to wait for a payout instead of to the requeue whose payout step adopts exactly this one. The
        // executor never reverses such a transfer, and the operator's reversal keeps the same rule. A status this
        // service does not know counts as one that may reach the bank.
        rowIs(waitingFrom(PayoutStatus.TRANSFERRED));
        listed(payout(PAYOUT, status));

        ApiException refusal = refusalOf(PayoutAction.REVERSE);

        assertThat(refusal).isInstanceOf(PayoutActionRefusedException.class).hasMessage(detail);
        assertThat(refusal.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        verify(provider, never()).retrievePayout(any(), any());
        verifyNoWrites();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "75.01, PLN",
            "74.99, PLN",
            "750.00, PLN",
            "7.50, PLN",
            "75.00, EUR",
            "75.00, USD"
    })
    void aLivePayoutWithOtherTermsKeepsTheGeneralRefusalOfAReversal(String amount, String currency) {
        // Guards a requeue recommended for a payout the payout step would not adopt: it would stop on a mismatch
        // again, and the operator would be told to wait on a payout that is not this withdrawal's money. A payout of
        // another amount or currency gets the general refusal, which names no adoption.
        rowIs(waitingFrom(PayoutStatus.TRANSFERRED));
        listed(payout(PAYOUT, ProviderPayoutStatus.PENDING, amount, currency));

        ApiException refusal = refusalOf(PayoutAction.REVERSE);

        assertThat(refusal).isInstanceOf(PayoutActionRefusedException.class).hasMessage(
                "Stripe payout po_1 for this payout is PENDING and may reach the bank; nothing was changed. Requeue "
                        + "once Stripe reports it failed or canceled; a paid one needs correction by hand."
        );
        verifyNoWrites();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listingsOfTwoLivePayouts")
    void twoLivePayoutsKeepTheGeneralRefusalOfAReversalAndNameTheFirstOne(
            List<ProviderPayout> listed,
            String named,
            ProviderPayoutStatus status
    ) {
        // Guards an adoption promised while two payouts may reach the bank: the payout step stops on MULTIPLE
        // whatever their terms, so a requeue would hand the row back with the same cause. The refusal names the first
        // live payout listed, and the payouts that failed or were canceled are not counted.
        rowIs(waitingFrom(PayoutStatus.TRANSFERRED));
        when(provider.findPayouts(ACCOUNT, REF, LISTED_SINCE)).thenReturn(listed);

        ApiException refusal = refusalOf(PayoutAction.REVERSE);

        assertThat(refusal).isInstanceOf(PayoutActionRefusedException.class).hasMessage(
                "Stripe payout " + named + " for this payout is " + status + " and may reach the bank; nothing was "
                        + "changed. Requeue once Stripe reports it failed or canceled; a paid one needs correction by "
                        + "hand."
        );
        verifyNoWrites();
    }

    @Test
    void payoutsThatCannotReachTheBankDoNotStopTheAdoptionOfTheOneThatCan() {
        // Guards a count of every listed payout instead of the live ones: the failed and canceled payouts of earlier
        // tries stay in Stripe's listing, and the payout step adopts the one live payout among them.
        rowIs(waitingFrom(PayoutStatus.TRANSFERRED));
        listed(
                payout("po_7", ProviderPayoutStatus.FAILED),
                payout("po_8", ProviderPayoutStatus.PAID),
                payout("po_9", ProviderPayoutStatus.CANCELED, "10.00", "PLN")
        );

        ApiException refusal = refusalOf(PayoutAction.REVERSE);

        assertThat(refusal).isInstanceOf(PayoutActionRefusedException.class).hasMessage(
                "Stripe payout po_8 for this payout is PAID, has the payout's amount and currency, and may reach the "
                        + "bank; nothing was changed. Requeue, and the payout step adopts it."
        );
    }

    @ParameterizedTest(name = "{0} {1} against {2} {3}: same terms {4}")
    @CsvSource({
            "75.00, PLN, 75.0000, PLN, true",
            "75.0000, PLN, 75.0000, PLN, true",
            "75.0, PLN, 75.0000, PLN, true",
            "75, PLN, 75.0000, PLN, true",
            "75.0000, PLN, 75.00, PLN, true",
            "75.00, pln, 75.0000, PLN, true",
            "75.00, PLN, 75.0000, pln, true",
            "500, JPY, 500.0000, JPY, true",
            "75.01, PLN, 75.0000, PLN, false",
            "74.99, PLN, 75.0000, PLN, false",
            "750.00, PLN, 75.0000, PLN, false",
            "0.00, PLN, 75.0000, PLN, false",
            "75.00, EUR, 75.0000, PLN, false",
            "75.00, PL, 75.0000, PLN, false",
            "500, JPY, 5.0000, JPY, false"
    })
    void aPayoutHasTheRowsTermsWhenAmountAndCurrencyAgreeWhateverTheScaleOrCase(
            String amount,
            String currency,
            String rowAmount,
            String rowCurrency,
            boolean same
    ) {
        // Guards a comparison by equals, which 75.00 against 75.0000 would fail, or one that is case-sensitive on the
        // currency, and one that treats any amount or any currency as agreeing: the payout step adopts a payout by
        // these two terms, so the operator's hint must follow the same rule.
        PayoutSnapshot row = snapshot(
                VERSION,
                PayoutStatus.NEEDS_ATTENTION,
                PayoutStatus.TRANSFERRED,
                null,
                null,
                HANDED_OVER,
                new BigDecimal(rowAmount),
                rowCurrency
        );

        assertThat(PayoutOperations.sameTerms(payout(PAYOUT, ProviderPayoutStatus.PENDING, amount, currency), row))
                .isEqualTo(same);
    }

    @Test
    void aReversalIsRefusedWithinTheQuietPeriod() {
        // Guards a reversal decided while the last try's payout request, which timed out on this side, may still be
        // creating a payout the listing cannot show yet.
        rowIs(waitingFrom(PayoutStatus.TRANSFERRED, HANDED_OVER_LATER));

        ApiException refusal = refusalOf(PayoutAction.REVERSE);

        assertThat(refusal).isInstanceOf(PayoutActionBusyException.class).hasMessage(
                "The payout entered NEEDS_ATTENTION at 2100-01-01T09:00:30Z, and a Stripe request of its last try "
                        + "may still be running. Nothing was changed; retry after 2100-01-01T09:01:30Z."
        );
        assertThat(refusal.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(provider);
        verifyNoWrites();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listingsOfATransferWithNoPayoutThatMayReachTheBank")
    void aReversalSendsTheRowToTheExecutorWhenNoPayoutCanReachTheBank(List<ProviderPayout> listed) {
        // Guards the exit from NEEDS_ATTENTION after TRANSFERRED, which otherwise has none but SQL: the row moves to
        // REVERSING through the store, and the executor's reversal step does the Stripe write under its own lease.
        rowIs(waitingFrom(PayoutStatus.TRANSFERRED));
        when(provider.findPayouts(ACCOUNT, REF, LISTED_SINCE)).thenReturn(listed);

        PayoutOperations.Result result = operations.act(REF, PayoutAction.REVERSE);

        assertThat(result.payout()).isEqualTo(sentToReversal());
        assertThat(result.leftInConnectedAccount()).isNull();
        verify(store).operatorReverse(eq(ID), eq(VERSION), any());
        verify(provider).findPayouts(ACCOUNT, REF, LISTED_SINCE);
        verifyNoMoreInteractions(provider);
    }

    @Test
    void refusalsNameStripesIdsAndNeverTheUserIdOrTheRejectedInput() {
        // Guards a problem detail that quotes the reference it was given or carries a user id: every detail is written
        // from the operator's snapshot, which has no user id, and from Stripe's objects
        // (docs/adr/0026-problem-details-never-quote-rejected-input.md,
        // docs/adr/0027-user-ids-stay-out-of-logs-and-provider-metadata.md).
        assertThat(PayoutSnapshot.class.getRecordComponents()).extracting(RecordComponent::getName)
                .doesNotContain("userId");

        when(store.findForOperator(REF)).thenReturn(Optional.empty());
        String notFound = detailOf(PayoutAction.REVERSE);
        rowIs(inStatus(PayoutStatus.RETURNED));
        String notWaiting = detailOf(PayoutAction.REQUEUE);
        rowIs(waitingFrom(PayoutStatus.TRANSFERRED));
        String transferStillOut = detailOf(PayoutAction.RETURN_WITHOUT_REVERSAL);
        rowIs(waitingFrom(PayoutStatus.ACCEPTED));
        String nothingTransferred = detailOf(PayoutAction.REVERSE);
        when(provider.findTransfers(REF)).thenReturn(List.of(transfer("tr_2", "75.00", "0.00")));
        String transferInGroup = detailOf(PayoutAction.RETURN_WITHOUT_REVERSAL);
        rowIs(waitingFrom(PayoutStatus.REVERSING));
        String alreadyReversing = detailOf(PayoutAction.REVERSE);
        listed(payout(PAYOUT, ProviderPayoutStatus.IN_TRANSIT));
        String livePayout = detailOf(PayoutAction.RETURN_WITHOUT_REVERSAL);
        listed(payout(PAYOUT, ProviderPayoutStatus.FAILED));
        transferReversed("75.00");
        String alreadyReversed = detailOf(PayoutAction.RETURN_WITHOUT_REVERSAL);
        doThrow(new PayoutLookupException("Stripe is down", null)).when(provider).retrieveTransfer(TRANSFER);
        String unavailable = detailOf(PayoutAction.RETURN_WITHOUT_REVERSAL);
        rowIs(waitingFrom(PayoutStatus.TRANSFERRED, HANDED_OVER_LATER));
        String quietPeriod = detailOf(PayoutAction.REVERSE);
        rowIs(waitingFrom(PayoutStatus.TRANSFERRED));
        listed(payout(PAYOUT, ProviderPayoutStatus.IN_TRANSIT));
        String livePayoutToAdopt = detailOf(PayoutAction.REVERSE);
        listed();
        when(store.operatorReverse(eq(ID), eq(VERSION), any()))
                .thenReturn(new OperatorWrite(OperatorWrite.Kind.CHANGED, waitingFrom(PayoutStatus.TRANSFERRED)));
        String changed = detailOf(PayoutAction.REVERSE);

        List<String> details = List.of(
                notFound,
                notWaiting,
                transferStillOut,
                nothingTransferred,
                transferInGroup,
                alreadyReversing,
                livePayout,
                livePayoutToAdopt,
                alreadyReversed,
                unavailable,
                quietPeriod,
                changed
        );
        assertThat(details).allSatisfy(detail -> assertThat(detail).doesNotContain(REF));
        assertThat(transferStillOut).contains("tr_1", "acct_1");
        assertThat(transferInGroup).contains("tr_2");
        assertThat(livePayout).contains("po_1", "IN_TRANSIT");
        assertThat(livePayoutToAdopt).contains("po_1", "IN_TRANSIT");
        assertThat(alreadyReversed).contains("tr_1");
    }

    @ParameterizedTest(name = "{0} from {1}")
    @MethodSource("appliedActionLines")
    void appliedActionsAreLoggedWithTheReferenceAndNoUserId(
            PayoutAction action,
            PayoutStatus from,
            Level level,
            String message
    ) {
        // Guards an operator's money move with no trace, a return or reversal hidden below WARN, and a line that
        // carries more than the reference and Stripe's ids: the message is compared in full, so a user id added to any
        // of them fails here (docs/adr/0027-user-ids-stay-out-of-logs-and-provider-metadata.md).
        rowIs(waitingFrom(from));
        listed(payout(PAYOUT, ProviderPayoutStatus.FAILED));
        transferReversed("25.00");

        operations.act(REF, action);

        assertThat(logs.list).extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .containsExactly(tuple(level, message));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "REQUEUE, applied, requeue",
            "REQUEUE, refused, requeue",
            "RETURN_WITHOUT_REVERSAL, applied, return_without_reversal",
            "RETURN_WITHOUT_REVERSAL, refused, return_without_reversal",
            "RETURN_WITHOUT_REVERSAL, retry, return_without_reversal",
            "RETURN_WITHOUT_REVERSAL, stripe_unavailable, return_without_reversal",
            "REVERSE, applied, reverse",
            "REVERSE, refused, reverse",
            "REVERSE, retry, reverse",
            "REVERSE, stripe_unavailable, reverse"
    })
    void operatorActionsAreCountedByActionAndOutcome(PayoutAction action, String outcome, String actionTag) {
        // Guards an outcome or action tag that drifts from the names the docs and alerts use, an action counted twice,
        // and a refusal that goes uncounted.
        switch (outcome) {
            case "applied" -> rowPassesTheChecksOf(action);
            case "refused" -> rowIs(inStatus(PayoutStatus.PAID));
            case "retry" -> rowIs(waitingFrom(fromWhichItPasses(action), HANDED_OVER_LATER));
            case "stripe_unavailable" -> {
                rowPassesTheChecksOf(action);
                doThrow(down()).when(provider).findTransfers(REF);
                doThrow(down()).when(provider).findPayouts(ACCOUNT, REF, LISTED_SINCE);
            }
            default -> throw new IllegalArgumentException(outcome);
        }

        catchThrowable(() -> operations.act(REF, action));

        assertThat(countedActions()).containsExactly(Map.entry(actionTag + "/" + outcome, 1.0));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "REQUEUE, requeue",
            "RETURN_WITHOUT_REVERSAL, return_without_reversal",
            "REVERSE, reverse"
    })
    void anUnknownReferenceIsCountedAsRefused(PayoutAction action, String actionTag) {
        // Guards a 404 that escapes the counter: the caller is answered with a refusal that changed nothing, so the
        // refused outcome has to count it, and a reference no row has must not read as an action that never came.
        when(store.findForOperator(REF)).thenReturn(Optional.empty());

        catchThrowable(() -> operations.act(REF, action));

        assertThat(countedActions()).containsExactly(Map.entry(actionTag + "/refused", 1.0));
    }

    @Test
    void aDatabaseFailureReachesTheCallerUnchanged() {
        // Guards a store failure turned into a refusal or a Stripe failure: the error handler answers it with its own
        // 503, and nothing was written.
        rowIs(waitingFrom(PayoutStatus.ACCEPTED));
        QueryTimeoutException timeout = new QueryTimeoutException("statement timeout");
        when(store.operatorReturn(eq(ID), eq(VERSION), any())).thenThrow(timeout);

        assertThatThrownBy(() -> operations.act(REF, PayoutAction.RETURN_WITHOUT_REVERSAL)).isSameAs(timeout);

        assertThat(logs.list).isEmpty();
    }

    @Test
    void theReadsComeFromTheStoreAndAskStripeNothing() {
        // Guards a GET that reaches Stripe or counts as an action: the list, the counts and one payout are the rows
        // as stored.
        PayoutSnapshot row = waitingFrom(PayoutStatus.ACCEPTED);
        when(store.findAwaitingOperator(100)).thenReturn(List.of(row));
        when(store.countAwaitingOperator(PayoutStatus.REVERSING)).thenReturn(3L);
        rowIs(row);

        assertThat(operations.awaiting(100)).containsExactly(row);
        assertThat(operations.countAwaiting(PayoutStatus.REVERSING)).isEqualTo(3L);
        assertThat(operations.payout(REF)).isEqualTo(row);

        verifyNoInteractions(provider);
        assertThat(meters.getMeters()).isEmpty();
    }

    @Test
    void aReadOfAnUnknownReferenceIsNotFound() {
        // Guards a GET of a reference no row has answering 200 with an empty body, or quoting the reference back.
        when(store.findForOperator(REF)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> operations.payout(REF))
                .isInstanceOf(PayoutNotFoundException.class)
                .hasMessage("No payout has this reference.");
    }

    @ParameterizedTest(name = "backoff {0}, now {1}: quiet until {2}")
    @CsvSource(nullValues = "passed", value = {
            "PT1M, 2026-10-01T09:00:30Z, 2026-10-01T09:01:30Z",
            "PT1M, 2026-10-01T09:01:29.999Z, 2026-10-01T09:01:30Z",
            "PT1M, 2026-10-01T09:01:30Z, passed",
            "PT1M, 2026-10-01T10:00:00Z, passed",
            "PT30S, 2026-10-01T09:00:45Z, 2026-10-01T09:01:00Z",
            "PT30S, 2026-10-01T09:01:00Z, passed",
            "PT1M, 2026-10-01T09:00:00Z, 2026-10-01T09:01:30Z"
    })
    void theQuietPeriodEndsTheBackoffAfterTheHandOver(Duration backoff, Instant now, @Nullable Instant until) {
        // Guards a quiet period measured from the wrong time, ended a moment early, or held open after its end. A
        // clock behind the hand-over's, the last row, still waits the whole period.
        PayoutSnapshot row = waitingFrom(PayoutStatus.ACCEPTED, HANDED_OVER);

        assertThat(PayoutOperations.quietUntil(row, backoff, now)).isEqualTo(until);
    }

    @ParameterizedTest(name = "{1} reversed of {0}: open {2}")
    @CsvSource({
            "75.00, 0.00, true",
            "75.00, 74.99, true",
            "75.00, 75.00, false",
            "75.00, 75.0, false",
            "10.00, 0.00, true",
            "10.00, 10.00, false",
            "100.00, 75.00, true"
    })
    void aTransferBlocksAReturnFromAcceptedUntilItIsReversedInFull(String amount, String reversed, boolean open) {
        // Guards a transfer measured against the payout's amount instead of its own, or by equals, which a scale
        // apart (75.0 against 75.00) would fail.
        ProviderTransfer transfer = transfer(TRANSFER, amount, reversed);

        assertThat(PayoutOperations.transferNotReversed(List.of(transfer))).isEqualTo(open ? transfer : null);
    }

    @Test
    void theFirstTransferNotReversedInFullIsTheOneNamed() {
        // Guards a check that stops at the first transfer, reversed or not, or that names no transfer at all.
        ProviderTransfer full = transfer("tr_1", "75.00", "75.00");
        ProviderTransfer first = transfer("tr_2", "75.00", "0.00");
        ProviderTransfer second = transfer("tr_3", "10.00", "5.00");

        assertThat(PayoutOperations.transferNotReversed(List.of())).isNull();
        assertThat(PayoutOperations.transferNotReversed(List.of(full))).isNull();
        assertThat(PayoutOperations.transferNotReversed(List.of(full, first, second))).isSameAs(first);
    }

    @ParameterizedTest(name = "{0}: may reach the bank {1}")
    @CsvSource({
            "PENDING, true",
            "IN_TRANSIT, true",
            "PAID, true",
            "FAILED, false",
            "CANCELED, false",
            "UNRECOGNIZED, true"
    })
    void onlyAFailedOrCanceledPayoutCannotReachTheBank(ProviderPayoutStatus status, boolean live) {
        // Guards a status this service does not know read as safe, and a paid payout, which Stripe can still fail
        // later, treated as one whose money is back.
        ProviderPayout found = payout(PAYOUT, status);

        assertThat(PayoutOperations.mayReachTheBank(List.of(found))).isEqualTo(live ? found : null);
    }

    @Test
    void theFirstPayoutThatMayReachTheBankIsTheOneNamed() {
        // Guards a check that looks only at the first payout listed, or that names a failed one in its refusal.
        ProviderPayout failed = payout("po_1", ProviderPayoutStatus.FAILED);
        ProviderPayout canceled = payout("po_2", ProviderPayoutStatus.CANCELED);
        ProviderPayout paid = payout("po_3", ProviderPayoutStatus.PAID);
        ProviderPayout pending = payout("po_4", ProviderPayoutStatus.PENDING);

        assertThat(PayoutOperations.mayReachTheBank(List.of())).isNull();
        assertThat(PayoutOperations.mayReachTheBank(List.of(failed, canceled))).isNull();
        assertThat(PayoutOperations.mayReachTheBank(List.of(failed, canceled, paid, pending))).isSameAs(paid);
    }

    @ParameterizedTest(name = "{1} reversed of {0} covers 75.0000: {2}")
    @CsvSource({
            "75.00, 75.00, true",
            "75.00, 75.0000, true",
            "75.00, 74.99, false",
            "75.00, 0.00, false",
            "100.00, 75.00, true",
            "100.00, 74.99, false",
            "75.00, 80.00, true",
            "100.00, 80.00, true"
    })
    void aTransferIsReversedInFullOnceItsReversalsCoverThePayoutsAmount(String amount, String reversed, boolean full) {
        // Guards a full reversal missed by a scale apart, or measured against the transfer's own amount, and one that
        // takes reversals beyond the payout's amount for open: from REVERSING the row's own transfer is judged by
        // what the payout sent.
        assertThat(PayoutOperations.reversedInFull(transfer(TRANSFER, amount, reversed), AMOUNT)).isEqualTo(full);
    }

    private static Stream<Arguments> payoutsThatAreNotWaiting() {
        return Stream.of(
                Arguments.of(
                        PayoutAction.REQUEUE,
                        Named.of("PAID", inStatus(PayoutStatus.PAID)),
                        "The payout is PAID, not NEEDS_ATTENTION; nothing was changed."
                ),
                Arguments.of(
                        PayoutAction.RETURN_WITHOUT_REVERSAL,
                        Named.of("RETURNED", inStatus(PayoutStatus.RETURNED)),
                        "The payout is RETURNED, not NEEDS_ATTENTION; nothing was changed. It was returned with "
                                + "OPERATOR_RETURNED."
                ),
                Arguments.of(
                        PayoutAction.RETURN_WITHOUT_REVERSAL,
                        Named.of("REVERSING", inStatus(PayoutStatus.REVERSING)),
                        "The payout is REVERSING, not NEEDS_ATTENTION; nothing was changed."
                ),
                Arguments.of(
                        PayoutAction.REVERSE,
                        Named.of("TRANSFERRED", inStatus(PayoutStatus.TRANSFERRED)),
                        "The payout is TRANSFERRED, not NEEDS_ATTENTION; nothing was changed."
                )
        );
    }

    private static Stream<Arguments> listingsWithAPayoutThatMayReachTheBank() {
        String rest = " and may reach the bank; nothing was changed. Requeue once Stripe reports it failed or "
                + "canceled; a paid one needs correction by hand.";
        return Stream.of(
                Arguments.of(
                        Named.of("pending", List.of(payout(PAYOUT, ProviderPayoutStatus.PENDING))),
                        "Stripe payout po_1 for this payout is PENDING" + rest
                ),
                Arguments.of(
                        Named.of("in transit", List.of(payout(PAYOUT, ProviderPayoutStatus.IN_TRANSIT))),
                        "Stripe payout po_1 for this payout is IN_TRANSIT" + rest
                ),
                Arguments.of(
                        Named.of("paid", List.of(payout(PAYOUT, ProviderPayoutStatus.PAID))),
                        "Stripe payout po_1 for this payout is PAID" + rest
                ),
                Arguments.of(
                        Named.of("unrecognized", List.of(payout(PAYOUT, ProviderPayoutStatus.UNRECOGNIZED))),
                        "Stripe payout po_1 for this payout is UNRECOGNIZED" + rest
                ),
                Arguments.of(
                        Named.of(
                                "failed, then a second one paid",
                                List.of(
                                        payout(PAYOUT, ProviderPayoutStatus.FAILED),
                                        payout("po_2", ProviderPayoutStatus.PAID)
                                )
                        ),
                        "Stripe payout po_2 for this payout is PAID" + rest
                )
        );
    }

    private static Stream<Arguments> listingsWithNoPayoutThatMayReachTheBank() {
        return Stream.of(
                Arguments.of(Named.of("no payout", List.of()), null),
                Arguments.of(Named.of("failed", List.of(payout(PAYOUT, ProviderPayoutStatus.FAILED))), PAYOUT),
                Arguments.of(Named.of("canceled", List.of(payout(PAYOUT, ProviderPayoutStatus.CANCELED))), PAYOUT),
                Arguments.of(
                        Named.of(
                                "failed and canceled",
                                List.of(
                                        payout(PAYOUT, ProviderPayoutStatus.FAILED),
                                        payout("po_2", ProviderPayoutStatus.CANCELED)
                                )
                        ),
                        PAYOUT
                )
        );
    }

    private static Stream<Arguments> listingsOfTwoLivePayouts() {
        return Stream.of(
                Arguments.of(
                        Named.of(
                                "two of the terms",
                                List.of(
                                        payout("po_1", ProviderPayoutStatus.PENDING),
                                        payout("po_2", ProviderPayoutStatus.IN_TRANSIT)
                                )
                        ),
                        "po_1",
                        ProviderPayoutStatus.PENDING
                ),
                Arguments.of(
                        Named.of(
                                "one of other terms first, then one of the terms",
                                List.of(
                                        payout("po_1", ProviderPayoutStatus.PENDING, "10.00", "PLN"),
                                        payout("po_2", ProviderPayoutStatus.PENDING)
                                )
                        ),
                        "po_1",
                        ProviderPayoutStatus.PENDING
                ),
                Arguments.of(
                        Named.of(
                                "one of the terms first, then one of other terms",
                                List.of(
                                        payout("po_1", ProviderPayoutStatus.IN_TRANSIT),
                                        payout("po_2", ProviderPayoutStatus.PENDING, "75.00", "EUR")
                                )
                        ),
                        "po_1",
                        ProviderPayoutStatus.IN_TRANSIT
                ),
                Arguments.of(
                        Named.of(
                                "a failed one first, then two live ones",
                                List.of(
                                        payout("po_0", ProviderPayoutStatus.FAILED),
                                        payout("po_2", ProviderPayoutStatus.PAID),
                                        payout("po_3", ProviderPayoutStatus.PENDING)
                                )
                        ),
                        "po_2",
                        ProviderPayoutStatus.PAID
                ),
                Arguments.of(
                        Named.of(
                                "an unrecognized one and a pending one",
                                List.of(
                                        payout("po_1", ProviderPayoutStatus.UNRECOGNIZED),
                                        payout("po_2", ProviderPayoutStatus.PENDING)
                                )
                        ),
                        "po_1",
                        ProviderPayoutStatus.UNRECOGNIZED
                )
        );
    }

    private static Stream<Arguments> listingsOfATransferWithNoPayoutThatMayReachTheBank() {
        return Stream.of(
                Arguments.of(Named.of("no payout", List.of())),
                Arguments.of(Named.of("failed", List.of(payout(PAYOUT, ProviderPayoutStatus.FAILED)))),
                Arguments.of(Named.of("canceled", List.of(payout(PAYOUT, ProviderPayoutStatus.CANCELED))))
        );
    }

    private static Stream<Arguments> stripeReadsThatFail() {
        return Stream.of(
                Arguments.of(
                        PayoutAction.RETURN_WITHOUT_REVERSAL,
                        PayoutStatus.ACCEPTED,
                        Named.<Consumer<PayoutProvider>>of(
                                "the group's transfers",
                                provider -> doThrow(down()).when(provider).findTransfers(REF)
                        )
                ),
                Arguments.of(
                        PayoutAction.RETURN_WITHOUT_REVERSAL,
                        PayoutStatus.REVERSING,
                        Named.<Consumer<PayoutProvider>>of(
                                "the account's payouts",
                                provider -> doThrow(down()).when(provider).findPayouts(ACCOUNT, REF, LISTED_SINCE)
                        )
                ),
                Arguments.of(
                        PayoutAction.RETURN_WITHOUT_REVERSAL,
                        PayoutStatus.REVERSING,
                        Named.<Consumer<PayoutProvider>>of(
                                "the row's own payout",
                                provider -> doThrow(down()).when(provider).retrievePayout(ACCOUNT, PAYOUT)
                        )
                ),
                Arguments.of(
                        PayoutAction.RETURN_WITHOUT_REVERSAL,
                        PayoutStatus.REVERSING,
                        Named.<Consumer<PayoutProvider>>of(
                                "the row's transfer",
                                provider -> doThrow(down()).when(provider).retrieveTransfer(TRANSFER)
                        )
                ),
                Arguments.of(
                        PayoutAction.REVERSE,
                        PayoutStatus.TRANSFERRED,
                        Named.<Consumer<PayoutProvider>>of(
                                "the transfer's payouts",
                                provider -> doThrow(down()).when(provider).findPayouts(ACCOUNT, REF, LISTED_SINCE)
                        )
                )
        );
    }

    private static Stream<Arguments> appliedActionLines() {
        return Stream.of(
                Arguments.of(
                        PayoutAction.REQUEUE,
                        PayoutStatus.TRANSFERRED,
                        Level.INFO,
                        "Operator requeued payout " + REF + " to TRANSFERRED (was UNKNOWN_LIMIT: " + DETAIL + ")"
                ),
                Arguments.of(
                        PayoutAction.RETURN_WITHOUT_REVERSAL,
                        PayoutStatus.ACCEPTED,
                        Level.WARN,
                        "Operator returned payout " + REF + " from ACCEPTED; Stripe holds no transfer for it that is "
                                + "not reversed"
                ),
                Arguments.of(
                        PayoutAction.RETURN_WITHOUT_REVERSAL,
                        PayoutStatus.REVERSING,
                        Level.WARN,
                        "Operator returned payout " + REF + " from REVERSING without a reversal; 50.00 PLN stays in "
                                + "connected account acct_1 as the platform's loss"
                ),
                Arguments.of(
                        PayoutAction.REVERSE,
                        PayoutStatus.TRANSFERRED,
                        Level.WARN,
                        "Operator sent payout " + REF + " from TRANSFERRED to its reversal; Stripe shows no payout of "
                                + "transfer tr_1 that may reach the bank"
                )
        );
    }

    private static PayoutLookupException down() {
        return new PayoutLookupException("Stripe is down", null);
    }

    private void rowIs(PayoutSnapshot row) {
        when(store.findForOperator(REF)).thenReturn(Optional.of(row));
    }

    /**
     * A waiting row whose state and quiet period let the action reach the store, with Stripe holding nothing in the
     * way: a return from ACCEPTED, the others from TRANSFERRED.
     */
    private void rowPassesTheChecksOf(PayoutAction action) {
        rowIs(waitingFrom(fromWhichItPasses(action)));
    }

    private static PayoutStatus fromWhichItPasses(PayoutAction action) {
        return action == PayoutAction.RETURN_WITHOUT_REVERSAL ? PayoutStatus.ACCEPTED : PayoutStatus.TRANSFERRED;
    }

    private void listed(ProviderPayout... found) {
        when(provider.findPayouts(ACCOUNT, REF, LISTED_SINCE)).thenReturn(List.of(found));
    }

    private void transferReversed(String reversed) {
        when(provider.retrieveTransfer(TRANSFER)).thenReturn(transfer(TRANSFER, "75.00", reversed));
    }

    private OngoingStubbing<OperatorWrite> whenWritten(PayoutAction action) {
        return switch (action) {
            case REQUEUE -> when(store.requeue(eq(REF), any()));
            case RETURN_WITHOUT_REVERSAL -> when(store.operatorReturn(eq(ID), eq(VERSION), any()));
            case REVERSE -> when(store.operatorReverse(eq(ID), eq(VERSION), any()));
        };
    }

    private void verifyWritten(PayoutAction action) {
        switch (action) {
            case REQUEUE -> verify(store).requeue(eq(REF), any());
            case RETURN_WITHOUT_REVERSAL -> verify(store).operatorReturn(eq(ID), eq(VERSION), any());
            case REVERSE -> verify(store).operatorReverse(eq(ID), eq(VERSION), any());
        }
    }

    private Instant writtenAt(PayoutAction action) {
        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        switch (action) {
            case REQUEUE -> verify(store).requeue(eq(REF), at.capture());
            case RETURN_WITHOUT_REVERSAL -> verify(store).operatorReturn(eq(ID), eq(VERSION), at.capture());
            case REVERSE -> verify(store).operatorReverse(eq(ID), eq(VERSION), at.capture());
        }
        return at.getValue();
    }

    private void verifyNoWrites() {
        verify(store, never()).requeue(any(), any());
        verify(store, never()).operatorReturn(any(), any(), any());
        verify(store, never()).operatorReverse(any(), any(), any());
    }

    private ApiException refusalOf(PayoutAction action) {
        Throwable thrown = catchThrowable(() -> operations.act(REF, action));
        assertThat(thrown).isInstanceOf(ApiException.class);
        return (ApiException) thrown;
    }

    private String detailOf(PayoutAction action) {
        return refusalOf(action).getMessage();
    }

    private double counted(String action, String outcome) {
        return meters.counter("payment.payouts.operator.actions", "action", action, "outcome", outcome).count();
    }

    private Map<String, Double> countedActions() {
        Map<String, Double> counted = new HashMap<>();
        for (Counter counter : meters.find("payment.payouts.operator.actions").counters()) {
            counted.put(counter.getId().getTag("action") + "/" + counter.getId().getTag("outcome"), counter.count());
        }
        return counted;
    }

    private boolean opensTransaction(AnnotatedElement element) {
        return AnnotatedElementUtils.hasAnnotation(
                element,
                org.springframework.transaction.annotation.Transactional.class
        ) || AnnotatedElementUtils.hasAnnotation(element, jakarta.transaction.Transactional.class);
    }

    private static OperatorWrite applied(PayoutSnapshot payout) {
        return new OperatorWrite(OperatorWrite.Kind.APPLIED, payout);
    }

    private static PayoutSnapshot waitingFrom(PayoutStatus from) {
        return waitingFrom(from, HANDED_OVER);
    }

    /**
     * A row waiting from the step, handed over at the given time. One waiting from REVERSING recorded its failed
     * payout.
     */
    private static PayoutSnapshot waitingFrom(PayoutStatus from, Instant handedOverAt) {
        String payoutId = from == PayoutStatus.REVERSING ? PAYOUT : null;
        return snapshot(VERSION, PayoutStatus.NEEDS_ATTENTION, from, null, payoutId, handedOverAt);
    }

    /**
     * A row waiting from REVERSING with the given payout id, or none when the payout step was refused before Stripe
     * created one.
     */
    private static PayoutSnapshot reversingWith(@Nullable String payoutId) {
        return snapshot(VERSION, PayoutStatus.NEEDS_ATTENTION, PayoutStatus.REVERSING, null, payoutId, HANDED_OVER);
    }

    private static PayoutSnapshot waitingWithoutPayout(PayoutStatus from) {
        return snapshot(VERSION, PayoutStatus.NEEDS_ATTENTION, from, null, null, HANDED_OVER);
    }

    private static PayoutSnapshot inStatus(PayoutStatus status) {
        PayoutReason reason = switch (status) {
            case RETURNED -> PayoutReason.OPERATOR_RETURNED;
            case REVERSING -> PayoutReason.PAYOUT_FAILED;
            default -> null;
        };
        return snapshot(VERSION + 1, status, null, reason, null, HANDED_OVER);
    }

    private static PayoutSnapshot requeued(PayoutStatus step) {
        return snapshot(VERSION + 1, step, null, null, null, HANDED_OVER);
    }

    private static PayoutSnapshot returned() {
        return inStatus(PayoutStatus.RETURNED);
    }

    private static PayoutSnapshot sentToReversal() {
        return inStatus(PayoutStatus.REVERSING);
    }

    private static PayoutSnapshot snapshot(
            Long version,
            PayoutStatus status,
            @Nullable PayoutStatus attentionFrom,
            @Nullable PayoutReason reason,
            @Nullable String payoutId,
            Instant updatedAt
    ) {
        return snapshot(version, status, attentionFrom, reason, payoutId, updatedAt, AMOUNT, "PLN");
    }

    private static PayoutSnapshot snapshot(
            Long version,
            PayoutStatus status,
            @Nullable PayoutStatus attentionFrom,
            @Nullable PayoutReason reason,
            @Nullable String payoutId,
            Instant updatedAt,
            BigDecimal amount,
            String currency
    ) {
        PayoutStatus step = attentionFrom == null ? status : attentionFrom;
        boolean transferred = step != PayoutStatus.ACCEPTED;
        boolean waiting = attentionFrom != null;
        return new PayoutSnapshot(
                ID,
                version,
                REF,
                status,
                attentionFrom,
                waiting ? AttentionCause.UNKNOWN_LIMIT : null,
                waiting ? DETAIL : null,
                amount,
                currency,
                reason,
                null,
                1,
                12,
                CREATED_AT,
                transferred ? ACCOUNT : null,
                transferred ? TRANSFER : null,
                payoutId,
                null,
                transferred ? TRANSFERRED_AT : null,
                null,
                CREATED_AT,
                updatedAt
        );
    }

    private static ProviderTransfer transfer(String id, String amount, String reversed) {
        return new ProviderTransfer(id, ACCOUNT, new BigDecimal(amount), new BigDecimal(reversed), "PLN");
    }

    private static ProviderPayout payout(String id, ProviderPayoutStatus status) {
        return payout(id, status, "75.00", "PLN");
    }

    private static ProviderPayout payout(String id, ProviderPayoutStatus status, String amount, String currency) {
        String failureCode = status == ProviderPayoutStatus.FAILED ? "account_closed" : null;
        return new ProviderPayout(id, status, failureCode, REF, new BigDecimal(amount), currency, "ba_1");
    }
}
