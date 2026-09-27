# 0005. A client-supplied Idempotency-Key binds the terms of one money operation

- Status: Accepted, extended by [0022](0022-stripe-charge-rules-checked-before-the-reservation.md) and
  [0025](0025-unreachable-database-answers-503.md), concurrent-reservation consequence superseded by
  [0024](0024-deposit-initiation-settles-its-own-races.md)
- Date: 2026-07-25

## Context

Two endpoints move money. `POST /api/wallets/{currency}/deposits` asks Payment Service for a Stripe PaymentIntent,
and `POST /api/wallets/{currency}/transfers` moves money between two wallets in `wallet_db`. A client that loses the
response cannot tell whether the operation happened, and its only way to find out is to send the request again.
Unless the server recognises that retry, the card is charged twice or the money moves twice. Stripe deduplicates on
an idempotency key of its own, and every ledger movement needs a reference, so one identifier can do all three jobs.

## Decision

Both endpoints require an `Idempotency-Key` header, and the server never generates one. The key is a UUID of any
version from 1 to 8, not nil, in either case: `@UUID` with every version listed on the deposit, and on the transfer
the ASCII `ANY_UUID` pattern, which accepts the same versions and variants, for a reason given at that constant. The
deposit's `@UUID` also lets non-ASCII digits through. A key has to be unique, not unguessable. The rules for user
ids, and the limits of `@UUID`, are in [0003](0003-caller-identity-and-trust-boundary.md).

`DepositService.start` and `TransferService.transfer` lower-case the key once, with `Locale.ROOT`, and after that it
is used verbatim as the `transactionReference`. The same string is the reference in `CreatePaymentIntentCommand`,
Stripe's idempotency key in `StripePaymentStrategy.initiatePayment`, the reference on the payment events, and the
ledger reference of a deposit and of both transfer legs.

A key binds the terms it was first used with:

- A deposit binds owner, amount, currency and provider. `PaymentTransactionStore.findOwnedBy` refuses a reference
  that another user owns and never returns it. `PaymentTransaction.differencesFrom` compares the amount by
  `compareTo`, the currency exactly (`@Iso4217Currency` admits only upper case) and the provider name ignoring
  case. `create` stores the name of the `PaymentProvider` constant that `PaymentProviderFactory.resolve` returned.
- A transfer binds the sender wallet, which also fixes the currency, plus the recipient user and the amount by
  `compareTo`. `BalanceHistory.isRepeatOf` checks these against the key's `TRANSFER_OUT` row. How the handler judges
  the key under its locks is in [0014](0014-transfers-in-one-local-transaction.md).

A request with the same key and the same terms gets the original answer: `200` with a byte-identical body. That is
why `DepositResponse` and `TransferResponse` carry no timestamp, request id or movement id, and why a transfer
answers `200` the first time as well. Any other use of the key gets one `409` (`ConflictingDepositException` or
`ConflictingTransferException`, listed with the other statuses in [0016](0016-error-model-and-status-codes.md)). It
has no problem type, and its detail names no cause and repeats no stored value. The wallet turns every `409` from
Payment Service into that exception without reading the text. Payment Service's own detail lists which terms differ,
never their values, and only the wallet sees it, because the gateway routes only webhooks to Payment Service.

A deposit whose payment is `SUCCESS` (`isSettled`) answers `409`, because a replay would hand back a client secret
that has already been spent. A `FAILED` payment does not count as settled: after a declined card the provider's
intent can still be used, so a retry gets it back. A transfer is finished by the time it answers, so its replay
returns the receipt even after the balance is spent. For deposits, Payment Service's row is the only record of the
binding, and the wallet keeps no copy. A refused request writes nothing, so it consumes no key. Retrying with the same
key after a `502` or `503` is safe.

## Alternatives considered

- An optional key. A client that forgets it loses idempotency without being told.
- A server-generated key. After a lost response the retry gets a fresh key, and the card is charged twice.
- A Stripe idempotency key unrelated to the reference. A retry that reaches Stripe again could create a second
  intent, and reusing a reserved row would no longer be safe.
- Key-only idempotency. A client that corrects the amount and retries under the old key gets the original intent and
  a `200`, and pays the old amount. Stripe would refuse the mismatch, but a local replay never reaches Stripe.
- `422` for a key reused with other terms. The request is valid: the same body under an unused key is accepted.
- `BigDecimal.equals` for amounts. `50.00` and a stored `50.0000` from `NUMERIC(19,4)` would not match unless every
  caller rescaled first.
- Comparing `providerName` case-sensitively. A byte-identical retry that sends `stripe` would get a conflict.
- Comparing a transfer's sender by user id. One key used from a USD wallet and then an EUR wallet would count as one
  transfer.
- A separate status or problem type for each cause of a `409`, or a wallet that tells deposit causes apart by
  matching Payment Service's message text. The remedy is a new key in every case, a transfer's cause can describe
  another user's transfer, and text matching breaks as soon as the wording changes.
- Stored values in the conflict detail. The message goes to the caller.
- Replaying a completed deposit with `200`. The client would get a spent credential with a success status and learn
  from Stripe what this service already knew.
- Treating `FAILED` as settled. That refuses a retry the provider would accept.
- A timestamp or request id in the response. It breaks byte-identical replays for no benefit, and a `createdAt`
  would also have to come back from the database's timestamp column unchanged.
- Only UUID versions 4 or 7, as for user ids. That refuses reasonable keys, such as a version-5 key derived from an
  order number, and gains nothing.
- `@UUID`'s default versions, 1 to 5. They refuse the version-7 keys that many client libraries generate by default.
- A wallet-side copy of the deposit binding. It gives the two services a way to disagree and adds no guarantee, and
  the only thing worth caching, the client secret, is a bearer credential that does not belong in a second database.

## Consequences

- Clients generate one key per operation and keep it across retries. Transfer keys should be random: the recipient
  sees the key in its history, and a key derived from something guessable lets the recipient predict the sender's
  next key and use it first.
- Keys are global, so a transfer `409` still tells any caller that the key has been used for some transfer.
- A refused transfer (`400`, `404`, `406`, `422`, `503`) leaves its key free, so a transfer refused for low funds can
  go through later under the same key after a top-up. Clients should treat a 4xx as the final answer for that intent,
  because retrying a `422` automatically can move money long after the user stopped expecting it.
- A concurrent deposit that loses the race to reserve the reference gets `409`, and its retry is answered from the
  winning row ([0013](0013-deposit-initiation.md)).
- One key can name both a deposit and a transfer, and each stays protected, because the ledger keys rows by
  reference and type ([0012](0012-balances-and-append-only-ledger.md)).
- A transfer replay's `balanceAfter` is the sender's balance right after that transfer, not the current one.
