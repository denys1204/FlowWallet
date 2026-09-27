# 0008. Payment events leave through a transactional outbox with at-least-once delivery

- Status: Accepted
- Date: 2026-07-03

## Context

Payment Service learns a payment's outcome from a Stripe webhook, and Wallet Service credits the balance from an event
on `payment.events`. The status change lives in `payment_db` and the event in Kafka, and no transaction spans both. An
event sent before the commit can announce a change that then rolls back. An event sent after the commit is lost if the
process crashes or the broker is down in between, and for a completed payment a lost event is a credit that never
happens. A send also blocks on the broker, and a blocking network call does not belong inside a database transaction
([0006](0006-short-transactions-across-bean-boundaries.md)).

## Decision

Business logic never publishes to Kafka. `PaymentTransactionHandler.handleSuccess` and `handleFailure` are
`@Transactional`, and inside that transaction `PaymentOutboxService` serialises the event and saves an `OutboxEvent` row
to `outbox_events`, so the row exists if and only if the status change commits. The payload is stored as the final JSON
string: the `eventId` is fixed when the row is written, and every send of the row carries it. `aggregate_id` holds the
`transactionReference`, which becomes the record key.

`OutboxEventListener` is `@Async @TransactionalEventListener(phase = AFTER_COMMIT)` and sends the row as soon as the
transaction commits. `OutboxPoller.pollOutbox` runs every `outbox.poll-interval-ms` and sends, oldest first and at
most `outbox.batch-size` per run, the `PENDING` rows whose `next_attempt_at` is null or past: rows the fast path missed
and rows due for a retry.

`OutboxMessageSender.processEvent` claims a row with `lockForProcessing`, a conditional UPDATE from `PENDING` to
`PROCESSING` that also sets `processing_started_at` and runs in its own `REQUIRES_NEW` transaction. The sender whose
UPDATE hits the row owns it; any other sender sees 0 rows and skips. No row lock is held and no transaction is open
during the send, and a successful send ends with `markAsCompleted`. A failed send goes to `incrementRetryOrFail`, which
increments `retry_count`, stores the whole error in `error_message` (TEXT, never truncated) and sets `next_attempt_at`
to now plus `retry-backoff-base-ms * 2^retryCount` (the count before this failure), capped at `retry-backoff-max-ms`
with overflow clamped to the cap. When the count reaches `max-retries`, the same UPDATE marks the row `FAILED`.

A sender that dies mid-send leaves its row in `PROCESSING`. On `ApplicationReadyEvent`, `resetStuckEvents` returns every
such row to `PENDING` with no age threshold. At runtime `reapStuckProcessing` returns rows whose `processing_started_at`
is older than `stuck-processing-threshold-ms`. That threshold has to stay well above the longest single send, which the
producer's `max.block.ms` and `delivery.timeout.ms` bound. The poller logs a failing row and moves on to the next, so
send order per key is not guaranteed; consumers do not need it ([0009](0009-payment-event-contract.md)).

`FAILED` rows are the durable dead-letter store and are never deleted by age: `cleanupOldEvents` deletes only
`COMPLETED` rows older than `retention-days`. `OutboxEndpoint` (`/actuator/outbox` on Payment Service's own port, not
routed through the gateway) counts `FAILED` rows on GET, and on POST returns them to `PENDING` with `retry_count`,
`error_message`, `next_attempt_at` and `processing_started_at` cleared. The `outbox.events.failed` gauge is the alert
signal. Payment Service has no Kafka dead-letter topic. Delivery is at-least-once, so every consumer must be
idempotent; the wallet deduplicates on `eventId` ([0010](0010-idempotent-payment-event-consumer.md)).

## Alternatives considered

- Publishing straight from business logic (a dual write). With no shared transaction, either order can lose an event
  or announce a change that never committed.
- Claiming with row locks. A lock lasts only as long as its transaction, so it would either keep a transaction open
  across the Kafka send or still need a status marker after the lock is released. The status column is that marker,
  and one conditional UPDATE sets it.
- Reaping at startup with the runtime threshold. Every recovery after a crash would wait out that threshold, while the
  unconditional reset costs one extra copy of a row that is still in flight.
- A short stuck-processing threshold. The reaper would reset rows a live instance is still publishing, and those rows
  would go out twice as a matter of routine.
- Stopping the batch, or the key, at the first failure to keep per-key order. One failing event would block delivery
  of unrelated transactions' events.
- Relying on the partition key for ordering. The key keeps sent messages in order. It cannot order a message that is
  still waiting out its backoff.
- Deleting `FAILED` rows with the retention cleanup. For a completed payment the row is the only record of a credit
  that never happened, and deleting it would make the money disappear without a trace.
- A payment-side Kafka dead-letter topic. A send fails almost only when the broker is unreachable, and a publish to
  another topic on the same broker fails at the same moment.
- Truncating `error_message`. The column is TEXT, so a cap would only throw away diagnostic detail.

## Consequences

- A committed status change always has its event, and a rolled-back one never does.
- Duplicates are expected. They come from a crash between the send and `markAsCompleted`, or a `markAsCompleted` that
  throws and leaves the row to the reaper; from a send the producer reports as failed after the broker already wrote
  the record; from the startup reset, which also returns rows still in flight, whether another instance is sending them
  in a rolling deploy or this instance started them before `ApplicationReadyEvent` (scheduling and the web server start
  earlier); and from a reaper threshold set too low. Every copy carries the same `eventId`.
- A broker outage that outlasts `max-retries` attempts leaves rows `FAILED`, and nothing retries them until an operator
  requeues. The requeue clears `error_message`, so the cause has to be read from `outbox_events` first.
- The table keeps `COMPLETED` rows for the retention window and every `FAILED` row, and the `FAILED` rows grow only as
  fast as sends fail permanently.
- The actuator endpoint has no authentication of its own and is exactly as protected as Payment Service's port
  ([0003](0003-caller-identity-and-trust-boundary.md)).
