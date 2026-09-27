# 0004. A wallet is addressed by owner and currency and opened only on request

- Status: Accepted
- Date: 2026-09-05

## Context

Wallet Service keeps one balance per user and currency. Every request names its owner in `X-User-Id`, which
`@CurrentUserId` resolves and the service takes on trust ([0003](0003-caller-identity-and-trust-boundary.md)).
Payment Service holds no wallet data. It charges a user in a currency and publishes events that carry `userId`
and `currency`. A client knows about a wallet only what Wallet Service returns to it.

A second identifier for a wallet would need two things: a source the client can learn it from truthfully, and a
place that checks it against the owner and the currency. Payment Service cannot run that check. The consumer of
its events could only reject a mismatch after the customer has paid.

## Decision

- A wallet is identified by `(userId, currency)`. `wallets` is unique on `(user_id, currency)`, so a user holds
  at most one wallet per currency. The currency never changes for the life of the wallet, and nothing converts
  between currencies.
- The owner comes from `X-User-Id` and the currency from the path, `/api/wallets/{currency}`. The one exception
  is opening a wallet, where `CreateWalletRequest` carries the currency and nothing else.
- `wallets.id` is internal. It appears in no URL, in no response (`WalletResponse`, `DepositResponse`,
  `TransferResponse`, history items), in neither payment event and in no payment request. The ledger names the
  other side of a transfer by `counterpartyUserId`.
- No request body carries an owner. `DepositRequest` and `TransferRequest` carry no currency either, because the
  wallet named in the path fixes it, and in a transfer that one currency covers both sides.
- The service looks up the caller's wallet only with the caller's id as a query parameter
  (`findByUserIdAndCurrency`, `findByUserIdOrderByCurrency`, `lockByUserIdAndCurrency`). No code reads a wallet
  more widely and checks ownership afterwards. As a result, a wallet that belongs to someone else and a wallet
  that does not exist give the same answer: `404` (`WalletNotFoundException`), never `403`. The only other wallet
  a request touches is a transfer recipient's. It is locked by the recipient's user id, and nothing from it
  appears in the sender's receipt ([0014](0014-transfers-in-one-local-transaction.md)).
- `GET /api/wallets` returns `200` with the caller's wallets ordered by currency, or an empty list if there are
  none. URLs name currencies, so this list is how a client finds out which ones it holds.
- Only `POST /api/wallets` opens a wallet. It returns `201`, or `409` (`WalletAlreadyExistsException`) when the
  caller already has a wallet in that currency. The unique constraint settles concurrent opens
  ([0007](0007-unique-constraints-decide.md)). A payment event or a transfer never creates a wallet. A deposit
  into a missing wallet gets `404` before anything is charged ([0013](0013-deposit-initiation.md)). A transfer to
  a user without a wallet in the currency gets `422` ([0014](0014-transfers-in-one-local-transaction.md)). A
  `PaymentCompletedEvent` for a missing wallet is stored as `REJECTED` with its payload
  ([0010](0010-idempotent-payment-event-consumer.md)).

## Alternatives considered

- A wallet id in the path. The caller has no truthful way to learn this second name for the wallet, and
  ownership turns into a check after the load instead of part of the query.
- A client-chosen wallet id in the payment request or the events. Payment Service cannot check that the id
  exists, belongs to the caller or matches the currency being charged, so the field would be the one place in
  the system where a wrong destination could be written down.
- A wallet id or owner in `WalletResponse`. No URL accepts the id, so publishing it invites clients to build
  paths that do not exist. The owner would only echo the caller's own header back.
- A currency field in deposit or transfer bodies. The body and the path could then disagree, and a transfer
  between currencies would become expressible.
- An owner in the create-wallet body. A caller could then open a wallet for somebody else.
- `403` for another user's wallet. It needs a wider query first, and it turns the endpoint into an oracle for
  other people's wallets.
- Loading the wallet and then checking ownership. The wider read stays in the code, and a later change can
  forget to narrow it.
- Opening a wallet as a side effect of the first payment or transfer. Before paying, the caller could not tell
  whether a wallet would exist afterwards, so the API would stop being predictable.
- `404` for a user who holds no wallets. The caller exists and holds nothing yet, so an empty list is the
  correct answer.

## Consequences

- No handler can forget an ownership check, because the owner is a parameter of every query that finds the
  caller's wallet.
- A movement cannot land in a wallet with another owner or another currency. `RejectionReason` therefore has no
  foreign-owner or currency-mismatch value, only `INVALID_AMOUNT`, `INVALID_ENVELOPE`, `WALLET_NOT_FOUND` and
  `DUPLICATE_REFERENCE`.
- A client has to open a wallet before its first deposit. A `WALLET_NOT_FOUND` rejection means a payment started
  by some other route, or a wallet that has disappeared. The payload is kept so that the event can be
  republished once the wallet exists, under a fresh `eventId`, because the `REJECTED` row holds the original one
  ([0010](0010-idempotent-payment-event-consumer.md)).
- The unique constraint alone treats `usd` and `USD` as different values. `Currencies.normalise` upper-cases the
  path and the body before any lookup, `Wallet.open` stores the code it returns, and the `wallets_currency_is_upper`
  CHECK enforces the rule in the schema ([0015](0015-currency-precision-and-no-rounding.md)).
- On a transfer, `404` always refers to the caller's own wallet and never to the recipient's
  ([0016](0016-error-model-and-status-codes.md)).
- The internal id is the primary key, and it appears as `balance_history.wallet_id` and in log lines.
- A user who wants a second currency opens a second wallet.
