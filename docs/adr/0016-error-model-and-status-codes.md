# 0016. Errors are RFC 9457 problems whose status tells the client what to do

- Status: Accepted, extended by [0017](0017-webhooks-verified-before-they-are-read.md),
  [0022](0022-stripe-charge-rules-checked-before-the-reservation.md),
  [0024](0024-deposit-initiation-settles-its-own-races.md), [0025](0025-unreachable-database-answers-503.md),
  [0026](0026-problem-details-never-quote-rejected-input.md) and
  [0033](0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md)
- Date: 2026-07-25

## Context

Wallet Service and Payment Service refuse requests for the same kinds of reason: a malformed request, a missing
identity, a key already used, a provider that failed. The callers are clients that retry after a lost response, the
wallet calling Payment Service, and Stripe, which redelivers a webhook until it gets a 2xx. Each of them decides from
the response alone whether to fix the request, retry it unchanged, retry with a new key or stop. A problem `detail`
is written for a person, so a client needs something else to branch on.

## Decision

Domain code throws a subclass of `ApiException` from `flow-wallet-platform`, and the subclass's constructor fixes the
`HttpStatus`. Controllers never choose an error status. The exception's message becomes the problem `detail`, so it
is written to be shown to the caller.

`GlobalExceptionHandler` renders every error as `application/problem+json`. `WebExceptionHandlerAutoConfiguration`
registers it in servlet services only, under `@ConditionalOnMissingBean`, so a service can replace it; the reactive
gateway keeps Spring Boot's default format ([0002](0002-module-boundaries.md)). The handler extends
`ResponseEntityExceptionHandler`, so standard Spring MVC exceptions (unsupported method, unreadable body, missing
header) arrive as typed `ProblemDetail`s. Every problem, the framework's included, gets a `timestamp` and the request
path as `instance`. `handleMethodArgumentNotValid` and `handleConstraintViolation` list failing body fields and
failing query and header parameters in `errors`. `handleApiException` logs each detail, at WARN for a 4xx and at
ERROR with the stack trace for a 5xx. `handleUnexpected` logs anything else and answers 500 with the detail
"Internal server error".

Nothing sets the problem `type`, so responses carry none (RFC 9457 reads that as `about:blank`) and promise no
taxonomy. The status is all a client can branch on, so each status stands for one remedy, and causes that share a
remedy share a status, with the detail naming the cause:

- `400`, fix the request: validation failures, an unknown provider, a non-ISO currency, a missing or malformed
  `Idempotency-Key`, an amount Payment Service refuses (`DepositRejectedException`), and a transfer to oneself
  (`SelfTransferException`). No wallet's state decides any of them.
- `401`, no usable identity: one `MissingUserIdException` for every cause
  ([0003](0003-caller-identity-and-trust-boundary.md)).
- `404`, the caller's own wallet does not exist: `WalletNotFoundException`, never `403` and never about a recipient
  ([0004](0004-wallet-addressed-by-owner-and-currency.md)).
- `406`, accept JSON: a transfer, deposit or wallet opening whose `Accept` header rules JSON out, refused before the
  handler method runs
  ([0014](0014-transfers-in-one-local-transaction.md)).
- `409`, already taken: a key or reference used for something else (`ConflictingDepositException`,
  `ConflictingTransferException`, `DuplicateTransactionReferenceException`), whose remedy is a new key
  ([0005](0005-client-supplied-idempotency-keys.md)), or a wallet that exists (`WalletAlreadyExistsException`).
- `422`, the request is well-formed and its key unspent but a wallet's state refuses it: `InsufficientFundsException`
  (lower the amount or top up) or `RecipientHasNoWalletException` (pick another recipient).
- `502`, a failure upstream of the wallet: `PaymentInitiationException` for the provider, and
  `PaymentUnavailableException` when Payment Service is unreachable, times out, answers 5xx, or answers a 4xx other
  than 400 and 409. That last case is the wallet's own fault, so `DepositService` logs the body instead of passing
  the status on. Nothing was charged, and a retry with the same key is safe.
