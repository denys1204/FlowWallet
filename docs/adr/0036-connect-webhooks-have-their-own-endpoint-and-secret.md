# 0036. Connect webhooks have their own endpoint and secret, and apply the payout's current status

- Status: Accepted, extends [0017](0017-webhooks-verified-before-they-are-read.md)
- Date: 2026-10-10

## Context

A payout's outcome and changes to a bank account happen on the connected account, not on the platform
([0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md)). Stripe sends `payout.*`, `account.updated` and
`account.external_account.updated` to endpoints registered for connected accounts (`connect=true`), with a top-level
`account` field that names the account. `transfer.*` goes to the platform's endpoints. Each registered endpoint has
its own signing secret.

The test-mode spike found that `stripe listen` signs both `--forward-to` and `--forward-connect-to` with one session
secret, and that it delivers events in the account's default API version, `2025-12-15.clover`, while stripe-java is
pinned to `2026-06-24.dahlia`. For a payout that failed, Stripe sent `payout.paid` before `payout.failed`, and
`payout.created` after both. Stripe documents that a payout can show as paid and fail several business days later.

`WebhookController` maps `/api/payments/webhooks/{provider}`, and `StripeWebhookParser.parse(payload, headers)`
verifies against the one secret in `stripe.webhook.secret` ([0017](0017-webhooks-verified-before-they-are-read.md)).
`ParsedStripeEvent` carries no account, and the parser falls back to `deserializeUnsafe` when an event's API version
differs from the SDK's.

## Decision

`ConnectWebhookController` handles `POST /api/payments/webhooks/stripe/connect`. The path has two segments after
`webhooks`, so `/{provider}` does not match it, and the gateway's `/api/payments/webhooks/**` route covers it.

`stripe.connect-webhook.secret` (`STRIPE_CONNECT_WEBHOOK_SECRET`) has no default and follows the rules of
`hasSigningSecret`: without a `whsec_` value that is not a known placeholder, every Connect delivery is refused with
400. Under `stripe listen` it holds the same value as `STRIPE_WEBHOOK_SECRET`.

The order of [0017](0017-webhooks-verified-before-they-are-read.md) holds. `WebhookPayloadReader` applies the size cap
first. `StripeWebhookParser.parse(payload, headers, secret)`, `StripeClient.verifyWebhookSignature` and
`constructVerifiedEvent` take the secret as a parameter, so each endpoint verifies with its own secret before anything
parses the body. `ParsedStripeEvent` carries `account`.

For `payout.paid`, `payout.failed` and `payout.canceled`, the handler finds the payout by `stripe_payout_id`, or by its
`flowwallet_withdrawal` metadata when the webhook arrives before the executor recorded the id. The event's `account`
must equal the row's `stripe_account_id`, and the payout's amount and currency must match the row. On any difference the
handler logs at ERROR, answers 200 and changes nothing. The event's type is not trusted. The handler reads the payout
with `Payout.retrieve`, outside any transaction, and applies its current status through the guarded transitions of
[0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md). A row found by metadata while still `TRANSFERRED`
adopts the payout first, as the executor's lookup does: it records `stripe_payout_id` and moves to `PAYOUT_CREATED` with
no event, and the status then applies in the same transaction, so a `failed` or `canceled` payout takes the row on to
`REVERSING`. The executor's own record of that payout then changes nothing. The statuses apply as follows:

- `paid` moves `PAYOUT_CREATED` to `PAID`;
- `failed` or `canceled` moves `PAYOUT_CREATED` or `PAID` to `REVERSING` with the reason `PAYOUT_FAILED`, however long
  ago a `PAID` payout was paid. `failed` also sets the bank account to `NOT_SET` when the payout's external account is
  the stored one; `canceled` does not, because a cancellation says nothing about the bank account;
- `pending` and `in_transit` move nothing.

A status that moves nothing, such as `paid` on a row in `REVERSING` or `RETURNED`, is logged at WARN, and a redelivery
moves nothing either. A payout this service does not know gets 200 and a WARN.

