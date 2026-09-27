# FlowWallet HTTP API

This file lists the HTTP endpoints FlowWallet exposes: the wallet endpoints behind the gateway, the Stripe
webhook, and the internal Payment Service call that starts a deposit. It also covers the error format the
wallet and payment services share. For how the services fit together, see
[ARCHITECTURE.md](../ARCHITECTURE.md); for the database tables behind these endpoints, see
[the data model](data-model.md); for running the services locally, see [development](development.md).

## API reference

The base URL through the gateway is `http://localhost:8080`. Every wallet endpoint needs `X-User-Id`.

### Wallet Service

A wallet is addressed by its ISO 4217 currency and never by an id. A user holds at most one wallet per
currency, and `X-User-Id` already names the owner, so the pair identifies the wallet exactly. It also means
"not yours" and "does not exist" give the same result
([ADR 0004](adr/0004-wallet-addressed-by-owner-and-currency.md)). Currency is case-insensitive in the
path and in the body.

| Method & path | Request | Response |
|---------------|---------|----------|
| `GET /api/wallets` | (none) | `200` `[{balance, currency, createdAt, updatedAt}]`, ordered by currency; an empty list if none, never `404` |
| `POST /api/wallets` | `{"currency": "USD"}` | `201` the wallet; `400` not an ISO 4217 code, or a code with no minor unit (XAU, XTS, XXX and the like); `409` a wallet in that currency already exists |
| `GET /api/wallets/{currency}` | (none) | `200` the wallet; `400` invalid code; `404` the caller holds no such wallet (never `403`) |
| `GET /api/wallets/{currency}/history?before={entryNo}&limit={n}` | (none) | `200` `{items, nextBefore}`, newest first |
| `POST /api/wallets/{currency}/deposits` | `{"amount": 50.00}` + `Idempotency-Key` header | `200` `{reference, provider, providerData}` |
| `POST /api/wallets/{currency}/transfers` | `{"to": "<user id>", "amount": 25.00}` + `Idempotency-Key` header | `200` `{reference, to, amount, currency, balanceAfter}` |

Every movement on a wallet has an `entryNo`: the wallet's movements are numbered 1, 2, 3 in the order they
committed, with no gaps. History is sorted by it, newest first, so the first page's first `balanceAfter` is
the wallet's balance. History uses this number as a cursor instead of an offset. The ledger only grows at its
newest end, so a credit landing between two page reads would shift every offset but leaves every entry number
where it was. Leave `before` out for the first page, then pass the previous `nextBefore`, the
`entryNo` of that page's oldest item, until it is `null`. Paging this way never repeats or skips a movement.
`limit` is 1 to 100, default 20. An item looks like `{entryNo, transactionReference, type, counterpartyUserId,
amount, balanceBefore, balanceAfter, createdAt}`, with no row id
([ADR 0021](adr/0021-per-wallet-ledger-entry-numbers.md)). `amount` is positive on every item, and `type`
says which way the money went.
`counterpartyUserId` is the other user of a transfer (the recipient on `TRANSFER_OUT`, the sender on
`TRANSFER_IN`) and `null` for a deposit. Both legs of a transfer carry the sender's key as
`transactionReference`.

A deposit returns `200` rather than `201` or `202`. Nothing gets created on the wallet's side, and the client
finishes the work itself by confirming the payment with Stripe using `providerData.clientSecret`.

- `Idempotency-Key` is required and can be a UUID of any version, in either case. It is lower-cased and
  becomes the payment's transaction reference, so the same string runs from the client through Payment
  Service into Stripe, onto the event and into the ledger. The server never generates one. If it did, a lost
  response would get a fresh key on retry, and the customer would be charged twice. The key rules shared by
  deposits and transfers are in [ADR 0005](adr/0005-client-supplied-idempotency-keys.md).
- Repeating a request with the same key and the same amount returns a byte-identical body. Reusing a key
  with a different amount, or a key whose deposit already completed, gets a `409`.
