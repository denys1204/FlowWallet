# 0010. The wallet consumer credits each payment once and sends every failure to a durable place

- Status: Accepted, extended by [0019](0019-payment-event-amounts-on-the-grid.md) and
  [0020](0020-wallet-dead-letters-kept-and-counted.md)
- Date: 2026-09-05

## Context

Payment Service publishes to `payment.events` at least once ([0008](0008-transactional-outbox.md)), so Wallet
Service receives some events more than once. A second credit for one payment creates money, and a lost credit drops
a payment the customer was charged for. Each record has to end in a durable place: a credit, a recorded failure, a
stored refusal or the dead-letter topic. Events are typed by the `eventType` header and identified by `eventId`
([0009](0009-payment-event-contract.md)). `PaymentCompletedEvent` and `PaymentFailedEvent` carry no validation, so a
null or negative amount can arrive. `payment.events` has three partitions keyed by `transactionReference`, so a
record that blocks its partition holds up about a third of all credits.

## Decision

- `PaymentEventHandler.credit` writes two unique barriers in the transaction that changes the balance.
  `processed_events.event_id` gets a row for every event the listener settles (`CREDITED`, `FAILURE_RECORDED`,
  `REJECTED`) and stops a redelivery. `balance_history (transaction_reference, type)` allows one `DEPOSIT` per
  reference and stops a second, different event for a credited payment, which is a producer defect.
  `transaction_reference` is NOT NULL because Postgres treats NULLs as distinct under a unique index. The ledger key
  is in [0012](0012-balances-and-append-only-ledger.md).
- `PaymentEventHandler` catches nothing. `PaymentEventListener` is not transactional. It catches
  `DataIntegrityViolationException` after the rollback and asks `PaymentEventOutcomeStore.classify`, a separate bean
  with a fresh read-only transaction, which barrier fired ([0006](0006-short-transactions-across-bean-boundaries.md),
  [0007](0007-unique-constraints-decide.md)). A `processed_events` row under the event id is
  `EVENT_ALREADY_PROCESSED`, and the record is acknowledged. A `DEPOSIT` row under the reference is
  `REFERENCE_ALREADY_CREDITED`, and the event is stored as `REJECTED` with `DUPLICATE_REFERENCE`. Anything else is
  `NOT_A_DUPLICATE`: the violation is rethrown, retried and dead-lettered. When recording a failure event or a
  refusal hits a violation, the listener acknowledges only `EVENT_ALREADY_PROCESSED`.
- Before any transaction opens, the listener checks a completed event. A blank `transactionReference`, `currency` or
  `userId` is `INVALID_ENVELOPE`, and a null, zero or negative amount is `INVALID_AMOUNT`. `Wallet.credit` relies on
  this check and does not repeat it.
- `PaymentEventOutcomeStore.recordRejection` stores refusals the wallet understands (`INVALID_AMOUNT`,
  `INVALID_ENVELOPE`, `WALLET_NOT_FOUND`, `DUPLICATE_REFERENCE`) as `REJECTED` rows with the reason and the full
  payload, and the offset is committed. Replay is manual, and a refused event is republished under a fresh `eventId`,
  because the `REJECTED` row holds the original one and an unchanged payload is acknowledged as already processed. The
  `DEPOSIT` key on `balance_history` still stops a second credit. `UnknownWalletException` is thrown inside the money
  transaction, so the `processed_events` row flushed before the wallet lookup rolls back and the `REJECTED` row can take
  the event id. An event never opens a wallet ([0004](0004-wallet-addressed-by-owner-and-currency.md)).
- An unreadable record (missing or unknown `eventType` header, unparseable JSON, no value or the JSON literal `null`, no
  `eventId`) raises `UnreadablePaymentEventException`. It is registered as not retryable and goes to
  `payment.events.wallet.DLT` at once: without an event id there is nothing to write a row under.
- The container's `DefaultErrorHandler` retries any other failure with an `ExponentialBackOff`
  (`wallet.consumer.retry.*`; by default 3 retries from 500 ms, doubling, capped at 10,000 ms), then a
  `DeadLetterPublishingRecoverer` publishes it to `payment.events.wallet.DLT`. It is the wallet's only retry mechanism.
- `KafkaConsumerConfig` declares the dead-letter topic as a `NewTopic` bean. The recoverer publishes with partition
  -1, so the producer's partitioner picks one, and the producer uses `acks=all` with idempotence. The topic is the
  wallet's own, apart from Payment Service's dead-letter store, the `FAILED` outbox rows.
- `balance_history.event_id` is nullable and records which event made a credit. Classification does not read it.

## Alternatives considered

- Only the `event_id` barrier: a second event under a different id for the same payment credits it twice.
- A nullable `transaction_reference`: the ledger barrier is inert for exactly the malformed events it exists to stop.
- The barrier row, balance and ledger in separate steps: the states in between need a sweeper or reconciliation job.
- A read before the insert: rejected for the reasons in [0007](0007-unique-constraints-decide.md).
- Recovering inside the money transaction: Postgres refuses every further statement in it after a violation.
- A `@Transactional` listener: the handler's transaction joins it, and the classifying reads run on the aborted one.
- `classify` and `recordRejection` on `PaymentEventHandler`: a self-invocation bypasses the transaction proxy.
- Reading the exception or constraint names: it needs the schema's naming, breaks on a rename and gives no definite
  answer when neither barrier fired.
- Treating any violation as a duplicate: an unrelated one, such as a CHECK constraint or a value too long for its
  column, would acknowledge a payment that was never credited.
- Validation annotations on the contract records: the contract module has no dependencies on purpose
  ([0002](0002-module-boundaries.md)), and `ObjectMapper.readValue` would not apply them.
- The container's default recoverer: it logs the record and moves on, so a payment silently does not arrive.
- Pausing or deferring a failed record: one poison record stalls its partition.
- Dead-lettering refusals, including a redelivered one: the topic for records the wallet could not read or settle
  would fill with records it read and declined on purpose.
- Refusals without the payload: replay would depend on Kafka retention or on Payment Service reissuing the event.
- Sharing Payment Service's dead-letter store or topic: it holds outbox rows under a different contract, the wallet's
  holds failed consumer records, and mixing the two makes both unreadable.
- Broker auto-creation of the dead-letter topic: it is off on production brokers, so the publish fails and the
  record is redelivered without end.
- Publishing to the record's original partition: the recoverer then checks before every publish that the partition
  exists and falls back to the producer's choice when the dead-letter topic has fewer partitions, so pinning buys
  nothing.
- Retrying unreadable records: every attempt fails the same way, so retries only delay the dead-letter.
- Spring Retry in the wallet: two retry budgets would disagree about how many attempts remain.

## Consequences

- A redelivery, including one of a refused or failed event, is acknowledged and changes nothing.
- Every readable event the listener settles has a `processed_events` row. `REJECTED` rows keep their payload, and
  nothing replays them automatically. A second event for a credited reference is also logged at error level.
- `payment.events.wallet.DLT` has no consumer; its records are inspected and replayed by hand. Unreadable and
  exhausted records leave no `processed_events` row, so a dead-lettered record can be republished unchanged.
- A failure the retries cannot fix holds its partition only for the length of the backoff.
- `classify` knows only the two barriers, so a violation of any other constraint on the credit path is retried and
  dead-lettered, never acknowledged. A further barrier needs its own case in `classify`.
- `Wallet.credit` takes its amount on trust, so any other caller must validate the amount first.
