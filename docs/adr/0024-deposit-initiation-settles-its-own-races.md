# 0024. Deposit initiation settles its own races, and its Stripe call ends before the wallet stops waiting

- Status: Accepted, extends [0013](0013-deposit-initiation.md) and [0016](0016-error-model-and-status-codes.md),
  supersedes the concurrent-reservation consequence of [0005](0005-client-supplied-idempotency-keys.md) and the handling
  of `PaymentTransactionStore.reserve` in [0007](0007-unique-constraints-decide.md), extended by
  [0033](0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md)
- Date: 2026-09-27

## Context

[0013](0013-deposit-initiation.md) reserves a `payment_transactions` row, calls Stripe outside any transaction and
records the answer. Two requests with the same key and terms can run at once, for instance when a client retries
after its own timeout while the first request is still running.

`PaymentTransactionStore.reserve` treated every integrity violation as the concurrent-creation race and threw
`DuplicateTransactionReferenceException` (409), shape 1 of [0007](0007-unique-constraints-decide.md). A request that
lost the reservation to its own twin was told to retry with a new key, which starts a second payment, although
[0005](0005-client-supplied-idempotency-keys.md) answers a same-key, same-terms request from the row that holds the
key. Two same-key requests that both found a row reserved but never initiated both called Stripe, which returns the
same intent for one idempotency key, and both recorded it. The second recording failed the `@Version` check with a
500 after the intent existed.

Stripe calls ran with stripe-java's defaults: 30 seconds to connect, 80 to read and two network retries, which cover
timeouts and 409s. Wallet Service gives up after `WALLET_PAYMENT_READ_TIMEOUT` (10 seconds) and answers 502. The
Stripe call kept a Payment Service thread for minutes after that answer, and could still create the intent long
after the caller had been told to retry.

## Decision

- `PaymentTransactionStore.reserve` catches nothing. The violation rolls the reservation back and reaches
  `PaymentService`, which is not transactional. It reads the row under the reference with `findOwnedBy`, in a fresh
  transaction, and judges it like any retry through the same `replayOf` step: another owner, other terms or a
  settled payment get the 409 of [0005](0005-client-supplied-idempotency-keys.md), and an initiated row is
  replayed with 200. A row that is reserved but not initiated yet gets `PaymentInProgressException` (503), with
  advice to retry under the same key. When no row holds the reference, another constraint fired, and the violation
  is rethrown as a 500. This is shape 2 of [0007](0007-unique-constraints-decide.md).
- `PaymentTransactionStore.recordInitiation` loads the row through `PaymentTransactionRepository.lockById`, a
  `PESSIMISTIC_WRITE` lock inside its short transaction. A concurrent recording waits for the first to commit and
  then finds the row initiated. A row already initiated with the same provider id is returned as it stands; one with
  a different provider id throws `IllegalStateException`, since the idempotency key makes a second intent
  impossible.
- `StripeProperties.Api` binds `stripe.api.connect-timeout`, `read-timeout` and `max-network-retries` (defaults
  `2s`, `6s` and `0`). Startup fails on a timeout below 1 ms or above what fits an `int` of milliseconds, and on a
  negative retry count. `StripeClient.requestOptions` sets all three on every call, so none of stripe-java's global
  defaults applies. `(1 + retries) * (connect + read)`, plus about half a second of backoff per retry, stays below
  `wallet.payment.read-timeout`: 8 seconds against 10 with the defaults.
- The wallet answers Payment Service's 503 like any 5xx it answered itself: a 502 that tells the caller to retry with
  the same key ([0022](0022-stripe-charge-rules-checked-before-the-reservation.md)).

## Alternatives considered

- A 409 for the request that lost the reservation. It sends the client to a new key for a payment that is already
  under way, and the new key starts a second one.
- Letting the loser call Stripe as well. Two calls under one idempotency key run at once, Stripe answers the second
  with a 409, and the two recordings still race.
- Making the loser wait for the winner to record Stripe's answer. The wait holds a request thread for as long as the
  winner's Stripe call takes, and the client's own retry reaches the same answer without holding anything.
- Keeping shape 1 in `reserve` and translating to a store-specific exception. The explaining read still has to run
  outside the aborted transaction, so the caller does the work either way, and the violation itself is needed to
  rethrow one that no row explains.
- Catching the optimistic-lock failure in `recordInitiation` and reading the row again. It works, but a row lock in
  the short transaction never fails and needs no second read.
- Setting `Stripe.setConnectTimeout`, `setReadTimeout` and `setMaxNetworkRetries` globally. They are static state
  shared by every caller in the JVM, and per-request options keep the values beside the call and testable. A
  `com.stripe.StripeClient` instance would do the same but collides with this project's own `StripeClient`.
- One network retry by default. With a 2 second connect and 6 second read timeout the worst case is over 16 seconds,
  past the wallet's 10.

## Consequences

- A client's concurrent duplicate of its own deposit gets the same intent, or a 502 at the wallet that it retries
  under the same key. Only another owner or other terms get the 409 of
  [0005](0005-client-supplied-idempotency-keys.md), whichever request reserved the row.
- `reserve` does not rely on every other constraint being checked first. A constraint added without a pre-check
  gives a 500 with its cause in the log instead of a misleading 409.
- By default a Stripe call ends before the wallet stops waiting. Nothing checks the budget across the two services,
  so a change to `STRIPE_API_*` or `WALLET_PAYMENT_READ_TIMEOUT` has to keep it by hand.
- A Stripe call that times out may still have created the intent at Stripe. The row stays reserved, and the
  client's same-key retry gets that intent through Stripe's idempotency key ([0013](0013-deposit-initiation.md)).
- With no network retry, a brief Stripe failure reaches the caller as a 502, and the client's retry takes the place
  of stripe-java's.