- The provider comes from configuration (`WALLET_PAYMENT_PROVIDER`, default `STRIPE`), not from the request.
- Errors: `400` for an invalid currency, a missing or invalid key, an amount that isn't positive, or a
  deposit Payment Service refuses (its message is passed on); `404` for no such wallet, checked before
  anything is charged; `409` as above; `502` when Payment Service is unreachable, times out, fails to start
  the payment or answers unexpectedly, with the detail saying which. Retrying a `502` with the same key is
  safe.
- A `400` passed on from Payment Service leaves the key free, except the one for a payment Stripe itself
  refused: its detail says to correct the request and retry with a new key
  ([ADR 0022](adr/0022-stripe-charge-rules-checked-before-the-reservation.md)).

A transfer moves money from the caller's wallet in `{currency}` to the wallet the user `to` holds in the same
currency. The body has no currency field, so a transfer between currencies can't be expressed. The first
answer and every replay are `200` with the same body:

```json
{
  "reference": "7e1855b3-4d95-4a72-a0c9-ef0d78be2e44",
  "to": "018f3a2b-7c4d-7e5f-8a9b-0c1d2e3f4a5b",
  "amount": 25.0000,
  "currency": "USD",
  "balanceAfter": 75.0000
}
```

The body is the sender's receipt. It has no timestamp and no entry number, so a replay can match the first
answer byte for byte; the movement itself, with both, is in the history. `balanceAfter` is the sender's
balance right after this transfer, and on a replay that is a past balance rather than the current one.
Nothing about the recipient's wallet appears. The status is `200` rather than `201` because no URL addresses
a transfer, and a replay has to match the first answer in status as well as body.

- `to` is the recipient's user id, held to the same rule as `X-User-Id`: a UUID of version 4 or 7, in either
  case. Unlike the header, surrounding whitespace is refused rather than stripped. A `to` equal to the
  caller's own id, compared after case folding, gets `400` "A transfer must go to another user".
- `amount` must be positive and sit on the grid deposits use for its currency: whole units for the 16
  zero-decimal currencies in Payment Service's `StripeCurrencyRules` (JPY and KRW among them) and for ISK, two
  decimals for every other. The wallet keeps its own copy of those lists in `AmountPrecision`, because a
  transfer never passes through Payment Service ([ADR 0015](adr/0015-currency-precision-and-no-rounding.md),
  [ADR 0023](adr/0023-isk-charged-in-whole-units.md)).
  Trailing zeros don't count, so `10.5000` USD and `100.00` JPY are fine. An amount off the grid, such as
  `10.001` USD or `1.5` JPY, gets a `400` and is never rounded. So does an amount with more than 15 integer
  digits, the room a `NUMERIC(19,4)` balance has. Nothing else limits the amount: the deposit range belongs
  to Payment Service and applies to deposits only.
- `Idempotency-Key` follows the deposit rules: required, any UUID version, lower-cased, never generated by
  the server. It becomes the `transactionReference` of both ledger entries.
- Repeating a transfer with the same key, from the same wallet, to the same recipient, for the same amount
  returns `200` with a body identical to the first and moves nothing. Amounts are compared by value, so
  `25`, `25.0` and `25.00` all match. This holds even after the balance was spent, because the key is judged
  before the funds. Deposits answer `409` to a completed key instead, since replaying one would hand back
  the client secret of a payment that is already made. A transfer is complete when it answers, so its
  replay returns the receipt a client needs after a lost response.
- Any other use of a key that already started a transfer gets `409`: a different amount or recipient, the
  same key from another of the caller's wallets, another user's transfer, a key copied from a transfer the
  caller received, or a request that lost a race for the key at the ledger's unique index. Every cause gets
  the same answer, so it reveals nothing about someone else's transfer. Keys are global, though, so a `409`
  still tells any caller that a key was used for some transfer, which is one more reason to use random keys.
- A refused transfer (`400`, `404`, `406`, `422`, `503`) writes nothing, so its key stays free and a later
  request under it is judged from scratch. A transfer refused for low funds can therefore go through later
  under the same key, after a top-up. Treat a 4xx as the final answer for that intent: a client that retries
  a `422` automatically with the same key can move money long after the user stopped expecting it.
