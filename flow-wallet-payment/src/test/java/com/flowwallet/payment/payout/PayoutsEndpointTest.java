package com.flowwallet.payment.payout;

import com.flowwallet.payment.payout.PayoutsEndpoint.ActionResult;
import com.flowwallet.payment.payout.PayoutsEndpoint.AttentionList;
import com.flowwallet.payment.payout.PayoutsEndpoint.PayoutView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.jmx.annotation.JmxEndpoint;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.flowwallet.payment.payout.PayoutAction.*;
import static com.flowwallet.payment.payout.PayoutStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PayoutsEndpointTest {
    private static final String REF = "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d";
    private static final String OTHER_REF = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";
    private static final String USER = "0b9e5c1a-7d2f-4a3b-8c4d-5e6f7a8b9c0d";
    private static final long ROW_ID = 8800123L;
    private static final long ROW_VERSION = 4400321L;
    private static final Instant CREATED_AT = Instant.parse("2026-10-09T08:15:30.123456Z");
    private static final Instant TRANSFERRED_AT = Instant.parse("2026-10-09T08:16:00.5Z");
    private static final Instant PAID_AT = Instant.parse("2026-10-09T09:00:00Z");
    private static final Instant HANDED_OVER = Instant.parse("2026-10-10T09:00:30Z");
    private static final Instant NEXT_ATTEMPT = Instant.parse("2026-10-10T08:00:00Z");
    private static final Instant CLAIMED_UNTIL = Instant.parse("2026-10-10T09:05:17Z");
    private static final Instant RECONCILED_AT = Instant.parse("2026-10-10T09:04:42.777Z");
    private static final String UNKNOWN_ACTION_DETAIL =
            "The action must be requeue, return-without-reversal or reverse.";

    private final PayoutOperations operations = mock(PayoutOperations.class);
    private final PayoutsEndpoint endpoint = new PayoutsEndpoint(operations);
    private final JsonMapper mapper = JsonMapper.builder().build();

    /**
     * A row with every column an operator must not see set, so a view that leaks one shows it.
     */
    private static Payout.PayoutBuilder row(PayoutStatus status) {
        return Payout.builder()
                .id(ROW_ID)
                .version(ROW_VERSION)
                .transactionReference(REF)
                .userId(USER)
                .amount(new BigDecimal("75.0000"))
                .currency("PLN")
                .status(status)
                .stripeAccountId("acct_1")
                .stripeTransferId("tr_1")
                .keyGen(1)
                .attempts(12)
                .nextAttemptAt(NEXT_ATTEMPT)
                .claimedUntil(CLAIMED_UNTIL)
                .lastReconciledAt(RECONCILED_AT)
                .createdAt(CREATED_AT)
                .updatedAt(HANDED_OVER);
    }

    private static PayoutSnapshot snapshot(PayoutStatus status) {
        return PayoutSnapshot.of(row(status).build());
    }

    private static PayoutSnapshot waiting(String reference, PayoutStatus from) {
        return PayoutSnapshot.of(row(NEEDS_ATTENTION)
                .transactionReference(reference)
                .attentionFrom(from)
                .attentionCause(AttentionCause.UNKNOWN_LIMIT)
                .attentionDetail("InvalidRequestException, status 400")
                .build());
    }

    private static PayoutOperations.Result answered(PayoutSnapshot payout) {
        return new PayoutOperations.Result(payout, null);
    }

    @Test
    void theEndpointIsWebOnlyUnderTheIdPayoutsAndItsWriteTakesTheReferenceThenTheAction() {
        // Guards an endpoint that is also exposed over JMX or under another id, and a write whose selectors swap or
        // swallow the rest of the path: the action would be read as the reference, and a payout looked up under the
        // word "requeue".
        assertThat(PayoutsEndpoint.class.getAnnotation(WebEndpoint.class).id()).isEqualTo("payouts");
        assertThat(PayoutsEndpoint.class.isAnnotationPresent(Endpoint.class)).isFalse();
        assertThat(PayoutsEndpoint.class.isAnnotationPresent(JmxEndpoint.class)).isFalse();

        List<Method> writes = operationsOf(WriteOperation.class);

        assertThat(writes).hasSize(1);
        Parameter[] parameters = writes.getFirst().getParameters();
        assertThat(parameters).extracting(Parameter::getName).containsExactly("reference", "action");
        assertThat(parameters).extracting(Parameter::getType).containsExactly(String.class, String.class);
        assertThat(parameters)
                .extracting(parameter -> parameter.getAnnotation(Selector.class).match())
                .containsOnly(Selector.Match.SINGLE);
    }

    @Test
    void theOnlyOperationsAreTheListTheReadByReferenceAndTheWrite() throws Exception {
        // Guards a reference that turns into a query parameter when its selector is dropped, a list that takes
        // parameters nobody documented, and a second write or a delete that no review looked at.
        Method list = PayoutsEndpoint.class.getMethod("attention");
        Method one = PayoutsEndpoint.class.getMethod("payout", String.class);

        assertThat(operationsOf(ReadOperation.class)).containsExactlyInAnyOrder(list, one);
        assertThat(operationsOf(WriteOperation.class)).hasSize(1);
        assertThat(operationsOf(DeleteOperation.class)).isEmpty();
        Parameter reference = one.getParameters()[0];
        assertThat(reference.getName()).isEqualTo("reference");
        assertThat(reference.getAnnotation(Selector.class).match()).isEqualTo(Selector.Match.SINGLE);
    }

    private static List<Method> operationsOf(Class<? extends Annotation> kind) {
        return Arrays.stream(PayoutsEndpoint.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(kind))
                .toList();
    }

    @Test
    void theEndpointHoldsNoTransaction() {
        // Guards someone adding @Transactional here: an action would then hold a database connection across every
        // Stripe read the operations make, and the operations' version check would compare a row with itself
        // (docs/adr/0006-short-transactions-across-bean-boundaries.md).
        assertThat(opensTransaction(PayoutsEndpoint.class)).isFalse();
        assertThat(PayoutsEndpoint.class.getDeclaredMethods()).noneMatch(this::opensTransaction);
    }

    private boolean opensTransaction(AnnotatedElement element) {
        return AnnotatedElementUtils.hasAnnotation(
                element,
                org.springframework.transaction.annotation.Transactional.class
        ) || AnnotatedElementUtils.hasAnnotation(element, jakarta.transaction.Transactional.class);
    }

    @Test
    void anUpperCaseReferenceIsFoldedBeforeTheLookup() {
        // Guards a lookup under the reference as typed: rows are stored lower-case, so an operator who pasted it
        // upper-case would be told there is no such payout while the money waits.
        when(operations.payout(REF)).thenReturn(snapshot(ACCEPTED));
        when(operations.act(REF, REQUEUE)).thenReturn(answered(snapshot(ACCEPTED)));

        PayoutView view = endpoint.payout(REF.toUpperCase(Locale.ROOT));
        ActionResult result = endpoint.act(REF.toUpperCase(Locale.ROOT), "requeue");

        assertThat(view.reference()).isEqualTo(REF);
        assertThat(result.payout().reference()).isEqualTo(REF);
        verify(operations).payout(REF);
        verify(operations).act(REF, REQUEUE);
        verifyNoMoreInteractions(operations);
    }

    @Test
    void theFoldDoesNotDependOnTheServersLanguage() {
        // Guards a fold with the default locale: under Turkish an upper-case I becomes a dotless one, so the lookup
        // would use text that no row holds.
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            when(operations.payout(anyString())).thenReturn(snapshot(ACCEPTED));

            endpoint.payout("REF-I");

            verify(operations).payout("ref-i");
        } finally {
            Locale.setDefault(before);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "approve",
            "REQUEUE",
            "Reverse",
            "",
            " requeue",
            "return_without_reversal",
            "requeue\nWARN forged log line",
            "requeue/../reverse"
    })
    void anUnknownActionIsRefusedWithoutQuotingItAndReachesNoOperation(String action) {
        // Guards a word that is no action being passed on, which could move money, and a refusal that quotes it,
        // which lets a caller write lines into the log.
        // See docs/adr/0026-problem-details-never-quote-rejected-input.md.
        assertThatThrownBy(() -> endpoint.act(REF, action))
                .isInstanceOfSatisfying(UnknownPayoutActionException.class, refused -> {
                    assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(refused).hasMessage(UNKNOWN_ACTION_DETAIL);
                });
        verifyNoInteractions(operations);
    }

    @ParameterizedTest
    @CsvSource({
            "requeue, REQUEUE",
            "return-without-reversal, RETURN_WITHOUT_REVERSAL",
            "reverse, REVERSE"
    })
    void eachActionReachesItsOwnOperation(String path, PayoutAction expected) {
        // Guards a path that reaches another action's operation: a reverse that ran as a return would credit the
        // wallet while the transfer sits in the connected account.
        when(operations.act(REF, expected)).thenReturn(answered(snapshot(ACCEPTED)));

        ActionResult result = endpoint.act(REF, path);

        assertThat(result.action()).isEqualTo(path);
        verify(operations).act(REF, expected);
        verifyNoMoreInteractions(operations);
    }

    @Test
    void anActionAnswersWithThePayoutAsItStandsAfterTheWriteAndNoLeftoverWhenNothingStaysBehind() {
        // Guards an answer that drops the payout after the write, which would leave the operator reading the row
        // again to learn what happened, and a leftover amount shown for an action that leaves nothing behind.
        PayoutSnapshot returned = PayoutSnapshot.of(row(RETURNED).reasonCode(PayoutReason.OPERATOR_RETURNED).build());
        when(operations.act(REF, RETURN_WITHOUT_REVERSAL)).thenReturn(answered(returned));

        ActionResult result = endpoint.act(REF, "return-without-reversal");

        assertThat(result.payout().status()).isEqualTo("RETURNED");
        assertThat(result.payout().reasonCode()).isEqualTo("OPERATOR_RETURNED");
        assertThat(result.payout().actions()).isEmpty();
        assertThat(result.leftInConnectedAccount()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "PLN, 30.0000, 30.00",
            "PLN, 0.0100, 0.01",
            "EUR, 75.5000, 75.50",
            "JPY, 500.0000, 500",
            "HUF, 1200.0000, 1200"
    })
    void whatStaysInTheConnectedAccountIsPrintedAtTheCurrencysPayoutScale(
            String currency,
            BigDecimal left,
            String expected
    ) {
        // Guards the ledger's scale of four reaching the operator, and a float read of it: the figure is the
        // platform's loss, and the operator reads it as money.
        PayoutSnapshot returned = PayoutSnapshot.of(row(RETURNED)
                .currency(currency)
                .reasonCode(PayoutReason.OPERATOR_RETURNED)
                .build());
        when(operations.act(REF, RETURN_WITHOUT_REVERSAL)).thenReturn(new PayoutOperations.Result(returned, left));

        ActionResult result = endpoint.act(REF, "return-without-reversal");

        assertThat(result.leftInConnectedAccount()).isEqualTo(expected);
    }

    @Test
    void theListCountsWaitingPayoutsByStep() {
        // Guards a count that drops a step or is read from the page: the operator would take a list cut at the
        // limit for everything that waits, and a step with none would vanish from the answer instead of reading 0.
        when(operations.countAwaiting(ACCEPTED)).thenReturn(1L);
        when(operations.countAwaiting(TRANSFERRED)).thenReturn(0L);
        when(operations.countAwaiting(REVERSING)).thenReturn(1L);
        when(operations.awaiting(101)).thenReturn(List.of(waiting(REF, ACCEPTED), waiting(OTHER_REF, REVERSING)));

        AttentionList list = endpoint.attention();

        assertThat(list.needsAttention()).isEqualTo(2L);
        assertThat(list.byFrom())
                .containsExactly(entry("ACCEPTED", 1L), entry("TRANSFERRED", 0L), entry("REVERSING", 1L));
        assertThat(list.truncated()).isFalse();
        assertThat(list.payouts()).extracting(PayoutView::reference).containsExactly(REF, OTHER_REF);
        assertThat(list.payouts()).extracting(PayoutView::attentionFrom).containsExactly("ACCEPTED", "REVERSING");
    }

    @ParameterizedTest
    @CsvSource({
            "1, 1, 1, 3, 3, 3, false",
            "0, 0, 0, 0, 0, 0, false",
            "50, 30, 20, 100, 100, 100, false",
            "50, 30, 20, 100, 101, 100, true",
            "70, 20, 40, 130, 100, 100, false",
            "70, 20, 40, 130, 101, 100, true",
            "0, 0, 0, 0, 101, 100, true",
            "0, 0, 101, 101, 100, 100, false",
            "0, 0, 101, 101, 101, 100, true"
    })
    void theListSaysWhenItIsCutAtTheLimitByWhatTheReadFound(
            long accepted,
            long transferred,
            long reversing,
            long waiting,
            int read,
            int shown,
            boolean cut
    ) {
        // Guards a list cut at the limit that reads as complete, which hides every payout behind the first hundred,
        // a full page of exactly the waiting payouts that claims to be cut, and a flag taken from the counts: they are
        // read apart from the rows, so a row that moved between the two reads would make them disagree.
        when(operations.countAwaiting(ACCEPTED)).thenReturn(accepted);
        when(operations.countAwaiting(TRANSFERRED)).thenReturn(transferred);
        when(operations.countAwaiting(REVERSING)).thenReturn(reversing);
        when(operations.awaiting(101)).thenReturn(Collections.nCopies(read, waiting(REF, ACCEPTED)));

        AttentionList list = endpoint.attention();

        assertThat(list.needsAttention()).isEqualTo(waiting);
        assertThat(list.payouts()).hasSize(shown);
        assertThat(list.truncated()).isEqualTo(cut);
        verify(operations).awaiting(101);
        assertThat(PayoutsEndpoint.LIST_LIMIT).isEqualTo(100);
    }

    @Test
    void theListReadsOneRowMoreThanItShows() {
        // Guards a read of exactly the limit, which could not tell a page that is full from one that is cut.
        when(operations.awaiting(anyInt())).thenReturn(List.of());

        endpoint.attention();

        verify(operations).awaiting(PayoutsEndpoint.LIST_LIMIT + 1);
        verify(operations, never()).awaiting(PayoutsEndpoint.LIST_LIMIT);
    }

    @Test
    void theListShowsTheFirstHundredRowsReadAndNotTheOneBeyond() {
        // Guards a cut that drops the longest-waiting rows instead of the extra one: the rows come longest-waiting
        // first, and the one read only to learn that the list is cut must not reach the answer.
        List<PayoutSnapshot> read = IntStream.rangeClosed(1, 101)
                .mapToObj(number -> waiting("ref-" + number, ACCEPTED))
                .toList();
        when(operations.awaiting(101)).thenReturn(read);

        AttentionList list = endpoint.attention();

        assertThat(list.truncated()).isTrue();
        assertThat(list.payouts()).hasSize(100);
        assertThat(list.payouts().getFirst().reference()).isEqualTo("ref-1");
        assertThat(list.payouts().getLast().reference()).isEqualTo("ref-100");
    }

    static Stream<Arguments> actionsOfAView() {
        return Stream.of(
                arguments(waiting(REF, ACCEPTED), List.of("requeue", "return-without-reversal")),
                arguments(waiting(REF, TRANSFERRED), List.of("requeue", "reverse")),
                arguments(waiting(REF, REVERSING), List.of("requeue", "return-without-reversal")),
                arguments(snapshot(ACCEPTED), List.of()),
                arguments(snapshot(TRANSFERRED), List.of()),
                arguments(snapshot(PAYOUT_CREATED), List.of()),
                arguments(snapshot(PAID), List.of()),
                arguments(snapshot(REVERSING), List.of()),
                arguments(snapshot(RETURNED), List.of()),
                arguments(PayoutSnapshot.of(row(RETURNED).attentionFrom(TRANSFERRED).build()), List.of())
        );
    }

    @ParameterizedTest
    @MethodSource("actionsOfAView")
    void aPayoutViewOffersOnlyTheActionsItsStateAllows(PayoutSnapshot payout, List<String> expected) {
        // Guards an action offered where it would be refused or would be wrong, such as a return from TRANSFERRED,
        // and actions offered on a row that is not waiting, even one that still carries a step in a stale column.
        assertThat(PayoutView.of(payout).actions()).containsExactlyElementsOf(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "PLN, 75.0000, 75.00",
            "PLN, 0.0500, 0.05",
            "PLN, 1234567.8900, 1234567.89",
            "PLN, 100, 100.00",
            "EUR, 10.0000, 10.00",
            "JPY, 500.0000, 500",
            "HUF, 1200.0000, 1200"
    })
    void aPayoutViewPrintsItsAmountOnThePayoutGrid(String currency, BigDecimal amount, String expected) {
        // Guards the ledger's scale of four reaching the operator, and a scale that does not follow the currency:
        // 75.0000 PLN reads as 75.00, and a whole-unit currency has no decimals to print.
        PayoutSnapshot payout = PayoutSnapshot.of(row(ACCEPTED).currency(currency).amount(amount).build());

        assertThat(PayoutView.of(payout).amount()).isEqualTo(expected);
        assertThat(PayoutView.of(payout).currency()).isEqualTo(currency);
    }

    @ParameterizedTest
    @CsvSource({
            "PLN, 75.1234, 75.1234",
            "JPY, 500.5000, 500.5"
    })
    void anAmountOffThePayoutGridIsShownInFullInsteadOfFailingTheView(
            String currency,
            BigDecimal amount,
            String expected
    ) {
        // Guards a rounding refusal on a row that carries more decimals than the grid: the list would fail as a
        // whole, and the operator would lose sight of every other waiting payout.
        PayoutSnapshot payout = PayoutSnapshot.of(row(ACCEPTED).currency(currency).amount(amount).build());

        assertThat(PayoutView.of(payout).amount()).isEqualTo(expected);
    }

    @Test
    void aPayoutViewPrintsItsTimesAsIsoText() {
        // Guards a time left to the actuator's JSON mapper, which can print it as a number: the text is the
        // instant's own ISO form, with as many fraction digits as it has in groups of three.
        PayoutSnapshot payout = PayoutSnapshot.of(row(NEEDS_ATTENTION)
                .attentionFrom(REVERSING)
                .attentionCause(AttentionCause.REVERSAL_REFUSED)
                .transferredAt(TRANSFERRED_AT)
                .paidAt(PAID_AT)
                .build());

        PayoutView view = PayoutView.of(payout);

        assertThat(view.createdAt()).isEqualTo("2026-10-09T08:15:30.123456Z");
        assertThat(view.transferredAt()).isEqualTo("2026-10-09T08:16:00.500Z");
        assertThat(view.paidAt()).isEqualTo("2026-10-09T09:00:00Z");
        assertThat(view.since()).isEqualTo("2026-10-10T09:00:30Z");
    }

    @Test
    void aTimeThatIsNotSetIsNull() {
        // Guards a null time printed as text such as "null", or one that fails the view of a payout not yet
        // transferred or paid.
        PayoutView view = PayoutView.of(snapshot(ACCEPTED));

        assertThat(view.transferredAt()).isNull();
        assertThat(view.paidAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = PayoutStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "NEEDS_ATTENTION")
    void sinceIsNotSetOutsideNeedsAttention(PayoutStatus status) {
        // Guards the last update of a row in an executor step or a final one passing as the time it was handed to
        // an operator: only in NEEDS_ATTENTION is it that time.
        assertThat(PayoutView.of(snapshot(status)).since()).isNull();
    }

    @Test
    void sinceIsTheHandOverWhileThePayoutNeedsAttention() {
        // Guards a wait that cannot be told from the view: it is read against the quiet period, which starts at the
        // hand-over.
        assertThat(PayoutView.of(waiting(REF, TRANSFERRED)).since()).isEqualTo("2026-10-10T09:00:30Z");
    }

    @ParameterizedTest
    @EnumSource(value = PayoutStatus.class, names = {"ACCEPTED", "TRANSFERRED", "REVERSING"})
    void nextAttemptAtIsShownInAnExecutorStep(PayoutStatus status) {
        // Guards the retry time of a step being hidden while the executor still works it.
        assertThat(PayoutView.of(snapshot(status)).nextAttemptAt()).isEqualTo("2026-10-10T08:00:00Z");
    }

    @ParameterizedTest
    @EnumSource(value = PayoutStatus.class, names = {"PAYOUT_CREATED", "PAID", "RETURNED", "NEEDS_ATTENTION"})
    void nextAttemptAtIsNotShownWhereNoExecutorWorksThePayout(PayoutStatus status) {
        // Guards a stale backoff on a waiting row reading as a retry that will happen: nothing claims a row in
        // NEEDS_ATTENTION until an operator moves it.
        PayoutSnapshot payout = status == NEEDS_ATTENTION ? waiting(REF, ACCEPTED) : snapshot(status);

        assertThat(PayoutView.of(payout).nextAttemptAt()).isNull();
    }

    @Test
    void aWaitingPayoutShowsItsStepItsCauseAndItsDetail() {
        // Guards a hand-over the operator cannot read: without the cause and the detail Stripe gave, the list says
        // that a payout waits and not why.
        PayoutView view = PayoutView.of(waiting(REF, TRANSFERRED));

        assertThat(view.status()).isEqualTo("NEEDS_ATTENTION");
        assertThat(view.attentionFrom()).isEqualTo("TRANSFERRED");
        assertThat(view.cause()).isEqualTo("UNKNOWN_LIMIT");
        assertThat(view.detail()).isEqualTo("InvalidRequestException, status 400");
    }

    @Test
    void aPayoutThatWaitedBeforeTheCauseWasStoredShowsNoCauseAndNoDetail() {
        // Guards a view that fails on a row handed over before the cause had a column, which would hide the oldest
        // waiting payouts from the list.
        PayoutSnapshot payout = PayoutSnapshot.of(row(NEEDS_ATTENTION).attentionFrom(ACCEPTED).build());

        PayoutView view = PayoutView.of(payout);

        assertThat(view.cause()).isNull();
        assertThat(view.detail()).isNull();
        assertThat(view.actions()).containsExactly("requeue", "return-without-reversal");
    }

    @Test
    void aRefusalOrANotFoundFromTheOperationsReachesTheCallerUnchanged() {
        // Guards an endpoint that catches and wraps what the operations throw: the platform's handler renders only
        // an ApiException as its own status, so a wrapped one would answer 500 for a refusal the operator can act on.
        PayoutActionRefusedException refusal = PayoutActionRefusedException.nothingTransferred();
        PayoutNotFoundException missing = new PayoutNotFoundException();
        when(operations.act(REF, REVERSE)).thenThrow(refusal);
        when(operations.payout(REF)).thenThrow(missing);

        assertThatThrownBy(() -> endpoint.act(REF, "reverse")).isSameAs(refusal);
        assertThatThrownBy(() -> endpoint.payout(REF)).isSameAs(missing);
    }

    @Test
    void noAnswerCarriesAUserIdTheRowIdOrItsVersion() {
        // Guards a view built from the entity's getters: a user id in an answer reaches whoever can reach the
        // endpoint's port, and the row id and version are the operator code's own guard, not the operator's.
        for (Class<?> answer : List.of(AttentionList.class, PayoutView.class, ActionResult.class)) {
            assertThat(Arrays.stream(answer.getRecordComponents()).map(RecordComponent::getName))
                    .as(answer.getSimpleName())
                    .doesNotContain("userId", "id", "version", "claimedUntil", "lastReconciledAt")
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("user"));
        }
        PayoutView view = PayoutView.of(snapshot(NEEDS_ATTENTION));
        AttentionList list = new AttentionList(1, Map.of("ACCEPTED", 1L), false, List.of(view));
        ActionResult acted = new ActionResult("reverse", view, null);

        String json = mapper.writeValueAsString(list) + mapper.writeValueAsString(acted);

        assertThat(json).contains(REF);
        assertThat(json)
                .doesNotContain(USER)
                .doesNotContain(String.valueOf(ROW_ID))
                .doesNotContain(String.valueOf(ROW_VERSION))
                .doesNotContain("2026-10-10T09:05:17Z")
                .doesNotContain("2026-10-10T09:04:42.777Z");
    }

    @Test
    void aPayoutViewSerializesWithExactlyTheFieldsTheOperatorSees() {
        // Guards a field added to the view without a decision: every one reaches whoever can open the endpoint's
        // port, and a null stays a key so the answer keeps one shape.
        PayoutView view = PayoutView.of(waiting(REF, ACCEPTED));

        assertThat(mapper.readTree(mapper.writeValueAsString(view)).propertyNames()).containsExactlyInAnyOrder(
                "reference",
                "status",
                "attentionFrom",
                "cause",
                "detail",
                "since",
                "amount",
                "currency",
                "reasonCode",
                "failureCode",
                "keyGen",
                "attempts",
                "nextAttemptAt",
                "stripeAccountId",
                "stripeTransferId",
                "stripePayoutId",
                "stripeReversalId",
                "transferredAt",
                "paidAt",
                "createdAt",
                "actions"
        );
    }
}
