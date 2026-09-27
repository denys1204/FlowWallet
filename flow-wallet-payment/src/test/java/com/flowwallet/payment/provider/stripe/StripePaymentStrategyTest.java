package com.flowwallet.payment.provider.stripe;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowwallet.payment.provider.dto.PaymentInitiationResult;
import com.flowwallet.payment.provider.dto.PaymentRequestContext;
import com.flowwallet.payment.provider.dto.WebhookEventType;
import com.flowwallet.payment.provider.dto.WebhookResult;
import com.flowwallet.payment.provider.exception.PaymentInitiationException;
import com.flowwallet.payment.provider.exception.PaymentRefusedException;
import com.flowwallet.payment.provider.stripe.client.StripeClient;
import com.flowwallet.payment.provider.stripe.mapper.StripeRequestMapper;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeError;
import com.stripe.model.StripeObject;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.*;

class StripePaymentStrategyTest {
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    private final StripeRequestMapper requestMapper = mock(StripeRequestMapper.class);
    private final StripeClient stripeClient = mock(StripeClient.class);
    private final StripeWebhookParser webhookParser = mock(StripeWebhookParser.class);
    private final StripePaymentStrategy strategy = new StripePaymentStrategy(
            requestMapper,
            webhookParser,
            stripeClient
    );
    private final Logger logger = (Logger) LoggerFactory.getLogger(StripePaymentStrategy.class);
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

    @Test
    void usesTransactionReferenceAsStripeIdempotencyKey() throws Exception {
        // A retried request must reuse the client-supplied reference as Stripe's own idempotency key, or a
        // retry would create a second PaymentIntent and charge the card twice (see ADR 0005).
        PaymentRequestContext context =
                new PaymentRequestContext("ref-1", new BigDecimal("50.00"), "USD", "user-1");
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(5000L)
                .setCurrency("usd")
                .build();
        when(requestMapper.toPaymentIntentParams(context)).thenReturn(params);

        PaymentIntent intent = mock(PaymentIntent.class);
        when(intent.getId()).thenReturn("pi_123");
        when(intent.getClientSecret()).thenReturn("cs_test");
        when(stripeClient.createPaymentIntent(params, "ref-1")).thenReturn(intent);

        PaymentInitiationResult result = strategy.initiatePayment(context);

        assertThat(result.providerTransactionId()).isEqualTo("pi_123");
        assertThat(result.providerData()).containsEntry("clientSecret", "cs_test");
        verify(stripeClient).createPaymentIntent(params, "ref-1");
    }