`account.updated` and `account.external_account.updated` about a stored account make the handler retrieve that account
through the pinned SDK and set the bank account's state from it by the rule of
[0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md): `READY` when `payouts_enabled` holds and the default
external account for the currency is not `errored`, `NOT_SET` otherwise. The event's data is not read. `transfer.*` is
not handled, because a transfer and a reversal answer synchronously.

A Connect webhook's status reports delivery, as in [0016](0016-error-model-and-status-codes.md): 200 for an event
applied, repeated, of another type, about an unknown payout or with differing terms; 400 for a missing or invalid
signature; 413 for an oversized body; 500 for an event this side cannot process, a failed `Payout.retrieve` included,
so that Stripe delivers it again.

`PayoutReconciler` asks Stripe about `PAYOUT_CREATED` rows older than a configured age, and about `PAID` rows for a
configured period after `paid_at`, 10 days by default, at configured intervals. `ACCEPTED`, `TRANSFERRED` and
`REVERSING` rows belong to the executor. Like `PendingPaymentReconciler`, it claims a row with a conditional update of
`last_reconciled_at` before calling Stripe, as [0032](0032-pending-payments-are-rechecked-with-the-provider.md) does,
and holds nothing open during the call. It pages its work under the sandbox's 25 requests per second, and it applies the
payout's status through the same code as the webhook. Without a Connect secret, it alone settles payouts.

The handler reads every object it acts on, the payout or the account, through the pinned SDK, so only the envelope
(id, type, account and the object's id) depends on the event's API version. The parser keeps accepting an event in
another version through `deserializeUnsafe`, as it does for deposits, and the sandbox's default API version stays as it
is.

A registered endpoint needs `connect=true`, the SDK's API version, and an ingress in front of the gateway, which
listens on loopback ([0031](0031-callers-are-authenticated-in-front-of-the-gateway.md)). A local run forwards both
scopes through one `stripe listen`:

```bash
stripe listen --forward-to localhost:8080/api/payments/webhooks/stripe \
  --forward-connect-to localhost:8080/api/payments/webhooks/stripe/connect
```

## Alternatives considered

- One endpoint for both scopes, told apart by the event's `account` field. The secret has to be chosen before the
  body is read, so the path chooses it. A registered endpoint receives one scope and has its own secret; only
  `stripe listen` signs both with one.
- One setting for both secrets. A registered deployment has two values.
- Applying the event's type. A failing payout sent `payout.paid` first, so a Paid event would go out for a payout that
  then failed, and events arrive out of order.
- The status in the event's data object. The spike's snapshots happened to show the final status, but nothing
  promises it, and the object arrives in the event's API version.
- An age limit on `PAID` to `REVERSING` for webhooks. A payout can fail days after it shows paid, so a real failure
  would leave the user without the money and the wallet showing `PAID`.
- Moving the sandbox's default API version to `2026-06-24.dahlia` in the Dashboard. It changes the version of every
  event the account sends, the deposits' included, while the handler reads nothing version-specific beyond the
  envelope.
- Reading `payouts_enabled` or the external account's status from an account event's data. The data arrives in the
  event's API version, and an event about an external account the user has since replaced would describe the wrong one.
- Polling only. Every outcome shows up an interval late, and every open payout costs a read per interval.
- Handling `transfer.*`. Transfers and reversals answer synchronously.

## Consequences

- Payment Service has two signing secrets. Without the Connect secret, Connect deliveries get 400 and payouts settle
  at the reconciler's pace. Changing either secret takes a restart.
- Each payout or account webhook costs one Stripe read.
- The order in which payout and account events arrive does not matter.
- A failure that arrives after the polling period is missed if its webhook is lost as well.
- An ERROR about a differing account, amount or currency means a verified event disagreed with a stored payout. The
  row keeps its state until an operator looks.
- An account event sets the state from the account as Stripe holds it when the handler reads it, so an event about an
  external account the user has since replaced cannot mark the current one `NOT_SET`.
