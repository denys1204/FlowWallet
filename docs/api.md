# FlowWallet HTTP API

This file lists the HTTP endpoints FlowWallet exposes: the wallet endpoints behind the gateway, the Stripe webhook,
the internal Payment Service calls that start a deposit, set a bank account and check a withdrawal, and the operator
endpoint for payouts that wait for a person. It also covers the error format the wallet and payment services share. For
how the services fit together, see [ARCHITECTURE.md](../ARCHITECTURE.md); for the database tables behind these
endpoints, see [the data model](data-model.md); for running the services locally, see [development](development.md).

## API reference

The base URL through the gateway is `http://localhost:8080`. Every wallet endpoint needs `X-User-Id`.

### Authentication

FlowWallet does not authenticate callers. The caller's identity is the `X-User-Id` header, a version 4 or 7 UUID, and
the services take it on trust ([ADR 0003](adr/0003-caller-identity-and-trust-boundary.md)). In a deployment, an
authentication layer in front of the gateway authenticates the caller, sets `X-User-Id` and removes any value the client
sent. The gateway forwards the header unchanged and listens on `127.0.0.1` by default (`GATEWAY_ADDRESS`), so without
that layer only clients on the same machine reach it, and they send `X-User-Id` themselves. Stripe's webhooks are
authenticated by their signature instead ([ADR 0031](adr/0031-callers-are-authenticated-in-front-of-the-gateway.md)).

### Wallet Service

A wallet is addressed by its ISO 4217 currency and never by an id. A user holds at most one wallet per
currency, and `X-User-Id` already names the owner, so the pair identifies the wallet exactly. It also means
"not yours" and "does not exist" give the same result
([ADR 0004](adr/0004-wallet-addressed-by-owner-and-currency.md)). Currency is case-insensitive in the
path and in the body.

Money is a decimal string at the currency's scale in every response: `"75.00"` for USD, `"1000"` for JPY. The
wallet's `decimals` says how many places its currency carries: 0 for the zero-decimal currencies, ISK and UGX, 2
for every other, KWD and BHD included. Parse money as a decimal, never as a float. A request takes an amount as a string
or as a JSON number; the string is the documented form
([ADR 0030](adr/0030-amounts-in-responses-are-decimal-strings.md)). A string amount is a plain decimal, surrounding
whitespace is ignored, and an empty string counts as a missing amount.

| Method & path | Request | Response |
|---------------|---------|----------|
| `GET /api/wallets` | (none) | `200` `[{balance, currency, decimals, createdAt, updatedAt}]`, ordered by currency; an empty list if none, never `404` |
| `POST /api/wallets` | `{"currency": "USD"}` | `201` the wallet; `400` not three letters (listed in `errors`), not an ISO 4217 code, or a code with no minor unit (XAU, XTS, XXX and the like); `409` a wallet in that currency already exists |
| `GET /api/wallets/{currency}` | (none) | `200` the wallet; `400` invalid code; `404` the caller holds no such wallet (never `403`) |
| `GET /api/wallets/{currency}/history?before={entryNo}&limit={n}` | (none) | `200` `{items, nextBefore}`, newest first |
| `POST /api/wallets/{currency}/deposits` | `{"amount": "50.00"}` + `Idempotency-Key` header | `200` `{reference, provider, providerData}` |
| `POST /api/wallets/{currency}/transfers` | `{"to": "<user id>", "amount": "25.00"}` + `Idempotency-Key` header | `200` `{reference, to, amount, currency, balanceAfter}` |
| `POST /api/wallets/{currency}/withdrawals` | `{"amount": "75.00"}` + `Idempotency-Key` header | `202` with `Location` and `{reference, amount, currency, balanceAfter}`; `422` refused, with its key spent; `503` also when the platform's reserve is short ([Withdrawals](#withdrawals)) |
| `GET /api/wallets/{currency}/withdrawals/{reference}` | (none) | `200` `{reference, amount, currency, status, reason}`; `404` no such withdrawal in the caller's wallet |
| `PUT /api/wallets/bank-account` | `{"token": "btok_...", "tosAccepted": true}` | `200` `{state, last4, currency}`; `400` not a bank token, terms not accepted, or Stripe refused the token; `502` Payment Service or Stripe gave no settling answer |
| `GET /api/wallets/bank-account` | (none) | `200` `{state, last4, currency}`, `NOT_SET` with nulls when none is set |

On every wallet endpoint a missing or invalid `X-User-Id` gets `401` before the body or any parameter is
checked. `POST /api/wallets`, deposits, transfers, withdrawals and `PUT /api/wallets/bank-account` produce only JSON, so
an `Accept` header that rules it out gets `406` before anything is created.

The bank account is the user's, not a wallet's, and withdrawals are paid out to it. The client creates a Stripe bank
account token with the publishable key, so bank details never reach FlowWallet, and sends only the token with
`tosAccepted: true`, the user's acceptance of Stripe's Connected Account Agreement. Wallet Service relays both routes to
Payment Service and stores nothing. `state` is `READY` when a payout can go to the bank account now and `NOT_SET`
otherwise. A user has one bank account: a new token replaces it, whatever its currency. A `400` for a refused token
needs a new token, because Stripe accepts a token once; after a `502`, retry with the same token, which is answered from
Payment Service's row if the first attempt went through
([ADR 0035](adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md)).

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
`TRANSFER_IN`) and `null` for every other movement: a `DEPOSIT`, a `WITHDRAWAL` (the debit of a withdrawal) and a
`WITHDRAWAL_RETURNED` (its money coming back). Both legs of a transfer carry the sender's key as
`transactionReference`, and a withdrawal's debit and its return carry the withdrawal's key.

A deposit returns `200` rather than `201` or `202`. Nothing gets created on the wallet's side, and the client
finishes the work itself by confirming the payment with Stripe using `providerData.clientSecret`.

A deposit is paid by card ([ADR 0028](adr/0028-deposits-accept-cards-only.md)), and the client sees it go
through three steps:

