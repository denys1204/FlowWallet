# 0018. An outbox send acts on its row only while it holds the claim, and only a failed send counts as an attempt

- Status: Accepted, extends [0008](0008-transactional-outbox.md)
- Date: 2026-09-27

## Context

In [0008](0008-transactional-outbox.md) a sender claims an `outbox_events` row with a conditional UPDATE, sends
it, and then completes it, schedules a retry or marks it `FAILED`. What the sender does after the claim decides
whether the retry count and the `FAILED` state mean what operators read into them.

- The startup reset returns every `PROCESSING` row to `PENDING`, including rows another instance is still
  sending during a rolling deploy. If the later update matches the row by id alone, the old instance's result
  lands on a row the new instance has already claimed or completed. A failure moves a `COMPLETED` row back to
  `PENDING` (one more copy) or to `FAILED` (a false dead letter and a false alert).
- `KafkaTemplate.send` throws some failures synchronously and raw: `org.apache.kafka.common.KafkaException` when
  the producer cannot be built, `IllegalStateException` for a closed producer, kafka-clients' `InterruptException`.
  A sender that handles only `ExecutionException` and Spring's `KafkaException` leaves such a row in `PROCESSING`
  with `retry_count` unchanged. The reaper returns it without counting, so it never reaches `FAILED` and the
  `outbox.events.failed` gauge stays at 0. A SASL security protocol with an empty `KAFKA_SASL_JAAS_CONFIG` is
  enough to cause it.
- A graceful shutdown interrupts the poller when a send outlasts the shutdown phase, which happens when the
  broker is slow or down. If an interrupted wait counts as an attempt, every restart during an outage pushes rows
  toward `FAILED` without a real failure.

## Decision

The claim's timestamp is the ownership token. `OutboxMessageSender.processEvent` truncates the current time to
microseconds, the precision of `processing_started_at`, and `OutboxEventRepository.claim` writes it to that
column while it moves the row from `PENDING` to `PROCESSING`. `markCompleted`, `scheduleRetry`, `markFailed` and
`releaseClaim` match on the id, `status = PROCESSING` and `processing_started_at` equal to that value, and return
the number of rows they updated. A result of 0 means a reset took the row away: nothing changes, a delivered
send is logged as a warning, and a failure message says the attempt was not recorded. Every exit from
`PROCESSING` (completion, retry, `FAILED`, release, the startup reset and the reaper) clears
`processing_started_at`, so the column is set exactly while a sender holds the claim.

Each transition is a named repository method with its statuses written into the JPQL as enum literals, and no
caller passes a status. `deleteCompletedBefore` can delete only `COMPLETED` rows.

A failed attempt is any exception from building or sending the record, whether `KafkaTemplate.send` throws it or
returns it through its future. The sender reads `retry_count` from the claimed row and either schedules the retry
or, on the last allowed attempt, marks the row `FAILED`; while the claim is held nothing else changes the count.
`error_message` stores the exception's cause chain, each link as `Class: message` joined by ` <- `, from the
wrapper down to the root cause. `markCompleted` runs outside that handling: a database error after the broker
took the record propagates, and the row stays in `PROCESSING` for the reaper, which sends it again.

An interrupt is not an attempt. When the send is interrupted (`InterruptedException`, kafka-clients'
`InterruptException`, or any exception with the thread's interrupt flag set), the sender returns the row to
`PENDING` with `releaseClaim`, counts nothing and restores the flag. If that update fails, the reaper returns
the row. `OutboxPoller.pollOutbox` checks the flag before each claim and stops, and it catches any
`RuntimeException` per row, so one row cannot end the batch. `OutboxEventListener` logs a failure once and does
not rethrow it, because the async executor would only log it a second time.

## Alternatives considered

- A separate claim column holding a random token. It needs a migration and adds nothing: two claims of the same
  row are separated by a reset, so in practice they never share a microsecond.
- A `@Version` column. The transitions are bulk JPQL updates that never load the entity, so each would need the
  version check written by hand, the same work as the timestamp check, plus a migration.
- Matching on the status alone. A row that was reset and claimed again is `PROCESSING` again, and the stale
  sender would still complete or fail it.
- Choosing `FAILED` inside one UPDATE with a `CASE` on `retry_count`. It needs the statuses back as parameters,
  and under the claim the sender already knows the count.
- Counting an interrupt as an attempt. A shutdown says nothing about the event.
- Leaving an interrupted row to the reaper. It works, but every shutdown would delay the row by the whole
  stuck-processing threshold.

## Consequences

- A sender that lost its claim cannot move a row another sender completed back to `PENDING` or to `FAILED`. Its
  own delivered send is still a duplicate with the same `eventId`, which [0008](0008-transactional-outbox.md)
  already expects.
- Every exception from a send counts toward `max-retries`, so a misconfigured producer drives rows to `FAILED`
  and the gauge shows it.
- `error_message` names the root cause, and an operator reads it there before a requeue clears it.
- A shutdown during an outage costs no attempts. The interrupted send may still have reached the broker, which
  gives one more copy with the same `eventId`.
- `processing_started_at` is null outside `PROCESSING`, so it does not record when a completed or failed row was
  last claimed; `processed_at` records the completion.