- A key binds one kind of operation. The same key can name a deposit and a transfer, and each is still
  protected on its own: a payment is credited at most once and a key starts at most one transfer. The wallet
  can't refuse a key that a deposit uses, because an in-flight deposit's key exists only in `payment_db`
  until its outcome event arrives. Use a fresh key for each operation.
- Refusals come in a fixed order, so a request with several faults gets the first that applies. First is
  `406` for an `Accept` header that rules out JSON: the endpoint produces only JSON, so the request is
  refused while the endpoint is matched, before anything runs. Next, `401` for a missing or invalid
  `X-User-Id` wins over everything else, because `@CurrentUserId` is the controller's first parameter. Then
  comes `400` for anything wrong with the request itself, checked before any query; `404` when the caller
  holds no wallet in the currency; the key (`200` replay or `409`); `422` for insufficient funds; and last
  `422` for a recipient without a wallet in the currency.
- Errors: `400` as above; `404` "No USD wallet", always about the caller's own wallet; `409` as above;
  `422` "Insufficient funds in the USD wallet" (no figures) or "The recipient holds no USD wallet"; `503`
  when a lock or version check failed, in which case nothing moved and a retry with the same key is safe.

### Provider webhook

```
POST /api/payments/webhooks/{provider}      e.g. /api/payments/webhooks/stripe
```

The endpoint reads the raw request body, up to `PAYMENT_WEBHOOK_MAX_PAYLOAD_SIZE` (256KB by default), plus
the provider's signature headers, and checks the signature before it parses the body. Subscribe it to
`payment_intent.succeeded`, which marks the transaction `SUCCESS` and emits `PaymentCompletedEvent`, and to
`payment_intent.payment_failed`, which marks a still-`PENDING` transaction `FAILED` and emits
`PaymentFailedEvent`. A failure that arrives after a success changes nothing. A success is applied only when
the PaymentIntent's status is `succeeded` and its amount and currency match the transaction; otherwise the
transaction is left as it is and Payment Service logs an ERROR.

Until `STRIPE_WEBHOOK_SECRET` holds a real signing secret (`whsec_...`, not a placeholder), webhooks are
disabled: every delivery gets `400` and nothing is credited.

The status code of a webhook response is about delivery, not about the business outcome. `200` means
received: the event was applied, was already processed, is of another type, concerns a PaymentIntent this
service never created (from `stripe trigger` or the dashboard), or disagrees with the stored transaction. A
retry couldn't change any of those. `400` means the signature is missing, malformed or invalid, webhooks are
disabled, or the provider is unknown. `413` means the body is larger than the limit. `500` means a correctly
signed payload couldn't be processed. The reasoning is in
[ADR 0017](adr/0017-webhooks-verified-before-they-are-read.md).

### Internal: Payment Service (`:8082` directly)

Wallet Service calls this with `X-User-Id`. The gateway doesn't route it, and it isn't reachable through
`:8080`.

```
POST /api/payments/intent
X-User-Id: <uuid>
Content-Type: application/json

{
  "transactionReference": "7e1855b3-4d95-4a72-a0c9-ef0d78be2e44",
  "amount": 50.00,
  "currency": "USD",
  "providerName": "STRIPE"
}
```

It returns `{providerData, paymentIntentId, transactionReference}`, with `providerData.clientSecret` for
Stripe.

- `transactionReference` is the idempotency key, and it is also sent to Stripe as Stripe's own idempotency
  key. The same user with the same terms gets the original intent back, including after a declined card, so
  the payment can be retried. A reference that was reserved but never sent to the provider is retried on the
  same row. The response is `409` if the reference belongs to another user, if the amount, currency or
  provider differs, if the payment already succeeded, or if a concurrent request with the same reference got
  there first.
- `400` with a detail that names the reason and asks for a new key when Stripe refuses the payment itself
  (a `400` or `402` from Stripe). The reserved row stays, so a retry with the same key and terms gets the same
  answer. Any other Stripe failure is a `502`
  ([ADR 0022](adr/0022-stripe-charge-rules-checked-before-the-reservation.md)).
