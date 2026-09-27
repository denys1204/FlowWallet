# Architecture decision records

An ADR records one decision that spans more than one file, in Michael Nygard's form: Context, Decision,
Alternatives considered and Consequences. The format, numbering and comment convention are themselves
[ADR 0001](0001-record-architecture-decisions.md). An accepted ADR is not rewritten when the decision changes;
a new ADR supersedes it, and the status line of both carries the link. A factual correction that leaves the
decision unchanged, such as a renamed class, is edited in place.

| Number | Title | Status |
| --- | --- | --- |
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-module-boundaries.md) | Services share only a platform module and a wire contract | Accepted |
| [0003](0003-caller-identity-and-trust-boundary.md) | The caller is an opaque X-User-Id taken on trust behind a network boundary | Accepted |
| [0004](0004-wallet-addressed-by-owner-and-currency.md) | A wallet is addressed by owner and currency and opened only on request | Accepted |
| [0005](0005-client-supplied-idempotency-keys.md) | A client-supplied Idempotency-Key binds the terms of one money operation | Accepted |
| [0006](0006-short-transactions-across-bean-boundaries.md) | Transactions are short, database-only and entered across a bean boundary | Accepted |
| [0007](0007-unique-constraints-decide.md) | Unique constraints decide uniqueness, and a violation is handled outside the aborted transaction | Accepted |
| [0008](0008-transactional-outbox.md) | Payment events leave through a transactional outbox with at-least-once delivery | Accepted, extended by [0018](0018-outbox-sends-own-their-claim.md) |
| [0009](0009-payment-event-contract.md) | Payment events evolve additively, are typed by header, deduplicated on eventId and need no ordering | Accepted |
| [0010](0010-idempotent-payment-event-consumer.md) | The wallet consumer credits each payment once and sends every failure to a durable place | Accepted, extended by [0019](0019-payment-event-amounts-on-the-grid.md) and [0020](0020-wallet-dead-letters-kept-and-counted.md) |
| [0011](0011-wallet-row-locking.md) | Every balance write locks the wallet row, in a fixed order, with nothing read first | Accepted |
| [0012](0012-balances-and-append-only-ledger.md) | A balance never goes negative, and every movement is an append-only ledger row keyed by reference and type | Accepted, history paging superseded by [0021](0021-per-wallet-ledger-entry-numbers.md) |
| [0013](0013-deposit-initiation.md) | A deposit starts in Wallet Service and is reserved in Payment Service before Stripe is called | Accepted |
| [0014](0014-transfers-in-one-local-transaction.md) | A transfer is one local transaction in wallet_db with a fixed decision order | Accepted |
| [0015](0015-currency-precision-and-no-rounding.md) | Amounts sit on an explicit per-currency grid and are refused, never rounded | Accepted, extended by [0019](0019-payment-event-amounts-on-the-grid.md) |
| [0016](0016-error-model-and-status-codes.md) | Errors are RFC 9457 problems whose status tells the client what to do | Accepted, extended by [0017](0017-webhooks-verified-before-they-are-read.md) |
| [0017](0017-webhooks-verified-before-they-are-read.md) | Webhooks are bounded and verified before they are read, and refused without a signing secret | Accepted, extends [0016](0016-error-model-and-status-codes.md) |
| [0018](0018-outbox-sends-own-their-claim.md) | An outbox send acts on its row only while it holds the claim, and only a failed send counts as an attempt | Accepted, extends [0008](0008-transactional-outbox.md) |
| [0019](0019-payment-event-amounts-on-the-grid.md) | Payment event amounts sit on the wallet's grid, and a refusal stores only an amount its column holds | Accepted, extends [0010](0010-idempotent-payment-event-consumer.md) and [0015](0015-currency-precision-and-no-rounding.md) |
| [0020](0020-wallet-dead-letters-kept-and-counted.md) | The wallet's dead-letter topic keeps its records without a time limit, and every dead letter is counted | Accepted, extends [0010](0010-idempotent-payment-event-consumer.md) |
| [0021](0021-per-wallet-ledger-entry-numbers.md) | Each wallet numbers its ledger entries under its row lock, and history is ordered and paged by that number | Accepted, supersedes the history paging of [0012](0012-balances-and-append-only-ledger.md) |