- `503`, contention inside the wallet: `TransferBusyException` for a lost lock or version check. Nothing moved, and
  a retry with the same key is safe ([0011](0011-wallet-row-locking.md)).
- `500`, a defect: anything unexpected, a transfer that broke a CHECK or overflowed a column
  ([0014](0014-transfers-in-one-local-transaction.md)), or a webhook payload that cannot be processed.

A webhook's status reports delivery, not the business outcome. It is `200` when the event was applied, was already
processed, is of another type, or concerns an intent this service never created (from `stripe trigger` or the
dashboard): a retry could change none of these, and `PaymentTransactionHandler` logs the last one. A missing or
invalid signature (`InvalidWebhookSignatureException`) or an unknown provider (`UnsupportedPaymentProviderException`)
gets `400`, because a real delivery is always signed. `WebhookProcessingException` gives `500` for an event this side
cannot process, such as a validly signed event whose data object fails to deserialize after an SDK version mismatch.
The fault is this service's, and a redelivery can succeed once it is fixed.

`UnknownWalletException` and `UnreadablePaymentEventException` extend `RuntimeException`, not `ApiException`,
because a Kafka listener has no HTTP response and a status on them would mislead whoever reads them next.
`InsufficientFundsException` is an `ApiException` because `Wallet.debit` is reached only from `TransferHandler`, so
every debit in scope starts from an HTTP request that waits for the answer.

Since every `ApiException` detail goes to the log as well as to the caller, no detail or log line carries a balance
figure or a rejected header value. `InsufficientFundsException` names only the currency, and `CurrentUserIdResolver`
and the `Idempotency-Key` constraints refuse a value without quoting it.

## Alternatives considered

- Status codes set in controllers. The code that detects a refusal would hand its reason back for the controller to
  map again, where the exception maps each refusal once.
- Hand-written handlers for the standard Spring MVC exceptions. `ResponseEntityExceptionHandler` already types them.
- The internal exception message as a 500's detail. It exposes internals, and the log already has the cause.
- A problem-type taxonomy, or distinct statuses for causes that share one remedy. The client can do nothing
  different with the distinction, and the detail already names the cause.
- 500 or 503 for a provider failure. The wallet is working, and the failure is upstream of it.
- Passing Payment Service's unexpected 4xx through. It blames the caller for the wallet's own request.
- 409 for insufficient funds, or 404 for a recipient without a wallet. On a transfer, 409 means "use a new key" and
  404 means the caller's own wallet is missing.
- 422 for a transfer to oneself. The request alone shows it can never be valid.
- 502 for lock contention, or 500 for a deadlock or version conflict. 502 names an upstream failure, and 500 hides
  that a retry with the same key is safe.
- 403 for another user's wallet, or a separate 401 for each cause. See 0004 and 0003.
- 404 for a webhook about an unknown intent. Stripe reads it as a missing endpoint, and a retry changes nothing.
- 5xx for a bad webhook signature. The fault is the sender's, and a 5xx invites a retry of a request that was never
  authenticated.
- 4xx for a validly signed payload that fails to deserialize. The sender did nothing wrong; the fault is on this
  side, and a redelivery can succeed once it is fixed.
- Event-path exceptions extending `ApiException`, or `InsufficientFundsException` reused for a debit driven by an
  event, such as a chargeback. A listener has no response to carry a status, and such a debit needs a refusal of its
  own.
- The balance and the shortfall in the insufficient-funds detail. The handler logs every 4xx detail, and the caller
  can read its own balance from the wallet endpoint.

## Consequences

- A new refusal takes the status of the remedy it shares. A refusal whose remedy none of these statuses stands for
  changes this decision.
- A controller that validates parameters carries `@Validated`. Without it, Spring MVC's built-in method validation
  raises `HandlerMethodValidationException`, and the problem comes back without `errors`.
- `StripeWebhookParser` checks the signature before it parses the body, and any exception from the check, a
  malformed `Stripe-Signature` header included, becomes `InvalidWebhookSignatureException`. A body that is not a
  readable event therefore gets `400` unless its signature is valid, and `500` only when it is
  ([0017](0017-webhooks-verified-before-they-are-read.md)).
