# 0022. Stripe's charge rules are checked before the reservation, and a refusal from Stripe is a 400

- Status: Accepted, extends [0005](0005-client-supplied-idempotency-keys.md), [0013](0013-deposit-initiation.md),
  [0015](0015-currency-precision-and-no-rounding.md) and [0016](0016-error-model-and-status-codes.md), extended by
  [0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md)
- Date: 2026-09-27

## Context

[0013](0013-deposit-initiation.md) reserves a transaction row before Stripe is called and runs `validateRequest`
first, so that a refusal leaves the reference free. It rejects validating after the reservation, because the client
could not then retry with a corrected amount. `StripeRequestMapper.validate` checked the sign, blank fields and the
currency grid. The deposit range in `PaymentDepositProperties` is the same figure in every currency, 1.00 to
10000.00 by default, and `@Iso4217Currency` accepts every code in the JDK's table, XAU, XTS and XXX included.

Stripe refuses a charge below its minimum for the currency (50 JPY, 175.00 HUF) and a charge in a currency it does
not support. Both refusals came after the reservation. `StripePaymentStrategy` turned every `StripeException` into
`PaymentInitiationException` (502), and the wallet answered "Payment Service is unavailable. Retry with the same
Idempotency-Key." Each retry reused the reserved row and was refused again, and a corrected amount under that key got
a 409. The caller could not learn what to fix, and the consequence in
[0015](0015-currency-precision-and-no-rounding.md) that a refused amount gets a 400 naming the limit did not hold.
The wallet also reported a 5xx that Payment Service sent itself, such as the 502 for a Stripe outage, as Payment
Service being unavailable.

## Decision

- `StripeChargeLimits` holds two lists copied from Stripe's currency page (https://docs.stripe.com/currencies,
  retrieved on 2026-09-27), and its Javadoc names that source and date. The first is the set of currencies Stripe
  charges: its 134 supported presentment currencies for card payments, plus the five three-decimal currencies that
  `StripeCurrencyRules` converts and the same page documents. The second is the minimum charge, in major units, for
  the 31 currencies Stripe lists one for.
- `StripeRequestMapper.validate` refuses a currency outside the first list and an amount below the minimum with
  `InvalidPaymentRequestException` (400). It runs before the reservation, so the key stays free. The detail names
  the figure: "Minimum deposit amount in JPY is 50, the smallest charge Stripe accepts in it". The effective floor
  is the higher of the configured deposit minimum and Stripe's.
- For a currency without a listed minimum, Stripe applies the settlement currency's minimum after conversion. That
  depends on the account and the exchange rate, so nothing is checked locally.
- A refusal that still comes back from Stripe is told apart from a failure. A 400 `InvalidRequestException` or a 402
  `CardException` judges the request's own content, and every retry of the same terms gets the same answer.
  `StripePaymentStrategy` throws `PaymentRefusedException` (400) for them, carrying Stripe's user-facing message and
  a detail that sends the caller to a new key. Every other failure stays `PaymentInitiationException` (502): a
  connection error or timeout, a 409 (stripe-java's `ApiException`), an idempotency error (`IdempotencyException`),
  a rate limit (`RateLimitException`), a 5xx, and a 401, 403 or 404, which point at this service's keys or account.
- The reserved row stays after a refusal, never initiated. A retry with the same key and terms gets the same
  refusal, and other terms under the key get the 409 of [0005](0005-client-supplied-idempotency-keys.md).
- The wallet relays Payment Service's 400s through `DepositRejectedException`. `DepositService` tells the kinds
  of 502 apart: a 5xx that Payment Service answered (`HttpServerErrorException`) gets "Payment Service could
  not start the payment. Nothing was charged. Retry with the same Idempotency-Key.", a connection failure or timeout
  (`ResourceAccessException`) gets "Payment Service is unavailable. Retry with the same Idempotency-Key.", and an
  answer the wallet cannot read gets a detail of its own.
- `WalletService.open` refuses a code that ISO 4217 gives no minor unit with `NonPaymentCurrencyException` (400),
  through `Currencies.normaliseForNewWallet`: the precious metals, the SDR and bond-market units, XSU, XUA, the
  withdrawn XFO and XFU, XTS and XXX. The JDK reports -1 default fraction digits for them. None is a means of
  payment, so no provider could fund such a wallet. The check uses ISO semantics only, so the wallet stays free of
  Stripe ([0002](0002-module-boundaries.md)). Reads and lookups keep `Currencies.normalise`, so a wallet opened
  before the rule stays reachable.

## Alternatives considered

- Only the refusal split, with no local lists. A caller who deposits 1 JPY still loses the key, and the minimum
  reaches them only in Stripe's wording.
- Only the local lists. They are copies and drift, and a refusal they miss becomes an endless same-key retry again.
- Deleting the reserved row after a refusal, so the key is free again. The delete races with a same-key retry that
  has already read the row, and the row is the only record of the terms the key was used with.
- Every Stripe 4xx as a refusal. A 409 or a 429 passes on a retry, and a 401, 403 or 404 on create is this
  service's configuration, not the caller's request.
- A deposit minimum high enough for every Stripe minimum. The range is one figure in every currency's units, so a
  floor of 175 for HUF would refuse a deposit of 100 USD.
- Chargeable currencies from `java.util.Currency` or from the SDK. The JDK knows nothing about Stripe, and
  stripe-java publishes no such list.
- Stripe's presentment list without the three-decimal currencies. Stripe documents how to charge BHD, JOD, KWD, OMR
  and TND, and an account that cannot charge them gets the refusal path.
- Refusing a wallet in a currency Stripe does not charge. That makes the wallet depend on one provider's list, which
  [0002](0002-module-boundaries.md) rules out. The ISO rule catches only codes no provider can charge.

## Consequences

- A deposit that Stripe is known to refuse gets a 400 naming the reason, and its key stays free.
- A refusal the lists do not foresee also gets a 400, but its key is spent, and the detail says so. It is the one
  refusal that writes something, an exception to "a refused request writes nothing" in
  [0005](0005-client-supplied-idempotency-keys.md).
- The lists are copies of Stripe's documentation, like `ZERO_DECIMAL` in `StripeCurrencyRules`. A change at Stripe
  is copied by hand, and until then the refusal path answers.
- A wallet can still be opened in an ISO currency Stripe does not charge, such as VES. Its deposits get a 400 before
  anything is reserved.
- The wallet's 502 detail shows whether Payment Service was unreachable or answered with a failure of its own.