1. Started: the `200` carries `providerData.clientSecret`, and the client confirms the card with Stripe.js. A
   card that asks for 3D Secure is completed there too.
2. Declined, if the card is refused: nothing moves, and the deposit stays open. The client can confirm the
   same payment with another card, and repeating the request with the same key returns the same payment.
3. Credited: once Stripe reports the payment as succeeded, the balance rises and a `DEPOSIT` entry appears in
   the history. No response announces it, so the client reads the wallet or its history.

- `Idempotency-Key` is required and can be a UUID of any version, in either case. It is lower-cased and
becomes the payment's transaction reference, so the same string runs from the client through Payment Service into
Stripe, onto the event and into the ledger. The server never generates one. If it did, a lost response would get a fresh
key on retry, and the customer would be charged twice. The key rules shared by deposits, transfers and withdrawals are
in [ADR 0005](adr/0005-client-supplied-idempotency-keys.md).
- Repeating a request with the same key and the same amount returns a byte-identical body. Reusing a key
  with a different amount, or a key whose deposit already completed, gets a `409`.
- The provider comes from configuration (`WALLET_PAYMENT_PROVIDER`, default `STRIPE`), not from the request.
- Refusals come in the transfer's order: `406` for an `Accept` header that rules out JSON, refused before
  anything runs, so no payment intent is created for an answer the client can't read; then `401` for a
  missing or invalid `X-User-Id`, before the key and the body are looked at.
- Errors: `400` for an invalid currency, a missing or invalid key, an amount that isn't positive, or a
  deposit Payment Service refuses (its message is passed on); `404` for no such wallet, checked before
  anything is charged; `409` as above; `502` when Payment Service is unreachable, times out, fails to start
  the payment or answers unexpectedly, with the detail saying which; `503` when the wallet's database is
  unavailable. Retrying a `502` or `503` with the same key is safe.
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
  "amount": "25.00",
  "currency": "USD",
  "balanceAfter": "75.00"
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
- `amount` must be positive and sit on the grid deposits use for its currency: whole units for the 15
  zero-decimal currencies in Payment Service's `StripeCurrencyRules` (JPY and KRW among them) and for ISK and UGX,
  two decimals for every other. The wallet keeps its own copy of those lists in `AmountPrecision`, because a
  transfer never passes through Payment Service ([ADR 0015](adr/0015-currency-precision-and-no-rounding.md),
  [ADR 0023](adr/0023-isk-charged-in-whole-units.md), [ADR 0038](adr/0038-ugx-charged-in-whole-units.md)).
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
- A refused transfer (`400`, `404`, `406`, `422`, or `503` for a lost lock) writes nothing, so its key stays
  free and a later request under it is judged from scratch. A transfer refused for low funds can therefore go
  through later under the same key, after a top-up. Treat a 4xx as the final answer for that intent: a
  client that retries a `422` automatically with the same key can move money long after the user stopped
  expecting it.
- A key binds one kind of operation. The same key can name a deposit, a transfer and a withdrawal, and each is
  still protected on its own: a payment is credited at most once and a key starts at most one transfer and at
  most one withdrawal. The wallet can't refuse a key that a deposit uses, because an in-flight deposit's key
  exists only in `payment_db` until its outcome event arrives. Use a fresh key for each operation.
- Refusals come in a fixed order, so a request with several faults gets the first that applies. First is
  `406` for an `Accept` header that rules out JSON: the endpoint produces only JSON, so the request is
  refused while the endpoint is matched, before anything runs. Next, `401` for a missing or invalid
  `X-User-Id` wins over everything else, because `@CurrentUserId` is the controller's first parameter. Then
  comes `400` for anything wrong with the request itself, checked before any query; `404` when the caller
  holds no wallet in the currency; the key (`200` replay or `409`); `422` for insufficient funds; and last
  `422` for a recipient without a wallet in the currency.
- Errors: `400` as above; `404` "No USD wallet", always about the caller's own wallet; `409` as above;
  `422` "Insufficient funds in the USD wallet" (no figures) or "The recipient holds no USD wallet"; `503`
  when a lock or version check failed, in which case nothing moved, or when the database is unavailable, in
  which case the transfer may have committed. A retry with the same key is safe in both cases: it gets the
  receipt or makes the transfer once.

#### Withdrawals

A withdrawal pays part of a wallet's balance out to the caller's bank account through Stripe Connect, in test mode. The
wallet is debited when it accepts the request and Payment Service pays out afterwards, so the answer is `202` rather
than `200`: a URL addresses the withdrawal, and the server finishes it later
([ADR 0033](adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md)). The caller sets a bank
account first with `PUT /api/wallets/bank-account`.

Payment Service owns the terms, and the wallet asks it before it debits anything: PLN only and a minimum of 5.00 by
default (`PAYMENT_PAYOUT_CURRENCY`, `PAYMENT_PAYOUT_MIN_AMOUNT`), no more decimals than a payout in the currency allows,
and at most 15 integer digits. A withdrawal from a wallet in another currency gets `400`. The amount must also sit on
the wallet's own grid, the one a transfer uses. Nothing else limits it but the balance and the platform's reserve. The
first answer and every replay are `202`:

```
POST /api/wallets/PLN/withdrawals
X-User-Id: <uuid>
Idempotency-Key: 6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d
Content-Type: application/json

{"amount": "75.00"}
```

```
HTTP/1.1 202 Accepted
Location: /api/wallets/PLN/withdrawals/6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d
Content-Type: application/json

{"reference":"6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d","amount":"75.00","currency":"PLN","balanceAfter":"25.00"}
```

The body is the receipt, as a transfer's is. It has no status and no timestamp, so a replay matches the first answer
byte for byte; the debit itself is a `WITHDRAWAL` entry in the history. `balanceAfter` is the balance right after this
debit, and on a replay that is a past balance. The status is read at `Location`, which is a path built from the wallet's
upper-case currency code and the lower-cased key, never from the request's host or path. A request to
`/api/wallets/pln/withdrawals` with an upper-case key gets the same `Location` and body as its replay, and the path is
valid through the gateway, which routes `/api/wallets/**` unchanged. The gateway's CORS settings expose no response
header, so a browser script cannot read `Location`; the body's `currency` and `reference` give the same path.

