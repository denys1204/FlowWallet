# 0044. Unreadable Kafka records are reported without their content, and failed sends without their value

- Status: Accepted, extends [0027](0027-user-ids-stay-out-of-logs-and-provider-metadata.md)
- Date: 2026-10-11

## Context

[ADR 0027](0027-user-ids-stay-out-of-logs-and-provider-metadata.md) keeps user ids out of log lines and exception
messages. Every payment event and withdrawal command carries one, and so does nearly every dead letter. The Kafka paths
let it through in two places and left room for a third.

A record that Jackson could not parse became an `UnreadablePaymentEventException` or an
`UnreadableWithdrawalCommandException` with the `JacksonException` as its cause. The retry listeners log the most
specific cause ([ADR 0020](0020-wallet-dead-letters-kept-and-counted.md),
[ADR 0034](0034-shared-outbox-and-the-wallet-withdrawals-command.md)), which was Jackson's exception, and Jackson's
message quotes the input: a whole string value it could not convert, or the leading identifier of an unquoted token. The
dead-letter publisher writes the exception's message into `kafka_dlt-exception-message` and its stack trace, whose
`Caused by` line carried Jackson's message, into `kafka_dlt-exception-stacktrace`. The wallet's message for an unknown
event type quoted the header's value.

Spring Boot gives the auto-configured `KafkaTemplate` a `LoggingProducerListener`, which logs the key and the first 100
characters of the value of a record that fails to send, whether an outbox event or a dead letter. In the records the
services write, `userId` comes after those 100 characters, but a dead letter can have any shape and the field order of
a record can change.

## Decision

- A consumer reports a record it cannot parse by the parser's error type and position only, such as
  `InvalidFormatException at line 1, column 63`, and does not keep the `JacksonException` as the cause.
  `UnreadablePaymentEventException.unparseable` and `UnreadableWithdrawalCommandException.unparseable` build that
  message; each service keeps its own copy, since the services share no code beyond the libraries
  ([ADR 0002](0002-module-boundaries.md)).
- No exception on the consumer paths quotes a user id, the raw value, a header value the consumer does not recognise or
  Jackson's message. An unknown event type is reported without the header's value. The `transactionReference`, the event
  id and a recognised event type stay, as ADR 0027 allows.
- The retry listeners keep printing the most specific cause, as ADR 0020 and ADR 0034 have them; the rule sits with the
  exceptions that reach them.
- Each service declares a `LoggingProducerListener` with `includeContents` off (`producerListenerWithoutContents`),
  which Spring Boot's own listener backs off for, so a failed send logs the topic and the exception.
- The dead letter's value stays as it came, kept without a time limit; an operator reads the input there.

## Alternatives considered

- Logging only the exception's class in the retry listeners: the headers and the stack trace would still quote the
  record, and messages that are safe and useful, such as a constraint name or an incomplete envelope, would be lost.
- A log pattern that masks UUIDs: it cannot tell a user id from an event id or a reference, which are UUIDs as well.
- `KafkaUtils.setProducerRecordFormatter`: a static setting for the whole JVM.
- Dropping the value from the dead letter: an operator needs it to repair or republish the record.

## Consequences

- An operator sees the error type and position in the log line and in `kafka_dlt-exception-message`, and reads the
  record itself on the dead-letter topic.
- An exception thrown on a consumer path must not quote a user id, the raw value, an unrecognised header value or
  Jackson's message. On each path a unit test checks the retry listener's lines and an integration test checks the dead
  letter's headers.
- `KafkaTemplate` logs the whole record of a failed send at DEBUG (`Failed to send:`) and of every send at TRACE
  (`Sending:`, `Sent:`), user ids included, whatever the producer listener does. The root logger stays at INFO by
  default (`LOG_LEVEL`), and raising it is a choice to make knowingly, like turning Postgres error detail on with
  `DB_LOG_SERVER_ERROR_DETAIL` (ADR 0027).
