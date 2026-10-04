# 0032. Pending payments are re-checked with the provider when their webhook does not arrive

- Status: Accepted, extends [0013](0013-deposit-initiation.md) and
  [0017](0017-webhooks-verified-before-they-are-read.md)
- Date: 2026-10-04

## Context

A deposit is settled by Stripe's webhook ([0013](0013-deposit-initiation.md),
[0017](0017-webhooks-verified-before-they-are-read.md)). If the webhook never reached Payment Service, because the
endpoint was down for longer than Stripe retries, no endpoint was registered, or `stripe listen` was not running,
the card was charged and the row stayed `PENDING`. Nothing published the payment, the wallet was never credited,
and nothing noticed. A declined card leaves the same gap one step later: the row is `FAILED`, the customer pays the
same intent with another card, and if that success webhook is lost the row stays `FAILED` although the money
arrived.

## Decision

- `PendingPaymentReconciler` runs on a schedule (`payment.reconciliation.interval-ms`, 5 minutes by default). It selects
  payments that are `PENDING` or `FAILED`, have a provider id, were created between `max-age` (24 hours) and `min-age`
  (15 minutes) ago, and were not looked at within `min-age`. Payments never looked at come first, then the least
  recently looked at, at most `batch-size` (50) per run. A `FAILED` row the reconciler itself failed is skipped, because
  its intent was canceled.
- Before asking the provider, the reconciler claims the row with a conditional update of `last_reconciled_at`.
  Another instance that selected the same row gets 0 rows and leaves it. No lock or transaction is held during the
  provider call, and the update changes no `@Version`, so it never collides with a webhook.
- `PaymentProviderStrategy.checkPayment` reads the PaymentIntent. `succeeded` is a success and `canceled` a
  failure. A status in which the customer can still pay (`requires_payment_method`, `requires_confirmation`,
  `requires_action`, `processing`) leaves the row as it is; any other status is logged at WARN and left too.
- The answer goes through `PaymentTransactionHandler`, the webhook's own code: the same amount and currency check,
  the same state machine ([0009](0009-payment-event-contract.md)) and the same outbox write. Its event id is
  `reconcile:` plus the intent id, which cannot collide with Stripe's `evt_` ids in the unique `provider_event_id`.
  A reconciled success carries the time the reconciler saw it as `completedAt`, because a PaymentIntent records no
  time of success. A canceled intent carries its cancellation time and the reason "Payment canceled at the
  provider".
- The reconciler never cancels an intent. A client retrying the deposit under the same key would get the canceled
  intent's client secret back and could not pay it.
- A run stops after three failed lookups in a row. Each completion is logged at WARN, since it means a webhook was
  lost, and every outcome is counted in `payment.reconciliation.outcomes`.
- `SchedulingConfig` enables scheduling unless `payment.scheduling.enabled` is false, and the scheduler pool has
  four threads, so a slow provider call does not hold back the outbox poller.

## Alternatives considered

- Only `PENDING` rows: a success that follows a decline would stay lost.
- ShedLock or another distributed lock: a new table and dependency for what one conditional update already does.
- Scanning Stripe's event list (`GET /v1/events`): it cannot be filtered by intent, keeps 30 days, and returns events
  in the API version they were created with.
- Stripe's PaymentIntent search: eventually consistent, and rate-limited more tightly than a retrieve.
- Canceling an intent that stays open too long: it strands a client that retries under the same key.
- Relying on Stripe's webhook retries: they cover a registered endpoint that is briefly down, not a missing one or a
  `stripe listen` session that was not running.

## Consequences

- A paid deposit whose webhook is lost is credited within about `min-age` plus one interval, through the same checks
  as a webhook.
- A payment older than `max-age` is no longer asked about. A success that arrives later still needs its webhook or
  an operator.
- A success whose amount or currency differs from the row is logged at ERROR on every look until the row leaves the
  window, as it would be for a repeated webhook.
- Every look is one provider read. At the default settings that is at most 50 reads per run and one read per payment
  every 15 minutes, well inside Stripe's rate limits.
- `completedAt` on a reconciled payment is when the reconciler saw it, later than the payment itself.
- The gap of `min-age` between looks holds while the window has at most `batch-size` × `min-age` / interval open
  payments, 150 at the defaults. Beyond that a payment never looked at still comes first, and the others are looked
  at less often.
- A row a webhook already failed, whose intent was then canceled, is read again every `min-age` until it leaves the
  window, because a failure changes nothing on a `FAILED` row.
- A run stops after three failures in a row of any kind. Rows that Stripe no longer knows, such as payments made
  with another account's key, therefore also stop runs until they leave the window.