    @Test
    void wrapsStripeExceptionAsPaymentInitiationException() throws Exception {
        // A raw StripeException must not leak past this strategy, or the caller could not handle it uniformly
        // across payment providers.
        PaymentRequestContext context =
                new PaymentRequestContext("ref-1", new BigDecimal("50.00"), "USD", "user-1");
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(5000L)
                .setCurrency("usd")
                .build();
        when(requestMapper.toPaymentIntentParams(context)).thenReturn(params);

        StripeException stripeException = new ApiConnectionException("stripe unreachable");
        when(stripeClient.createPaymentIntent(params, "ref-1")).thenThrow(stripeException);

        assertThatThrownBy(() -> strategy.initiatePayment(context))
                .isInstanceOf(PaymentInitiationException.class)
                .hasCause(stripeException);
        // Guards a second stack trace: GlobalExceptionHandler already logs this 502 at ERROR with the full
        // cause chain, so a log here at the same level would print the same trace twice for one failure.
        assertThat(logs.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));
    }

    @Test
    void aStripeRefusalOfTheRequestIsABadRequestThatSendsTheCallerToANewKey() throws Exception {
        // Guards a permanent refusal answered as a 502. The wallet tells the caller to retry a 502 with the same
        // key, every such retry reuses the reserved row and is refused again, and a corrected amount under that
        // key gets a 409, so the caller could never learn what to fix.
        PaymentRequestContext context = stripeCallFails(refusal(
                new InvalidRequestException(
                        "Amount must convert to at least 50 cents.",
                        "amount",
                        "req_1",
                        "amount_too_small",
                        400,
                        null
                ),
                "Amount must convert to at least 50 cents."
        ));

        assertThatThrownBy(() -> strategy.initiatePayment(context))
                .isInstanceOf(PaymentRefusedException.class)
                .hasMessageContaining("Amount must convert to at least 50 cents.")
                .hasMessageContaining("new Idempotency-Key");
        // GlobalExceptionHandler already logs every 4xx ApiException at WARN with its message, so this logs
        // at most at INFO, never a level that would repeat a failure the handler already reports.
        assertThat(logs.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));
    }

    @Test
    void aCardRefusalIsABadRequest() throws Exception {
        // Guards the second kind of refusal Stripe gives on create, a 402 CardException, being retried forever.
        PaymentRequestContext context = stripeCallFails(refusal(
                new CardException(
                        "Your card was declined.",
                        "req_1",
                        "card_declined",
                        null,
                        "generic_decline",
                        null,
                        402,
                        null
                ),
                "Your card was declined."
        ));

        assertThatThrownBy(() -> strategy.initiatePayment(context))
                .isInstanceOf(PaymentRefusedException.class)
                .hasMessageContaining("Your card was declined.");
    }

    static Stream<Arguments> failuresThatAreNotARefusalOfTheRequest() {
        return Stream.of(
                arguments(named("connection error", new ApiConnectionException("stripe unreachable"))),
                arguments(named("409 conflict", new ApiException("Lock timeout", "req_1", "lock_timeout", 409, null))),
                arguments(named("idempotency error", new IdempotencyException("Keys in use", "req_1", null, 400))),
                arguments(named("rate limit", new RateLimitException("Too many", null, "req_1", null, 429, null))),
                arguments(named("bad API key", new AuthenticationException("Invalid key", "req_1", null, 401))),
                arguments(named("404", new InvalidRequestException("No such", null, "req_1", null, 404, null))),
                arguments(named("500 at Stripe", new ApiException("Internal", "req_1", null, 500, null)))
        );
    }

    @ParameterizedTest(name = "a {0} stays a 502")
    @MethodSource("failuresThatAreNotARefusalOfTheRequest")
    void aFailureThatIsNotARefusalOfTheRequestStaysABadGateway(StripeException failure) throws Exception {
        // Guards the refusal branch widening: a conflict, a rate limit or an outage passes, and this service's
        // own keys or account are no fault of the caller, so each must keep the same-key retry a 502 invites.
        PaymentRequestContext context = stripeCallFails(failure);

        assertThatThrownBy(() -> strategy.initiatePayment(context))
                .isInstanceOf(PaymentInitiationException.class)
                .hasCause(failure);
    }

    @Test
    void mapsSucceededWebhookToPaymentSuccessResult() {
        // The ordinary path: a genuinely succeeded intent must map to PAYMENT_SUCCESS with its real terms.
        PaymentIntent paymentIntent = paymentIntent("succeeded", 5000L, "usd");
        when(webhookParser.parse("payload", Map.of())).thenReturn(
                new ParsedStripeEvent("evt_1", "payment_intent.succeeded", CREATED, paymentIntent)
        );

        WebhookResult result = strategy.handleWebhook("payload", Map.of());

        assertThat(result.eventType()).isEqualTo(WebhookEventType.PAYMENT_SUCCESS);
        assertThat(result.providerTransactionId()).isEqualTo("pi_1");
        assertThat(result.providerEventId()).isEqualTo("evt_1");
        assertThat(result.amount()).isEqualByComparingTo("50.00");
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.occurredAt()).isEqualTo(CREATED);
    }

    @ParameterizedTest(name = "{1} {2} is {0} in major units")
    @CsvSource({
            "50.00, 5000, usd",
            "500, 500, jpy",
            "12.34, 12340, kwd",
    })
    void convertsStripesMinorUnitsWithTheSameExponentAsTheRequest(String expected, long minor, String currency) {
        // The amount is compared with the stored transaction before a success is applied. Converting with a
        // different exponent than the request used would refuse every genuine JPY or KWD payment.
        PaymentIntent intent = paymentIntent("succeeded", minor, currency);
        when(webhookParser.parse("payload", Map.of())).thenReturn(
                new ParsedStripeEvent("evt_1", "payment_intent.succeeded", CREATED, intent)
        );

        WebhookResult result = strategy.handleWebhook("payload", Map.of());

        assertThat(result.amount()).isEqualByComparingTo(expected);
    }

    @Test
    void aSuccessEventWhoseIntentHasNotSucceededIsIgnored() {
        // A success event must describe a settled intent; one that does not must never credit a wallet.
        PaymentIntent intent = paymentIntent("requires_payment_method", 5000L, "usd");
        when(webhookParser.parse("payload", Map.of())).thenReturn(
                new ParsedStripeEvent("evt_1", "payment_intent.succeeded", CREATED, intent)
        );

        WebhookResult result = strategy.handleWebhook("payload", Map.of());

        assertThat(result.eventType()).isEqualTo(WebhookEventType.UNKNOWN);
    }

    @Test
    void aSuccessEventWithoutAnAmountCarriesNone() {
        // A null amount reaches the handler as a difference, so it can never match a stored transaction.
        PaymentIntent intent = paymentIntent("succeeded", null, null);
        when(webhookParser.parse("payload", Map.of())).thenReturn(
                new ParsedStripeEvent("evt_1", "payment_intent.succeeded", CREATED, intent)
        );

        WebhookResult result = strategy.handleWebhook("payload", Map.of());

        assertThat(result.eventType()).isEqualTo(WebhookEventType.PAYMENT_SUCCESS);
        assertThat(result.amount()).isNull();
        assertThat(result.currency()).isNull();
    }

    @Test
    void returnsUnknownForNonPaymentIntentObject() {
        // A webhook for an object type this strategy does not model (a charge, not a PaymentIntent) must be
        // acknowledged as UNKNOWN rather than fail with a cast error.
        StripeObject other = mock(StripeObject.class);
        when(webhookParser.parse("payload", Map.of())).thenReturn(
                new ParsedStripeEvent("evt_2", "charge.refunded", CREATED, other)
        );

        WebhookResult result = strategy.handleWebhook("payload", Map.of());

        assertThat(result.eventType()).isEqualTo(WebhookEventType.UNKNOWN);
    }

    @Test
    void returnsUnknownForUnhandledEventType() {
        // An event type this strategy does not act on (creation, not settlement) must not be mistaken for a
        // success or a failure.
        PaymentIntent paymentIntent = mock(PaymentIntent.class);

        when(webhookParser.parse("payload", Map.of())).thenReturn(
                new ParsedStripeEvent("evt_3", "payment_intent.created", CREATED, paymentIntent)
        );

        WebhookResult result = strategy.handleWebhook("payload", Map.of());

        assertThat(result.eventType()).isEqualTo(WebhookEventType.UNKNOWN);
    }

    private PaymentRequestContext stripeCallFails(StripeException failure) throws StripeException {
        PaymentRequestContext context =
                new PaymentRequestContext("ref-1", new BigDecimal("50.00"), "USD", "user-1");
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(5000L)
                .setCurrency("usd")
                .build();
        when(requestMapper.toPaymentIntentParams(context)).thenReturn(params);
        when(stripeClient.createPaymentIntent(params, "ref-1")).thenThrow(failure);
        return context;
    }

    /**
     * stripe-java fills the user-facing message from the parsed error body, so a test sets that body too.
     */
    private static StripeException refusal(StripeException exception, String message) {
        StripeError error = new StripeError();
        error.setMessage(message);
        exception.setStripeError(error);
        return exception;
    }

    private static PaymentIntent paymentIntent(String status, Long amount, String currency) {
        PaymentIntent paymentIntent = new PaymentIntent();
        paymentIntent.setId("pi_1");
        paymentIntent.setStatus(status);
        paymentIntent.setAmount(amount);
        paymentIntent.setCurrency(currency);
        return paymentIntent;
    }
}