- `amount` is required and positive, a string or a JSON number, as in a deposit.
- `Idempotency-Key` follows the deposit rules: required, any UUID version, lower-cased, never generated by the server.
  It becomes the withdrawal's reference, the `transactionReference` of its ledger entries and the reference its payout
  carries at Stripe. A server-generated key would give a retry after a lost response a fresh one, and the money would
  go out twice.
- The key binds the wallet, which fixes the currency, and the amount, compared by value, so `75`, `75.0` and `75.00`
  match. Only withdrawals are consulted: a key that a deposit or a transfer used is free for a withdrawal.
- A key that got a `202` gets the same `202`, `Location` and body for every request with the same terms, whatever the
  withdrawal's status by then, `PAID` and `RETURNED` included, and even after the minimum was raised or the bank
  account removed. The wallet rebuilds the answer from the stored ledger entry and never asks Payment Service, so a
  replay works while Payment Service is down.
- A key that got a `422` gets the same `422` and detail on every request with the same terms, even after a top-up or a
  bank account set later. That is the difference from a transfer, whose refused key stays free: a withdrawal's
  automatic retry must never pay out a withdrawal the user saw refused.
- Another amount, another of the caller's wallets or another user's withdrawal under the key gets `409`, one answer
  for every cause.
- A `400` or a `422` ends the matter for that key. A `400` writes nothing and leaves the key free, but the terms Payment
  Service checks come from configuration, so a request refused today, such as one below a minimum that is later lowered,
  could pass under the same key. A new attempt, with a new confirmation from the user, takes a new key.
- A `502`, a `503`, a gateway `504`, a client timeout or a dropped connection can leave the outcome unknown. Repeat the
  request under the **same** key until the answer is not a 5xx, and never switch to a new key after an unknown outcome:
  the first request can still commit, and a second key would pay out twice. The repeated `POST` is the authoritative
  answer, because the wallet judges it under the wallet's lock. A `GET` shows progress, but its `404` does not prove
  that a request still in flight will not commit.
- A `RETURNED` withdrawal is not retried under its key, which still answers with the original `202`. A new withdrawal
  takes a new key.

Refusals come in a fixed order, so a request with several faults gets the first that applies:

| # | Status | Cause | Written | The key afterwards |
|---|--------|-------|---------|--------------------|
| 1 | `406` | the `Accept` header rules out JSON | nothing | free |
| 2 | `401` | `X-User-Id` missing or invalid | nothing | free |
| 3 | `400` | the key is missing or not a UUID, the body is unreadable, or the amount is missing or not positive (listed in `errors`) | nothing | free |
| 4 | `400` | the currency is not an ISO 4217 code, or the amount is off the wallet's grid or over 15 integer digits | nothing | free |
| 5 | `404` | the caller holds no wallet in the currency | nothing | free |
| 6 | `202`, `422` or `409` | the key already names a withdrawal: replayed, refused or a different one | nothing | as found |
| 7 | `400` | Payment Service refuses the terms: a currency it does not pay out in, an amount below the minimum or off the payout grid | nothing | free, but use a new key |
| 8 | `502` | Payment Service is unreachable, times out, answers 5xx, refuses the wallet's own request or answers something the wallet cannot read | nothing | free |
| 9 | `503` | the bank account is ready and the platform's reserve is short | nothing | free |
| 10 | `202`, `422` or `409` | the key is judged again under the wallet's lock, after a request with the same key committed meanwhile | nothing | as found |
| 11 | `422` | no bank account is ready in the currency | a `REFUSED` withdrawal | spent |
| 12 | `422` | the balance does not cover the amount | a `REFUSED` withdrawal | spent |
| 13 | `202` | debited | the `WITHDRAWAL` entry, a `PENDING` withdrawal and the command to Payment Service | spent |
| 14 | `503` | the wallet lost a lock or a version check | nothing | free |
| 15 | `503` | the database is unavailable, or the connection was lost during the commit | unknown | unknown |
| 16 | `500` | a defect | nothing expected | none |

