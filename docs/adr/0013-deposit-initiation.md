# 0013. A deposit starts in Wallet Service and is reserved in Payment Service before Stripe is called

- Status: Accepted, extended by [0022](0022-stripe-charge-rules-checked-before-the-reservation.md),
  [0024](0024-deposit-initiation-settles-its-own-races.md) and [0028](0028-deposits-accept-cards-only.md)
- Date: 2026-09-05

## Context

A deposit charges a card at Stripe and credits a wallet later, when the payment event arrives
([0008](0008-transactional-outbox.md) to [0010](0010-idempotent-payment-event-consumer.md)). A payment started for a
wallet that does not exist takes money that has nowhere to go, and Payment Service knows nothing about wallets, so it
cannot check. The synchronous half crosses two network hops, wallet to Payment Service and Payment Service to Stripe,
and either can fail or hang. After any failure the client must be able to retry under the same `Idempotency-Key`
([0005](0005-client-supplied-idempotency-keys.md)) without Stripe creating a second intent. The deposit range is a
commercial limit that a risk team, a regulator or a sandbox changes, so it must change without a rebuild and be
enforced in one place.

## Decision

The gateway routes `/api/wallets/**` to Wallet Service and only `/api/payments/webhooks/**` to Payment Service, which
Stripe posts to. `/api/payments/intent` has no public route, so a payment begins only through
`POST /api/wallets/{currency}/deposits`.

`DepositService` reads the caller's wallet with the non-locking `findByUserIdAndCurrency` and throws
`WalletNotFoundException` (404) before any outbound call. It calls Payment Service at its own address
(`wallet.payment.base-url`), not through the gateway, through `PaymentIntentClient`: a declarative `@HttpExchange`
interface over `RestClient` on the JDK `HttpClient`. `PaymentClientConfig` sets the connect timeout on the
`HttpClient` and the read timeout on `JdkClientHttpRequestFactory` (defaults `2s` and `10s`). The caller's id is an
explicit `@RequestHeader(HttpHeaders.USER_ID)` parameter. The wallet stores nothing about a deposit. The Payment
Service row that binds the reference to its terms is the only record, and the client secret, a bearer credential, is
passed to the caller and never stored.

The provider comes from `wallet.payment.provider-name` (`WALLET_PAYMENT_PROVIDER`, default `STRIPE`) because
`PaymentProvider` has one constant. `providerData` is an open map that each provider strategy fills key by key (for
Stripe, only `clientSecret`), and the wallet copies it into `DepositResponse` untouched.

Payment Service owns the deposit range and is the only service that enforces it. `PaymentDepositProperties` binds
`payment.deposit.min-amount` and `max-amount` (defaults `1.00` and `10000.00`), and startup fails unless both are
positive, the range is ordered and the maximum fits `NUMERIC(19,4)`. `@DepositAmount` on
`CreatePaymentIntentRequest.amount` checks the range through `DepositAmountValidator`, which has the properties
injected, reads them at validation time and names the bound that was crossed. The wallet's `DepositRequest` checks
only `@NotNull` and `@Positive`. On a 400 from Payment Service, `DepositRejectedException` relays the problem's
`errors` joined with `; `, then `detail` if there are no errors, then a fixed fallback.

`PaymentService.initiatePayment` is not transactional ([0006](0006-short-transactions-across-bean-boundaries.md)) and
runs in this order:

1. It looks up the reference for this owner. An initiated row is returned as it is; conflicts are refused as 0005
   describes.
2. `PaymentProviderFactory` resolves the strategy and `validateRequest` vets the request (for Stripe,
   `StripeRequestMapper.validate`, including the currency grid of [0015](0015-currency-precision-and-no-rounding.md)).
   Either can refuse before any row is written.
3. `PaymentTransactionStore.reserve` writes a `PENDING` row in its own short transaction
   ([0007](0007-unique-constraints-decide.md)). A row from step 1 that was reserved but never initiated
   (`isInitiated()` false, `providerTransactionId` null) is reused instead.
4. Stripe is called outside any transaction, with the reference as its idempotency key.
5. `recordInitiation` stores the provider's id and `providerData` on the row.

Both endpoints answer 200. The wallet creates nothing, and the client finishes the payment with the provider's SDK.

## Alternatives considered

- A public route to `/api/payments/intent`, or any entry point other than the wallet: a card could be charged with
  no wallet to credit.
- Calling Payment Service through the gateway: internal traffic has no reason to leave by the front door, and the
  call would depend on the edge being up.
- Feign or Spring Cloud OpenFeign: the dependency was removed as unused, and the web starter already has what a
  declarative client needs.
- Default client timeouts: a wedged Payment Service accepts the connection and never answers, so without a read
  timeout it holds a wallet request thread indefinitely.
- An interceptor over a request-scoped identity holder: it sends an empty header, with no error, once the call
  leaves the request thread.
- A wallet-side copy of the reference and its terms: a way for the two services to disagree that adds no
  guarantee. Caching the client secret in `wallet_db` would put a bearer credential with its own lifetime in a
  second database.
- A `providerName` request field: with one legal value, the client can only get it wrong. It becomes a request
  field, as an additive change, when a second provider exists.
- Typed per-provider response fields, or a flattened `clientSecret` field: the first grows a field per provider
  that is null for all the others, the second puts one provider's vocabulary in the wallet's public contract.
  Copying the provider's response object into `providerData` wholesale would leak fields by accident.
- The wallet re-declaring the bounds: a second source of truth that drifts. Hard-coded constants need a rebuild.
  `@DecimalMin`/`@DecimalMax` cannot read configuration, because annotation attributes are compile-time constants.
- Relaying only `detail`: for a bean-validation failure it is Spring's generic "Invalid request content.", and the
  useful sentence ("Maximum deposit amount is 10000.00") is in `errors`, so the caller gets an accurate 400 that
  tells them nothing. Wallet-authored wording fails too, because the wallet does not know the bounds.
- Validating after the row is reserved: a refusal leaves the reference taken, and the client cannot retry with a
  corrected amount.
- Writing a second row on retry: the reference is already taken, and the response carries a null client secret the
  client cannot use.
- 201 or 202: there is no resource for a `Location` to point at, and 202 promises that the server finishes the
  work, so a client written the usual way polls for a balance that never moves.

## Consequences

- A missing wallet costs a 404 before any outbound call, with the card untouched.
- Payment Service can trust `X-User-Id` only because it has no public route except webhooks
  ([0003](0003-caller-identity-and-trust-boundary.md)). Any new public route to it breaks that.
- A Payment Service failure (5xx, connection refused, either timeout) reaches the caller as a 502 with nothing
  charged, and a retry with the same key is safe ([0016](0016-error-model-and-status-codes.md)).
- If Stripe fails or the process stops between reserve and record, the row stays reserved. The retry reuses it, and
  Stripe's idempotency key returns the same intent.
- The deposit range changes by configuring Payment Service alone, and the wallet relays the new wording without a
  release. A misconfigured range stops startup instead of running a healthy-looking service that refuses every
  payment.
- A deposit that has started but not completed exists only in `payment_db`. The wallet learns of it only from the
  payment event.
- A second provider needs a `PaymentProvider` constant, a strategy with its own `validateRequest` and `providerData`
  keys, and a provider field on the wallet's request. No response type changes.
