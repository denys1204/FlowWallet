# 0003. The caller is an opaque X-User-Id taken on trust behind a network boundary

- Status: Accepted, extended by [0027](0027-user-ids-stay-out-of-logs-and-provider-metadata.md) and
  [0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md), intended authentication model superseded by
  [0031](0031-callers-are-authenticated-in-front-of-the-gateway.md)
- Date: 2026-07-03

## Context

Every wallet and payment endpoint acts for one user, and the services need that user's id before they touch money.
Authentication is not implemented. The gateway routes requests without filters, so whatever a client sends reaches
the services. A user id also arrives as data, as the recipient (`to`) of a transfer, and it is stored in `VARCHAR(64)`
columns (`wallets.user_id`, `payment_transactions.user_id`, `balance_history.counterparty_user_id`). No service keeps
a table of users.

## Decision

- The caller's identity is the `X-User-Id` header (`HttpHeaders.USER_ID`), read by `CurrentUserIdResolver` for
  parameters annotated `@CurrentUserId`. `CurrentUserIdAutoConfiguration` registers the resolver in servlet services
  only, so Wallet Service and Payment Service read the header and the reactive gateway does not.
- An identity is opaque and is not derived from anything knowable. It must be a random-based UUID: version 4 or 7,
  RFC 4122 variant. Versions 3 and 5 are hashes of a name in a namespace, so anyone who suspects an id is
  `uuid5(namespace, e-mail)` can compute it and confirm the guess. Version 1 embeds a MAC address and a creation time.
  All three pass a naive UUID check. The nil UUID fails on its version digit.
- The resolver strips the value and lower-cases it with `Locale.ROOT`, so one identity has one spelling and cannot
  become two users with two balances. `MAX_USER_ID_LENGTH` (64) bounds the stripped value at the width of every user
  id column. A UUID never gets near it. The bound is a storage guard, kept so that relaxing the identity rule later
  does not remove it as a side effect.
- Every unusable identity is one `MissingUserIdException`, a 401: no servlet request, a missing or blank header, a
  value over 64 characters, or anything that is not a version 4 or 7 UUID. The message never echoes the rejected
  value, because `GlobalExceptionHandler` returns it as the problem detail and logs it.
- The check is a hand-written ASCII expression, `CurrentUserIdResolver.RANDOM_UUID_REGEX`. An argument resolver runs
  before bean validation and is constructed by hand, so no constraint annotation reaches it. The constant is public,
  and `TransferRequest.to` applies it through `@Pattern`, so the caller and the recipient follow one rule.
- UUID-shaped input is checked with an ASCII `@Pattern`, not Hibernate Validator's `@UUID`. In Hibernate Validator 9.1
  that validator throws on a 36-character value with a fifth dash, which the platform's last-resort handler answers
  with a 500. It also reads digits with `Character.digit` and so accepts non-ASCII digits the resolver refuses. The
  idempotency keys of both money endpoints use the pattern `TransferController.ANY_UUID`, and the key's version
  policy is in [0005](0005-client-supplied-idempotency-keys.md).
- Services take the header on trust. Nothing authenticates it and the gateway forwards the client's value unchanged,
  so the header is the only credential. The intended model is that the gateway validates a token and sets
  `X-User-Id`, and that services are reachable only through the gateway, plus Wallet Service calling Payment Service
  directly and forwarding `X-User-Id` when it starts a payment. Nothing enforces that model yet. The identity rule
  stays after authentication arrives.
- There is no user search or registry. A transfer names its recipient by user id only, and a user learns someone's
  id because that person shared it.

## Alternatives considered

- Accepting any UUID version. Rejected: versions 1, 3 and 5 would pass, with the costs above.
- Accepting any opaque string, such as a name or an e-mail. Rejected: such an id is known to people the user never
  shared it with, and while the header is unauthenticated, knowing an id is enough to act as that user.
- Rejecting upper-case input instead of folding it. Rejected: refusing a valid identity over capitalisation is a
  needless outage, and folding gives one spelling anyway.
- Dropping the version rule once the gateway authenticates. Rejected: knowing an id then stops being worth anything
  and the rule is only hygiene, but it costs one expression, and a change to the identity scheme should show up as
  refused requests.
- A distinct status per identity fault, such as 400 for a malformed id. Rejected: the caller cannot proceed in any
  of these cases, and separate statuses would give it nothing to act on differently.
- Echoing the received value in the problem detail. Rejected: the detail goes back to the caller and into the logs.
- Hibernate Validator's `@UUID` for the header or the recipient. Rejected: no annotation reaches an argument
  resolver, and on the recipient the validator's faults described above apply.
- `UUID.fromString` as a validator. Rejected: it accepts `1-1-1-1-1` and invents a UUID from it.
- A separate recipient pattern in the wallet module. Rejected: two copies of the rule can drift apart, and an id no
  caller can have can never own a wallet.
- Validating a JWT at the gateway. Deferred and not implemented; clients pass `X-User-Id` directly until it lands.
- Looking a recipient up by name or e-mail, or keeping a user registry in the wallet. Rejected: either would make
  the wallet a directory of who holds a wallet.

## Consequences

- While the header is unauthenticated, anyone who can reach Wallet Service and knows a user's id can spend that
  user's balance through a transfer. Only the network boundary prevents this, and deployment has to provide it: in a
  cluster, neither Wallet Service nor Payment Service gets a public route.
- Each side of a transfer sees the other's user id, and the recipient's `TRANSFER_IN` entry also shows the sender's
  `Idempotency-Key`. Why transfer keys should therefore be random is in
  [0005](0005-client-supplied-idempotency-keys.md), and what a sender can learn about a recipient is in
  [0014](0014-transfers-in-one-local-transaction.md).
- A malformed `to` is a 400, not a 401, because it belongs to the request rather than to the caller's identity, and
  it is refused before any query. Unlike the header, `to` with surrounding whitespace is refused rather than
  stripped. `TransferService` lower-cases it before the lookup.
- Changing the identity rule means changing `RANDOM_UUID_REGEX`, which changes the header check and the recipient
  check together. `CurrentUserIdResolverTest` and `TransferControllerTest` pin both rules, and
  `UserIdColumnWidthTest` ties Payment Service's `user_id` column to `MAX_USER_ID_LENGTH`.
- Caller-scoped wallet reads and their 404 are in [0004](0004-wallet-addressed-by-owner-and-currency.md). The full
  status table is in [0016](0016-error-model-and-status-codes.md).