- `currency` must be an upper-case ISO 4217 code, `transactionReference` can be at most 64 characters, and
  `providerName` at most 32 (case-insensitive).
- `amount` must be between `1.00` and `10000.00` inclusive by default (`PAYMENT_MIN_DEPOSIT_AMOUNT` /
  `PAYMENT_MAX_DEPOSIT_AMOUNT`). For Stripe it can have at most two decimal places, and none for
  zero-decimal currencies such as JPY or for ISK, which Stripe takes in hundredths but charges in whole units;
  trailing zeros don't count. Three-decimal currencies such as KWD are
  limited to two as well. The currency must be one Stripe charges, and the amount must reach Stripe's minimum
  charge for the currencies Stripe lists one for (50 JPY, 175.00 HUF, 0.50 USD), both taken from a dated copy
  of Stripe's currency page in `StripeChargeLimits`. Breaking any of these gives a `400` before a transaction
  row is written.

## Error responses

The wallet and payment services return errors as RFC 9457 `application/problem+json`, produced by a shared
handler in `flow-wallet-platform`:

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "Invalid request content.",
  "instance": "/api/wallets/USD/deposits",
  "timestamp": "2026-09-26T10:15:30.123Z",
  "errors": ["amount Amount must be greater than zero"]
}
```

Validation failures include an `errors` array for body fields and for query and header parameters. A
currency in the path is checked by the service instead and comes back with a `detail` only. The `type`
field is left out: nothing sets it, and a null field isn't serialised.
The gateway is reactive and doesn't use this handler. Errors it raises itself (`404` for an unrouted path,
`5xx` when a downstream service is unreachable) come in Spring Boot's default WebFlux format.

The status mapping lives in one place, and each status stands for one remedy
([ADR 0016](adr/0016-error-model-and-status-codes.md)):

- `401`: missing or malformed `X-User-Id` (blank, over 64 characters, not a version 4 or 7 UUID).
- `400`: invalid request, unknown provider, non-ISO-4217 currency, a new wallet in a code with no minor unit,
  missing or non-UUID `Idempotency-Key`, a deposit Payment Service refuses (its message passed on, including
  a currency Stripe doesn't charge, an amount below Stripe's minimum, and a payment Stripe refused, whose key
  is spent), a transfer `to` that isn't a version 4 or 7 UUID, a transfer amount off its currency's grid or
  too large for a balance, a transfer to oneself, a missing, malformed or invalid webhook signature, or any
  webhook while no signing secret is configured.
- `404`: wallet not found. Wallet lookups are scoped to the caller, so it is never `403`. On a transfer it
  always means the caller's own wallet; a recipient without a wallet gets `422`.
- `406`: a transfer whose `Accept` header rules out JSON. It is refused before anything runs.
- `409`: transaction reference already in use (another user, a concurrent request, different terms, or
  already paid), wallet already exists, `Idempotency-Key` reused for a different or completed deposit or for
  a different transfer.
- `413`: a webhook body larger than `PAYMENT_WEBHOOK_MAX_PAYLOAD_SIZE`, refused before its signature is
  checked ([ADR 0017](adr/0017-webhooks-verified-before-they-are-read.md)).
- `422`: a transfer the caller's balance doesn't cover, or a recipient without a wallet in the currency. The
  request is well-formed and its key unspent, and the remedy is a smaller amount, a top-up or another
  recipient. On a transfer it is kept apart from `409`, whose remedy there is a new key, because without a
  `type` the status is all a client can branch on.
- `502`: upstream failure, meaning the payment provider failing (not refusing), or Payment Service being
  unreachable, timing out, failing to start the payment or answering unexpectedly. The detail says which.
  Retrying a deposit with the same key is safe.
- `503`: a transfer lost a lock or a version check (a deadlock, a lock wait that timed out, a version
  conflict). The lock order is meant to rule these out. Nothing was moved, and retrying with the same key is
  safe.
- `500`: a correctly signed webhook payload that can't be processed, a transfer that broke a database CHECK
  or overflowed a column, or anything unexpected. The detail stays generic; the specifics go to the logs and
  are never returned.