The bank account is judged before the funds, so a caller with neither is told to set a bank account, as
[ADR 0033](adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md) orders it; a transfer judges
the funds before the recipient. The wallet checks the terms and the reserve with Payment Service
([the internal call](#internal-payment-service-8082-directly)) before it opens the transaction that debits, so a
refusal at 7 to 9 writes nothing.

What a client does with each status:

| Status | Written | The key | The client |
|--------|---------|---------|------------|
| `202` | the debit | spent | follows `Location`, or builds the path from `currency` and `reference`; a replay gives the same bytes |
| `400` | nothing | free, but start over | fixes the request; a new confirmation takes a new key |
| `401`, `406` | nothing | free | fixes the header |
| `404` | nothing | free | opens and funds the wallet, then uses a new key |
| `409` | nothing | not applicable | uses a new key |
| `422` | a `REFUSED` withdrawal | **spent** | tops up or sets a bank account, then uses a **new** key; the same key keeps getting this `422` |
| `502` | nothing | free | repeats with the **same** key; a repeat is always safe, and after "Payment Service refused the request" it keeps failing until the service is fixed, so the client also reports it |
| `503` for a short reserve or a busy wallet | nothing | free | repeats later with the same key, or stops; both are safe |
| `503` for the database, `504`, no answer | unknown | unknown | repeats with the same key until the answer is not a 5xx |
| `500` | nothing expected | not applicable | reports it; a repeat under the same key is safe |

The details are written by the server and never quote the request, a figure or a user id:

- `404`: "No PLN wallet", and on the read "No such withdrawal in the PLN wallet".
- `409`: "This Idempotency-Key was already used for a different withdrawal. Retry with a new key."
- `422`: "Insufficient funds in the PLN wallet. This Idempotency-Key is spent; withdraw a smaller amount or top up, then
  use a new key." or "No bank account is ready for payouts in PLN. This Idempotency-Key is spent; set a bank account,
  then use a new key."
- `400` from Payment Service's terms: its own words, which name the rule, such as "The smallest withdrawal in PLN is
  5.00", and never the amount.
- `502`: says what failed and that nothing was debited. Three variants invite a retry with the same key: Payment Service
  unavailable, Payment Service could not check the withdrawal, and an answer the wallet could not read. The fourth,
  "Payment Service refused the request", means the wallet's own call is wrong: a retry under the same key is safe but
  keeps failing until that is fixed, so report it.
- `503`: "Withdrawals are temporarily unavailable. Nothing was debited; retry later with the same Idempotency-Key." for
  a short reserve, which says nothing about the reserve; "The wallet is busy and nothing was debited. Retry with the
  same Idempotency-Key." for a lost lock; and the detail of [ADR 0025](adr/0025-unreachable-database-answers-503.md)
  for the database, which does not say whether anything was debited.

`GET /api/wallets/{currency}/withdrawals/{reference}` shows where a withdrawal stands. `{reference}` is the key, in
either case, and a key that got a `422` can be read as well:

```json
{
  "reference": "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d",
  "amount": "75.00",
  "currency": "PLN",
  "status": "RETURN_PENDING",
  "reason": "PAYOUT_FAILED"
}
```

| `status` | Meaning | The client |
|----------|---------|------------|
| `PENDING` | debited and on its way. The payout executor runs every 10 seconds by default; in Stripe test mode with the Connect webhook a withdrawal showed `PAID` about 11 seconds after its `202`. Without the Connect webhook secret a payout settles only when the payout reconciler asks Stripe, first 15 minutes after the withdrawal was accepted | polls |
| `PAID` | Stripe reports the payout paid. This is not final: a payout can fail days later, which the payout reconciler checks every 6 hours for 10 days and a webhook can report at any time | treats it as sent and rechecks rarely |
| `RETURN_PENDING` | the payout failed or could not be made, and the money is on its way back but not credited yet | polls |
| `RETURNED` | the money is back in the wallet, in full and once, as a `WITHDRAWAL_RETURNED` entry in the history under the same key; `reason` says why | stops |
| `REFUSED` | the wallet never debited it; `reason` is `NO_BANK_ACCOUNT` or `INSUFFICIENT_FUNDS` | stops |

- `reason` is the refusal reason of a `REFUSED` withdrawal and the reason the money comes back for a `RETURN_PENDING` or
  `RETURNED` one, and `null` otherwise. The reasons for a return are an open set, so a client shows one and must not
  fail on an unknown value. The values in use are `PLATFORM_FUNDS_SHORT` (the platform's balance was short when the
  transfer was made), `TRANSFER_REFUSED` (Stripe refused the transfer for another reason), `BANK_ACCOUNT_NOT_SET` (the
  bank account was not ready), `BANK_ACCOUNT_REJECTED` (Stripe refused the payout for a reason about the bank account),
  `PAYOUT_FAILED` (the payout failed, was canceled or was refused), `TERMS_REFUSED` (the terms Payment Service sets no
  longer allowed the withdrawal when it accepted it), `OPERATOR_RETURNED` (an operator returned it) and `OTHER`.
- `amount` and `currency` are the withdrawal's, as in the `202`. The read has no timestamp.
- A `404` "No PLN wallet" is about the caller's own wallet. A `404` "No such withdrawal in the PLN wallet" answers an
  unknown reference, another wallet's withdrawal and another user's alike. `400` is for a reference that is not a UUID
  (in `errors`, not quoted) and for an invalid currency. The read needs no Payment Service, so it works while Payment
  Service is down.
- The money of a `RETURN_PENDING` withdrawal comes back only with its `WITHDRAWAL_RETURNED` entry. The status alone
  does not credit the wallet.

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
disabled: every delivery gets `400`, and a paid deposit is credited only when the reconciler finds it, 15 minutes or
more after it started ([ADR 0032](adr/0032-pending-payments-are-rechecked-with-the-provider.md)).

The status code of a webhook response is about delivery, not about the business outcome. `200` means
received: the event was applied, was already processed, is of another type, concerns a PaymentIntent this
service never created (from `stripe trigger` or the dashboard), or disagrees with the stored transaction. A
retry couldn't change any of those. `400` means the signature is missing, malformed or invalid, webhooks are
disabled, or the provider is unknown. `413` means the body is larger than the limit. `500` means a correctly
signed payload couldn't be processed. The reasoning is in
[ADR 0017](adr/0017-webhooks-verified-before-they-are-read.md).

### Connect webhook

```
POST /api/payments/webhooks/stripe/connect
```

Register it in Stripe for connected accounts (`connect=true`) with the SDK's API version, `2026-06-24.dahlia`, and
subscribe it to `payout.paid`, `payout.failed`, `payout.canceled`, `account.updated` and
`account.external_account.updated`. It reads the body like the provider webhook, under the same size cap, and checks the
signature with `STRIPE_CONNECT_WEBHOOK_SECRET` before it parses the body. Until that holds a real signing secret, every
delivery gets `400` and payouts settle only when the payout reconciler asks Stripe about them. By default it first asks
about a created payout 15 minutes after its withdrawal was accepted and then at most every 15 minutes, and about a paid
one every 6 hours for 10 days after it was paid
([ADR 0036](adr/0036-connect-webhooks-have-their-own-endpoint-and-secret.md)). Under `stripe listen` events arrive in
the account's default API version; Payment Service still reads them and logs a WARN for each.

An event only names the payout or the account that changed. Payment Service reads it from Stripe and applies its current
status. `paid` marks the payout `PAID` and emits `PayoutPaidEvent`. `failed` or `canceled` starts the reversal of the
transfer and emits `PayoutFailedEvent`, also for a payout that was already paid. A `failed` payout sent to the stored
bank account sets that account back to `NOT_SET`; a `canceled` one leaves it alone. An account event sets the bank
account's state from the account Stripe returns.

The status code reports delivery, as on the provider webhook. `200` means received: the event was applied, was already
applied, is of another type, lacks the account or the payout id it needs (logged at WARN), concerns a payout or an
account this service does not know, or disagrees with the stored payout on its account, amount, currency, payout id or
reference (logged at ERROR, and nothing changes). `400` means the signature is missing, malformed or invalid, or the
Connect secret is not set. `413` means the body is larger than the limit. `500` means a correctly signed event couldn't
be processed, a failed read from Stripe or a bank account refresh that raced another write included, so Stripe delivers
it again. `503` means the database could not be reached, and Stripe delivers that event again too.

### Internal: Payment Service (`:8082` directly)

Wallet Service calls this with `X-User-Id`. The gateway doesn't route it, and it isn't reachable through
`:8080`. A missing or invalid `X-User-Id` gets `401` before the body is checked.

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

This internal call keeps `amount` a JSON number. It is not part of the public API.

It returns `{providerData, paymentIntentId, transactionReference}`, with `providerData.clientSecret` for
Stripe. The intent offers only the payment methods in `STRIPE_PAYMENT_METHOD_TYPES`, cards by default
([ADR 0028](adr/0028-deposits-accept-cards-only.md)).

- `transactionReference` is the idempotency key, and it is also sent to Stripe as Stripe's own idempotency
  key. The same user with the same terms gets the original intent back, including after a declined card, so
  the payment can be retried. A reference that was reserved but never sent to the provider is retried on the
  same row. The response is `409` if the reference belongs to another user, if the amount, currency or
  provider differs, or if the payment already succeeded, whether that row was there first or won a concurrent
  reservation. A concurrent request with the same user and terms gets the winner's intent, or `503` while the
  winner has not recorded Stripe's answer yet, which the wallet passes on as a `502` to retry with the same key
  ([ADR 0024](adr/0024-deposit-initiation-settles-its-own-races.md)).
- `400` with a detail that names the reason and asks for a new key when Stripe refuses the payment itself
  (a `400` or `402` from Stripe). The reserved row stays, so a retry with the same key and terms gets the same
  answer. Any other Stripe failure is a `502`
  ([ADR 0022](adr/0022-stripe-charge-rules-checked-before-the-reservation.md)).
- `currency` must be an upper-case ISO 4217 code, `transactionReference` a lower-case UUID (the wallet
  lower-cases the caller's key), and `providerName` at most 32 characters (case-insensitive).
- `amount` must be between `1.00` and `10000.00` inclusive by default (`PAYMENT_MIN_DEPOSIT_AMOUNT` /
  `PAYMENT_MAX_DEPOSIT_AMOUNT`). For Stripe it can have at most two decimal places, and none for
  zero-decimal currencies such as JPY or for ISK and UGX, which Stripe takes in hundredths but charges in whole units;
  trailing zeros don't count. Three-decimal currencies such as KWD are
  limited to two as well. The currency must be one Stripe charges, and the amount must reach Stripe's minimum
  charge for the currencies Stripe lists one for (50 JPY, 175.00 HUF, 0.50 USD), both taken from a dated copy
  of Stripe's currency page in `StripeChargeLimits`. Breaking any of these gives a `400` before a transaction
  row is written.

```
PUT /api/payments/payout-accounts
X-User-Id: <uuid>
Content-Type: application/json

{"token": "btok_1PxYz...", "tosAccepted": true}

GET /api/payments/payout-accounts
X-User-Id: <uuid>
```

These set and read the caller's bank account for withdrawals. Both return `{state, last4, currency}`: `state` is
`READY` when a payout can go to the bank account now and `NOT_SET` otherwise, `last4` the bank account's last four
digits and `currency` its currency. A caller with no bank account gets `NOT_SET` with `last4` and `currency` null
([ADR 0035](adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md)).

- `token` is a Stripe bank account token (`btok_`) the client creates with the publishable key, so bank details
  never reach FlowWallet. Anything else is a `400` that does not quote it.
- `tosAccepted` must be `true`: the user accepts Stripe's Connected Account Agreement, which opening their
  connected account needs. Anything else is a `400`.
- The first request opens the user's connected account. Every request with another token attaches it as the default bank
  account for the token's currency and records it as the user's one bank account, replacing the previous one in any
  currency. After it records the bank account, the request reads the
  connected account once more and answers with the state that read sets, so a change Stripe made in between, such as
  turning payouts on, is not lost; if that read fails, it answers with the recorded state. A request with the token last
  applied is answered from the stored row, because Stripe accepts a token once.
- `400` when Stripe refuses the token (it does not exist, it was used already, or the bank account cannot take
  payouts): create a new token. `502` when Stripe gives no answer that settles it: retry with the same token.

```
POST /api/payments/payouts/preflight
X-User-Id: <uuid>
Content-Type: application/json

{"currency": "PLN", "amount": 75.00}
```

Wallet Service asks this before it debits a withdrawal, with no transaction open
([ADR 0033](adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md)); the client's side is in
[Withdrawals](#withdrawals). It returns `{bankAccount, reserveSufficient}`: `bankAccount` is `READY` when the caller's
bank account takes payouts in the currency and `NOT_SET` otherwise, and `reserveSufficient` says whether the platform's
available Stripe balance, less what payouts not yet transferred still owe, covers the amount. It is `null` while no bank
account is ready, because the balance is not read then
([ADR 0035](adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md)).

- `400` when the withdrawal misses Payment Service's terms: a currency it does not pay out in (PLN only by default), an
  amount below the minimum (`PAYMENT_PAYOUT_MIN_AMOUNT`, 5.00 by default and never below Stripe's own minimum payout
  where `StripePayoutMinimums` lists one), more decimals than a payout in the currency allows, or more than 15 integer
  digits. The detail names the rule, and the minimum for an amount below it, such as "The smallest withdrawal in PLN is
  5.00.", and never the amount.
- `400` also when `currency` is not three capital letters or `currency` or `amount` is missing.
- `502` when Stripe cannot report the platform's balance. Nothing is written; retry.
- The check is not atomic: two withdrawals can pass it together, as can one whose command is still on its way, and
  the transfer that finds the balance short returns its money.
- The wallet sends `amount` at the ledger's scale, four decimals such as `75.0000`, as a JSON number. Trailing zeros
  count against neither the payout grid nor the digit limit.
- The wallet reads the answer this way. `NOT_SET` is a recorded `422` for the withdrawal, whatever `reserveSufficient`
  says. `READY` with `true` goes on to the debit. `READY` with `false` is a `503` with nothing written. A body the
  wallet cannot read, a `bankAccount` it does not know or a `READY` without `reserveSufficient` is a `502` and is never
  taken as either state, because taking it as `NOT_SET` would spend the caller's key. A `400` is passed on as the
  withdrawal's `400`, and a `5xx`, a timeout or any other `4xx` is a `502`.

### Operator: the `payouts` actuator endpoint (`:8082` directly)

A payout the executor cannot finish waits in `NEEDS_ATTENTION` until a person acts. This endpoint lists those payouts,
shows one payout and moves a waiting payout on. It is a web-only actuator endpoint on Payment Service's own port, and
the gateway does not route to it. It authenticates nobody and its writes credit wallets, so it is in no default
exposure: with the default `ACTUATOR_EXPOSED_ENDPOINTS` every path below answers `404`
([ADR 0037](adr/0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md)).

To use it, add `payouts` to `ACTUATOR_EXPOSED_ENDPOINTS` for the one Payment Service process, and never in `.env`, which
the gateway and Wallet Service read too (the command and the reason are in the
[configuration table](development.md#configuration)). A value of `*` exposes it as well. In a cluster, set the variable
on Payment Service's workload only while acting, reach the pod with `kubectl port-forward`, and remove the variable
afterwards. Send the requests with curl from the service's host or through the port-forward: Payment Service answers
`payouts` only under a loopback `Host` (`localhost`, `127.0.0.1` or `[::1]`), and refuses with `403` every actuator
request that a web page sends, one with an `Origin` header or a `Sec-Fetch-Site` other than `none`, because a page open
in the operator's browser could otherwise send the writes to `localhost`.

| Request | Answer |
|---------|--------|
| `GET /actuator/payouts` | `200` the payouts in `NEEDS_ATTENTION`, longest-waiting first |
| `GET /actuator/payouts/{reference}` | `200` one payout in any status |
| `POST /actuator/payouts/{reference}/{action}` with `X-FlowWallet-Operator` | `200` the action's result; `action` is `requeue`, `return-without-reversal` or `reverse` |

`{reference}` is the withdrawal's transaction reference, in either case. A write carries the header
`X-FlowWallet-Operator` with any value, for example
`curl -X POST -H 'X-FlowWallet-Operator: on-call' http://localhost:8082/actuator/payouts/{reference}/requeue`: a web
page cannot set a custom header across origins without a preflight, which the actuator never answers, so the header
keeps even a browser that sends no `Origin` from posting a write. No request takes a body, a query string or a user id,
and one that has a body or a query string gets `400`, because the actuator would merge their fields over the reference
and the action in the path; and no answer carries a user id, the row's id or its `version`. A field without a value is
`null`. Amounts are decimal strings at the currency's payout scale and times are ISO-8601 text.

The list answer is `{needsAttention, byFrom, truncated, payouts}`:

```json
{
  "needsAttention": 3,
  "byFrom": {"ACCEPTED": 1, "TRANSFERRED": 1, "REVERSING": 1},
  "truncated": false,
  "payouts": [ { "...": "a payout, as below" } ]
}
```

`payouts` holds at most 100 payouts. `needsAttention` counts every waiting payout and `byFrom` counts them by the step
they came from, and `truncated` is `true` when more wait than `payouts` lists, which one row read beyond the 100
decides. The counts and the list are read one after another, so they can differ by a payout that moved in between. A
payout looks like this in the list, in the single read and in an action's answer:

```json
{
  "reference": "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d",
  "status": "NEEDS_ATTENTION",
  "attentionFrom": "ACCEPTED",
  "cause": "MULTIPLE",
  "detail": "2 transfers in the group",
  "since": "2026-10-10T09:00:30.123456Z",
  "amount": "75.00",
  "currency": "PLN",
  "reasonCode": null,
  "failureCode": null,
  "keyGen": 0,
  "attempts": 1,
  "nextAttemptAt": null,
  "stripeAccountId": null,
  "stripeTransferId": null,
  "stripePayoutId": null,
  "stripeReversalId": null,
  "transferredAt": null,
  "paidAt": null,
  "createdAt": "2026-10-10T08:59:12.404118Z",
  "actions": ["requeue", "return-without-reversal"]
}
```

- `since` is when the payout was handed over to an operator, `cause` says why and `detail` is what the executor saw, cut
  to 255 characters. All three are set only while the payout waits, and `cause` and `detail` are `null` for a payout
  handed over before changeset 010 stored them. `cause` is one of:
  - `UNKNOWN_LIMIT`: the step's outcome stayed unknown for `PAYMENT_PAYOUT_EXECUTOR_UNKNOWN_ATTEMPTS` tries.
  - `DEFECT`: an idempotency key was sent with other parameters, which only a defect causes.
  - `MISMATCH`: Stripe holds an object for the reference whose terms or destination are not the payout's.
  - `MULTIPLE`: Stripe holds more than one transfer, or more than one live payout, for the reference.
  - `LIVE_PAYOUT`: a reversal was due while a payout for the reference may still reach the bank.
  - `REVERSAL_REFUSED`: Stripe kept refusing the reversal for `PAYMENT_PAYOUT_EXECUTOR_REVERSAL_RETRY_ATTEMPTS` tries.
- `attentionFrom` is the step a `requeue` returns the payout to. `nextAttemptAt` is set only in an executor step
  (`ACCEPTED`, `TRANSFERRED` or `REVERSING`). `attempts` counts the executor's tries, and the try that handed the payout
  over is not counted, so `UNKNOWN_LIMIT` shows one less than `PAYMENT_PAYOUT_EXECUTOR_UNKNOWN_ATTEMPTS`.
- `actions` lists what the payout's state allows and promises nothing about Stripe, whose checks can still refuse:
  `requeue` and `return-without-reversal` from `ACCEPTED` and `REVERSING`, `requeue` and `reverse` from `TRANSFERRED`,
  and none for a payout that is not waiting.

The three actions:

| Action | Allowed from | Result |
|--------|--------------|--------|
| `requeue` | any waiting payout | Back to `attentionFrom`, with its key generation kept. Stripe is not asked and no event is written. The executor takes the step up at its next run and looks for what Stripe holds before it creates anything |
| `return-without-reversal` | `ACCEPTED`, `REVERSING` | `RETURNED` with the reason `OPERATOR_RETURNED` and a `PayoutReturnedEvent`, which credits the wallet |
| `reverse` | `TRANSFERRED` | `REVERSING` with the reason `PAYOUT_FAILED` and a `PayoutFailedEvent`. The executor then reverses the transfer and ends the payout in `RETURNED`, which credits the wallet |

The wallet's withdrawal follows the payout's events. A `requeue` changes nothing there. A return from `ACCEPTED` takes
it from `PENDING` to `RETURNED` and credits the amount, and a return from `REVERSING` takes it from `RETURN_PENDING` to
`RETURNED` and credits it. `reverse` takes it from `PENDING` to `RETURN_PENDING` at once, and to `RETURNED` with the
credit after the executor's reversal. A `requeue` or `reverse` hands the payout back to the executor, so with
`PAYMENT_PAYOUT_EXECUTOR_ENABLED=false` nothing works it, and the single read shows it in its step.

The endpoint creates nothing at Stripe: only the executor's claimed steps create transfers, payouts and reversals, and a
waiting payout is never claimed. `return-without-reversal` and `reverse` decide in this order, and a refusal at any
point writes nothing:

1. The payout must be in `NEEDS_ATTENTION`. A return from `TRANSFERRED` is refused without asking Stripe, because the
   transfer sits in the connected account and a reversal can still bring it back. A `reverse` from `ACCEPTED` is
   refused, because nothing was transferred, and so is one from `REVERSING`, which is already being reversed.
2. A quiet period must have passed since `since`, `PAYMENT_PAYOUT_EXECUTOR_UNKNOWN_BACKOFF` (1 minute by default). The
   payout is handed over right after the last Stripe call of its try, and a request of that try may still be running at
   Stripe, so nothing is decided before the executor's own wait for such a request is over.
3. Stripe is read with no transaction open:
   - A return from `ACCEPTED` lists the transfers in the payout's transfer group and is refused while one is not
     reversed in full. A transfer reversed in full by hand does not block it.
   - A return from `REVERSING` lists the connected account's payouts for the reference over the executor's window, from
     an hour before the transfer, and adds the row's own payout by id when the listing lacks it. It is refused while any
     of them may reach the bank, which is every status but failed and canceled, an unrecognized one included. It then
     reads the transfer and is refused when it is reversed in full.
   - A `reverse` lists the payouts the same way and is refused while any of them may reach the bank.
4. The write is one short transaction that locks the row and applies the move only while the row's `version` is the one
   read before Stripe was asked, so a decision never rests on a row that changed in between.

An answer to a write is `{action, payout, leftInConnectedAccount}`, where `payout` is the payout after the write and
`leftInConnectedAccount` is set only for a return from `REVERSING`.

A refusal is an `application/problem+json` response. The `400` and `403` answers of the request checks carry only
`title`, `status` and `detail`; the others have the shape of [Error responses](#error-responses). The detail is written
by the server, never quotes the request and names only Stripe ids and statuses. The checks run in this order:

| Status | Action | Condition | The detail |
|--------|--------|-----------|------------|
| `400` | all | the path has a path parameter (`;`), an escape (`%`), a doubled slash or a dot segment, refused on every route of Payment Service | Says the path must be plain |
| `403` | all | the request came from a web page, or reached `payouts` under a host other than loopback | Says which |
| `400` | all | the request has a query string or a body | Says the endpoint takes neither |
| `400` | write | the `X-FlowWallet-Operator` header is missing or blank | Names the header |
| `400` | write | `action` is none of the three | Names the three actions |
| `404` | all | no payout has the reference | Says so, without quoting the reference |
| `422` | write | the payout is not in `NEEDS_ATTENTION` | Gives its status, and the reason of a `RETURNED` one; also the answer to a repeated write |
| `422` | return | the payout waits from `TRANSFERRED` | Names the transfer and its account; use `reverse` once no payout of it may reach the bank, or `requeue` |
| `422` | `reverse` | the payout waits from `ACCEPTED` | Nothing was transferred; use `requeue` or `return-without-reversal` |
| `422` | `reverse` | the payout waits from `REVERSING` | It is already being reversed; `requeue` retries the reversal |
| `503` | return, `reverse` | the quiet period has not passed | Gives `since` and the time to retry after |
| `422` | return from `ACCEPTED` | a transfer in the group is not reversed in full | Names it; `requeue` adopts it if it is the only one and has the payout's terms, otherwise reverse it in the Dashboard, then return the payout |
| `422` | `reverse` | exactly one payout of the transfer may reach the bank, and it has the payout's amount and currency | Names it and its status; `requeue`, and the payout step adopts it |
| `422` | return from `REVERSING`, `reverse` | any other payout of the transfer that may reach the bank | Names it and its status; `requeue` once Stripe reports it failed or canceled, and a paid one needs correction by hand |
| `422` | return from `REVERSING` | the transfer is reversed in full | Names it; `requeue`, and the reversal step records it with the payout's own reason |
| `502` | return, `reverse` | Stripe could not be asked | Nothing was changed; retry |
| `503` | return, `reverse` | the payout changed while Stripe was asked | Nothing was changed; read it again |
| `503` | all | the database cannot be reached | The detail of [ADR 0025](adr/0025-unreachable-database-answers-503.md) |
| `500` | all | a defect | "Internal server error" |

On this endpoint `422` means the payout's state or what Stripe holds refuses the action, and the detail names the action
to use instead. `503` means nothing moved and a retry is safe, and `502` means Stripe could not be asked and nothing
moved. `409` is not used, because [ADR 0016](adr/0016-error-model-and-status-codes.md) keeps it for a key already taken.
A `requeue` makes no Stripe call, so it can answer only `404`, `422`, `503` for the database or `500`, and it is the
action that works while Stripe is down.

A response can be lost after the write committed. A repeat then answers `422` with the payout's current status, which
tells what the first call did, so read `GET /actuator/payouts/{reference}` before repeating a write.

A return from `REVERSING` credits the wallet with the full amount while the part of the transfer that no reversal
brought back stays in the connected account. `leftInConnectedAccount` states that part, and it is the platform's loss. A
return from `ACCEPTED` costs nothing, because no transfer holds money.

Which action fits which cause, and the cases that still need correction by hand, are in the
[operator runbook](operations.md#payouts-waiting-for-an-operator).

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
currency in the path is checked by the service instead and comes back with a `detail` only. No detail quotes
the value it refuses: an unknown currency gets "Not an ISO 4217 currency code", and a query parameter of the
wrong type gets "Invalid value for parameter 'before'"
([ADR 0026](adr/0026-problem-details-never-quote-rejected-input.md)). The `type`
field is left out: nothing sets it, and a null field isn't serialised.
The gateway is reactive and doesn't use this handler. Errors it raises itself (`404` for an unrouted path,
`5xx` when a downstream service is unreachable) come in Spring Boot's default WebFlux format.

The status mapping lives in one place, and each status stands for one remedy
([ADR 0016](adr/0016-error-model-and-status-codes.md)):

- `401`: missing or malformed `X-User-Id` (blank, over 64 characters, not a version 4 or 7 UUID).
- `400`: invalid request, unknown provider, non-ISO-4217 currency, a new wallet in a code with no minor unit, missing or
  non-UUID `Idempotency-Key`, a deposit Payment Service refuses (its message passed on, including a currency Stripe
  doesn't charge, an amount below Stripe's minimum, and a payment Stripe refused, whose key is spent), a withdrawal
  Payment Service refuses (its message passed on: a currency it does not pay out in, an amount below the minimum or off
  the payout grid; the key stays free, but a new attempt takes a new one), a transfer `to` that isn't a version 4 or 7
  UUID, a transfer or withdrawal amount off its currency's grid or too large for a balance, a transfer to oneself, a
  bank account request without a bank token or the terms accepted, a bank token Stripe refuses, a missing, malformed or
  invalid webhook signature, or any webhook while no signing secret is configured.
- `404`: wallet not found. Wallet lookups are scoped to the caller, so it is never `403`. On a transfer or a withdrawal
  it always means the caller's own wallet; a recipient without a wallet gets `422`. On the read of a withdrawal it
  also means no such withdrawal in the caller's wallet, with a detail of its own that an unknown reference, another
  wallet's withdrawal and another user's share.
- `406`: a transfer, a deposit, a withdrawal, a wallet opening or a bank account request whose `Accept` header rules out
  JSON. It is refused before anything runs.
- `409`: transaction reference already in use (another user, different terms, or already paid), wallet
  already exists, `Idempotency-Key` reused for a different or completed deposit, for a different transfer or for a
  different withdrawal.
- `413`: a webhook body larger than `PAYMENT_WEBHOOK_MAX_PAYLOAD_SIZE`, refused before its signature is
  checked ([ADR 0017](adr/0017-webhooks-verified-before-they-are-read.md)).
- `422`: a transfer the caller's balance doesn't cover, a recipient without a wallet in the currency, or a withdrawal
  for which no bank account is ready or the balance doesn't cover. The request is well-formed, and the remedy is a
  smaller amount, a top-up, another recipient or a bank account. A transfer's key is unspent. A withdrawal's `422` is
  recorded under its key, which stays spent, so the remedy there is the same plus a new key. On a transfer it is kept
  apart from `409`, whose remedy there is a new key, because without a `type` the status is all a client can branch on.
- `502`: upstream failure, meaning the payment provider failing (not refusing), or Payment Service being unreachable,
  timing out, failing to start the payment, to complete a bank account request or to check a withdrawal, or answering
  unexpectedly. The detail says which. Retrying a deposit or a withdrawal with the same key, or a bank account with the
  same token, is safe. Nothing was debited.
- `503`: a transfer or a withdrawal that lost a lock or a version check (a deadlock, a lock wait that timed out, a
  version conflict), which the lock order is meant to rule out, a withdrawal while the platform's reserve cannot cover
  it, or Payment Service's answer to a deposit whose twin request with the same key is still starting the payment (the
  wallet turns that one into a `502`). In these cases nothing moved. On any endpoint of either service, `503` also
  means the database is unavailable: a transaction that could not begin, a connection lost or ended by Postgres, a
  query timeout. The detail is "Service temporarily unavailable; retry the request, with the same Idempotency-Key if it
  has one", and it does not say whether anything moved, because a connection lost during the commit leaves that
  unknown. Retrying with the same key is safe in every case
  ([ADR 0025](adr/0025-unreachable-database-answers-503.md)).
- `500`: a correctly signed webhook payload that can't be processed, a transfer or withdrawal that broke a database
  CHECK or overflowed a column, or anything unexpected. The detail stays generic; the specifics go to the logs and
  are never returned.

The `payouts` actuator endpoint answers in this format too, and gives `422`, `502` and `503` meanings of their own, so
its statuses are listed in [its section](#operator-the-payouts-actuator-endpoint-8082-directly)
([ADR 0037](adr/0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md)).
