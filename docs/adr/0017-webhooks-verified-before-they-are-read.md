# 0017. Webhooks are bounded and verified before they are read, and refused without a signing secret

- Status: Accepted, extends [0016](0016-error-model-and-status-codes.md)
- Date: 2026-09-27

## Context

`POST /api/payments/webhooks/{provider}` is the only route the gateway opens to Payment Service, and port 8082
answers without the gateway. A verified `payment_intent.succeeded` marks the transaction `SUCCESS` and emits a
`PaymentCompletedEvent`, which credits a wallet. The webhook signature is therefore all that stands between an
unauthenticated request and a credit.

stripe-java's `Webhook` computes its HMAC-SHA256 with whatever string it is given as the secret. The secret used to
default to `whsec_dummy` in `application.yml` and in `StripeProperties`, and `.env.example` shipped
`whsec_your_secret_here`, which the quickstart said to keep until `stripe listen` printed a real one. With either
value, anyone could sign a `payment_intent.succeeded` for the `pi_` id inside their own deposit's client secret.
The handler read only the intent id and the event id, so the wallet was credited without a payment.

`Webhook.constructEvent` deserializes the body into a Gson tree before it calls `Webhook.Signature.verifyHeader`, and
`@RequestBody String` reads a body of any size, so an unauthenticated caller could make the service buffer and parse
anything. The SDK's header parsing throws `NumberFormatException` or `ArrayIndexOutOfBoundsException` for a header
such as `t=abc` or a bare `t`, and those were answered with 500 and logged at ERROR with a stack trace.

## Decision

Webhooks fail closed. `stripe.webhook.secret` has no default. `StripeProperties.Webhook.hasSigningSecret` accepts only
a value that starts with `whsec_`, has more after the prefix and is not a known placeholder (`whsec_dummy`,
`whsec_your_secret_here`). Without such a value Payment Service still starts, `StripeConfig` logs one WARN, and
`StripeWebhookParser` refuses every delivery with `InvalidWebhookSignatureException` (400) without computing a
signature. `stripe.webhook.tolerance-seconds` must be positive, because stripe-java skips the timestamp check for zero
or less; `StripeProperties` is `@Validated` and fails startup otherwise.

The body is bounded before anything reads it. `WebhookController` reads it through `WebhookPayloadReader` instead of
`@RequestBody`. A `Content-Length` above `payment.webhook.max-payload-size` (256KB by default) is refused unread, and a
body that declares no length (chunked) is read to at most one byte past the limit. Both cases throw
`WebhookPayloadTooLargeException`, answered with `413 Content Too Large`. This adds a status to the list in 0016: its
remedy, sending a smaller body, is one that no other status stands for. Payment Service sets
`spring.servlet.multipart.enabled` to `false`: with a multipart resolver registered, `DispatcherServlet` reads and
spools a multipart body of up to 10MB before any controller runs.

The signature is checked before the body is parsed. `StripeClient.verifyWebhookSignature` runs
`Webhook.Signature.verifyHeader` over the raw body, and only then does `StripeClient.constructVerifiedEvent` call
`Webhook.constructEvent`, which verifies a second time. `StripeWebhookParser` maps every exception from verification,
the SDK's runtime exceptions for a malformed header included, to `InvalidWebhookSignatureException`, which
`GlobalExceptionHandler` logs at WARN without a stack trace. A body that passes verification and still cannot be read
is a `WebhookProcessingException` (500), as 0016 prescribes.

A verified success is compared with the transaction before it is applied. `StripePaymentStrategy` ignores a
`payment_intent.succeeded` whose PaymentIntent status is not `succeeded`, logging it at ERROR, and otherwise reports the
intent's amount in major units, converted with `StripeCurrencyRules`' transmit exponent (the inverse of the request's
conversion), and its currency. `PaymentTransactionHandler.handleSuccess` compares them with the row through
`PaymentTransaction.differencesFromConfirmed`: the amount by `compareTo`, the currency ignoring case, and a missing
value counts as a difference. On any difference the row stays unchanged, the handler logs at ERROR which terms differ,
and the webhook gets 200: a webhook's status reports delivery (0016), and a redelivery of the same event cannot change
the outcome.

## Alternatives considered

- A dummy default secret so the service starts without configuration. Any published value is a signing key.
- Failing startup without a secret. The quickstart starts Payment Service before `stripe listen` has printed the
  secret, and deposits can be started before webhooks work.
- Warning about a placeholder but still verifying with it. The warning does not stop a forged webhook.
- `Webhook.constructEvent` as the only call. It parses unauthenticated input first and lets the header-parsing
  exceptions through.
- A size limit only in Tomcat or in the gateway. Tomcat's `maxPostSize` applies to form bodies only, and port 8082
  is reachable without the gateway. A gateway `RequestSize` filter checks only `Content-Length`.
- 400 for an oversized body. The body may be well formed and signed; only its size is refused.
- A 4xx or 5xx for a verified event whose terms differ from the row. Stripe would keep redelivering an event whose
  content cannot change, and the difference needs an operator, whom the ERROR log reaches.
- Applying a verified event without comparing it. The signature proves who sent the event, not that it describes
  this payment: an event from another integration on the same account, or one signed with a leaked secret, would
  credit whatever it states.

## Consequences

- A fresh checkout refuses webhooks until `STRIPE_WEBHOOK_SECRET` holds the value from `stripe listen` or the
  dashboard. Deposits can still be started, and they stay `PENDING`.
- Changing the secret takes a restart. A placeholder added to `.env.example` must also be added to
  `StripeProperties.Webhook`'s placeholder list.
- A genuine event larger than the limit is refused with 413 until the limit is raised. A PaymentIntent event is
  usually a few kilobytes.
- An accepted webhook's signature is computed twice.
- An ERROR from `PaymentTransactionHandler` about differing terms means a verified event disagreed with a stored
  payment. The row keeps its status until an operator investigates.
