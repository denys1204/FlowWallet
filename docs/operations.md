# Operating FlowWallet

This file is the operator's runbook: what to do when a withdrawal, a payout or an event waits for a person. Each entry
gives the signal, how to check it, the action and how to verify the result. The contract of each endpoint is in
[api.md](api.md), the flows are in [ARCHITECTURE.md](../ARCHITECTURE.md), the settings and the payout metrics are in
[development.md](development.md#configuration), and the outbox, dead-letter and reconciliation metrics in
[ARCHITECTURE.md](../ARCHITECTURE.md) and [events.md](events.md). The tables the queries read are described in
[data-model.md](data-model.md), and the topics in [events.md](events.md).

## Before you act

### Where things run

Wallet Service listens on `:8081` and Payment Service on `:8082`, each on loopback by default (`WALLET_SERVICE_ADDRESS`,
`PAYMENT_SERVICE_ADDRESS`). Their actuator endpoints are on those ports only. The gateway on `:8080` routes
`/api/wallets/**` and `/api/payments/webhooks/**` and nothing else, so `:8080/actuator/outbox` answers `404`. Both
services check Kafka at startup and do not start while it is unreachable (`spring.kafka.admin.fail-fast`), so after an
outage start Kafka first.

Send every actuator request with curl from the service's host. In the lab, reach the service with `kubectl
port-forward` to a local port and send the requests to `localhost`. Never open an actuator URL in a browser. Payment
Service refuses with `403` every actuator request a web page sends, one with an `Origin` header or a `Sec-Fetch-Site`
other than `none`, which curl never sends. Wallet Service's `outbox` endpoint has no such guard yet
([Known gaps](#known-gaps)).

### Tools

- SQL: `docker exec -it flow-postgres psql -U flowadmin -d wallet_db`, or `-d payment_db`, with the user from
  `POSTGRES_USER` when it is not the default. The queries in this file use a psql variable for the reference: set it
  first with `\set ref 6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d`. References are stored in lower case. psql does not
  replace variables in a `-c` argument, so the one-line commands below put the reference in with the shell instead.
- Kafka: the tools in the broker's container, `docker exec flow-kafka /opt/kafka/bin/<tool>.sh --bootstrap-server
  kafka:29092 ...`, as in [Kafka records](#kafka-records). Kafka UI at `http://localhost:8090` shows topics, records
  with their keys and headers, and consumer groups. It cannot publish, because Compose runs it read-only.
- Stripe: the Dashboard in test mode, or a read with curl and your `sk_test_` key, as in
  [development.md](development.md#6-try-a-deposit). A withdrawal's Stripe objects carry its reference: the transfer in
  the transfer group `wd-<reference>`, and the transfer, the payout and the reversal in the metadata key
  `flowwallet_withdrawal`. A deposit's PaymentIntent carries it in the metadata key `transactionReference`.
- Metrics: `curl -s localhost:8082/actuator/metrics/<name>`, narrowed to one tag value with `?tag=from:transferred`.
  The payout, reconciler and operator-action counters appear only after their first increment; the two dead-letter
  counters exist at 0 from startup. Each counter counts from zero again when its service restarts. The gauges read the database each time they are read.

### Rules

- Never edit `balance_history`, `wallets.balance` or `wallets.last_entry_no`. Money moves in a wallet only through a
  request and through the consumer: a deposit is credited by its `PaymentCompletedEvent` and a withdrawal comes back by
  its `PayoutReturnedEvent`. The one correction by hand in this file changes status columns only
  ([Corrections by hand](#corrections-by-hand)).
- Logs name references, wallet ids and Stripe ids, never a user id
  ([ADR 0027](adr/0027-user-ids-stay-out-of-logs-and-provider-metadata.md)). Ask the user for the reference, which is
  the `Idempotency-Key` of their request. The queries here leave `user_id` out, except where a command has to be
  rebuilt or a row copied. Keep user ids out of tickets and chat.
- Never print `.env` or paste a key from it. It holds the Stripe keys and the database password.
- Read before you write, and read again before you repeat a write whose answer was lost.

### Kafka records

Read a topic with the offset, headers and key of each record. The timeout ends the read once no record has arrived for
ten seconds, and the tool then prints an ERROR with a `TimeoutException` and the number of records it read, which is its
normal end:

```bash
docker exec flow-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 \
  --topic wallet.withdrawals.payment.DLT --from-beginning --timeout-ms 10000 \
  --formatter-property print.offset=true --formatter-property print.headers=true --formatter-property print.key=true
```

A dead-lettered record keeps its key and headers, and the dead-letter publisher adds `kafka_dlt-*` headers: the original
topic, partition and offset, and the exception's class, message and stack trace. For a record the consumer could not
parse, the message names the parser's error type and the line and column where it stopped, not the value
([ADR 0044](adr/0044-unreadable-kafka-records-are-reported-without-their-content.md)); the record's value shows the
input itself. Kafka UI shows the same records in a form that is easier to read.

Check that a consumer group keeps up with its topic. `flow-wallet-payment` reads `wallet.withdrawals`, and
`flow-wallet-service` reads `payment.events`:

```bash
docker exec flow-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:29092 \
  --describe --group flow-wallet-payment
```

Publish one record with its key and its `eventType` header. The console producer reads one line per record, made of the
headers as `name:value`, a tab, the key, a tab and the value, so the value has to be JSON on one line:

```bash
printf 'eventType:%s\t%s\t%s\n' "$EVENT_TYPE" "$REF" "$VALUE" |
  docker exec -i flow-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:29092 \
    --topic "$TOPIC" --reader-property parse.headers=true --reader-property parse.key=true
```

The record carries no header but `eventType`, so a republished dead letter leaves its `kafka_dlt-*` headers behind.
Both consumers read the type from that header and treat a record without it as unreadable. The key is the reference,
as the outbox sends it.

### Exposing the payouts endpoint

Payment Service's `payouts` endpoint is in no default exposure, because its writes credit wallets and it authenticates
nobody. Restart the one Payment Service process with the endpoint added to the defaults it replaces:

```bash
ACTUATOR_EXPOSED_ENDPOINTS=health,info,metrics,outbox,payouts ./mvnw -pl flow-wallet-payment spring-boot:run
```

Never set it in `.env`. All three services read that file, and the value replaces each service's own default. In a
cluster, set the variable on Payment Service's workload only while acting. When you are done, restart Payment Service
without it; with the default exposure every `payouts` path answers `404`.

The endpoint answers only a loopback `Host` (`localhost`, `127.0.0.1` or `[::1]`), so send the requests to
`localhost:8082`, directly or through the port-forward. A write needs the header `X-FlowWallet-Operator` with any value,
and no request takes a body or a query string. The contract, the answers and every refusal are in
[api.md](api.md#operator-the-payouts-actuator-endpoint-8082-directly).

## Signals

| Signal | Service | Meaning | Entry |
|---|---|---|---|
| `outbox.events.failed` above 0; `GET localhost:8081/actuator/outbox` answers `{"failed":n}` | Wallet | A debited withdrawal's command never reached Kafka | [A failed row in the wallet's outbox](#a-failed-row-in-the-wallets-outbox) |
| `outbox.events.failed` above 0 on `localhost:8082` | Payment | A deposit's credit or failure, or a payout's status or return, never reached Kafka, so the wallet never saw it | [A failed row in Payment Service's outbox](#a-failed-row-in-payment-services-outbox) |
| `payment.consumer.dead.letters` rising; ERROR `Dead-lettered wallet.withdrawals-<partition> at offset <offset> to wallet.withdrawals.payment.DLT: <cause>` | Payment | A debited withdrawal's command that Payment Service did not accept | [A command on the dead-letter topic](#a-command-on-the-dead-letter-topic) |
| ERROR `Withdrawal <reference> arrived again with other terms` | Payment | A second command under an accepted reference, with other terms, dead-lettered at once | [A command on the dead-letter topic](#a-command-on-the-dead-letter-topic) |
| `wallet.consumer.dead.letters` rising; ERROR `Dead-lettered payment.events-<partition> at offset <offset> to payment.events.wallet.DLT: <cause>` | Wallet | A payment or payout event the wallet could not read or apply | [A record on the wallet's dead-letter topic](#a-record-on-the-wallets-dead-letter-topic) |
| `REJECTED` rows in `processed_events`; ERROR `Refused event <id> for transaction <reference>: <reason>` | Wallet | An event the wallet read and refused. No metric counts these | [Refused events](#refused-events) |
| `payment.payouts.awaiting.operator` above 0 (tag `from`) | Payment | Payouts in `NEEDS_ATTENTION` now | [Payouts waiting for an operator](#payouts-waiting-for-an-operator) |
| `payment.payouts.needs.attention` rising (tags `from`, `cause`); ERROR `Payout <reference> needs attention from <step> (<cause>): <detail>` | Payment | The executor handed a payout to an operator | [Payouts waiting for an operator](#payouts-waiting-for-an-operator) |
| `payment.payouts.operator.actions` (tags `action`, `outcome`) | Payment | What operators did: `applied`, `refused` (a `404` or `422`), `retry` (a `503`) or `stripe_unavailable` (a `502`) | [Payouts waiting for an operator](#payouts-waiting-for-an-operator) |
| WARN `The platform's PLN reserve does not cover a withdrawal`; the wallet answers `503` | Payment | The platform's Stripe balance is short, and nothing was debited | [Withdrawals refused for a short reserve](#withdrawals-refused-for-a-short-reserve) |
| Withdrawals `RETURNED` with `PLATFORM_FUNDS_SHORT`; INFO `Payout <reference> returned before its transfer: PLATFORM_FUNDS_SHORT (<code>)` | Payment | The reserve was short at the transfer, after the preflight had passed | [Withdrawals refused for a short reserve](#withdrawals-refused-for-a-short-reserve) |
| INFO `Payout <reference> failed at its bank account, which is set back to NOT_SET`; WARN `Payout <reference> reversing: BANK_ACCOUNT_REJECTED (<code>)` | Payment | The user's bank account no longer takes payouts | [A bank account set back to NOT_SET](#a-bank-account-set-back-to-not_set) |
| WARN `Reconciler completed transaction <reference> without its webhook` or `Payout <reference> settled (<from> to <to>) without its webhook` | Payment | A webhook was lost | [Webhook secrets and the reconcilers](#webhook-secrets-and-the-reconcilers) |
| WARN at startup `Stripe webhooks are disabled: ...` or `Stripe Connect webhooks are disabled: ...` | Payment | A signing secret is not set | [Webhook secrets and the reconcilers](#webhook-secrets-and-the-reconcilers) |
| ERROR `Stripe payout <id> on account <id> disagrees with payout <reference> on its <fields>; nothing was changed` | Payment | A verified Connect event does not match the stored payout | [Webhook secrets and the reconcilers](#webhook-secrets-and-the-reconcilers) |
| ERROR `Ignoring success event <id> for tx <reference>: the provider reports a different <field>` | Payment | A deposit's PaymentIntent does not match the stored payment | [A webhook whose terms differ from the deposit](#a-webhook-whose-terms-differ-from-the-deposit) |

### Alerts

- `payment.payouts.awaiting.operator` above 0 for 15 minutes. With `from=accepted` the money is still in the
  platform's Stripe balance and the wallet shows `PENDING`. With `from=transferred` the money sits in the user's
  connected account and the wallet shows `PENDING`. With `from=reversing` the wallet shows `RETURN_PENDING`.
- `outbox.events.failed` above 0 on either service. Each `FAILED` row is an event the other service never received,
  and nothing retries it until an operator requeues it.
- `wallet.consumer.dead.letters` or `payment.consumer.dead.letters` rising. Both count from zero again after a restart,
  so alert on an increase rather than on the value.

### Known noise

- Wallet Service logs each `503` for a short reserve and each `502` from the preflight at ERROR with a stack trace, as
  the platform logs every 5xx it answers, for example `API exception 503 at /api/wallets/PLN/withdrawals: Withdrawals
  are temporarily unavailable. ...`. Payment Service's WARN about the reserve is the signal for a short reserve.
- Payment Service logs the `502` and `503` refusals of the `payouts` endpoint the same way.
- Payment Service logs a WARN `Ignoring event <id> for provider tx <pi_...>: no transaction in this service` for a
  PaymentIntent it did not create, such as a platform top-up.
- Under `stripe listen`, Payment Service logs a WARN for each event that arrives in another API version than the SDK's.

## Tracing one withdrawal

Set `ref` and run these in `wallet_db`:

```sql
-- the withdrawal
SELECT w.status, w.refusal_reason, w.return_reason, w.amount, wa.currency, w.wallet_id, w.created_at, w.updated_at
  FROM withdrawals w JOIN wallets wa ON wa.id = w.wallet_id
 WHERE w.transaction_reference = :'ref';

-- its ledger rows: the WITHDRAWAL debit and, once it came back, the WITHDRAWAL_RETURNED credit
SELECT entry_no, type, amount, balance_before, balance_after, event_id, created_at
  FROM balance_history WHERE transaction_reference = :'ref' ORDER BY entry_no;

-- its command to Payment Service; COMPLETED rows are deleted after OUTBOX_RETENTION_DAYS (7)
SELECT id, status, retry_count, error_message, created_at, processed_at
  FROM outbox_events WHERE aggregate_type = 'Withdrawal' AND aggregate_id = :'ref';

-- the payout events the wallet settled
SELECT event_id, event_type, outcome, rejection_reason, processed_at
  FROM processed_events WHERE transaction_reference = :'ref' ORDER BY processed_at;
```

And these in `payment_db`:

```sql
-- the payout
SELECT status, attention_from, attention_cause, attention_detail, reason_code, failure_code, key_gen, attempts,
       next_attempt_at, stripe_account_id, stripe_transfer_id, stripe_payout_id, stripe_reversal_id,
       transferred_at, paid_at, created_at, updated_at
  FROM payouts WHERE transaction_reference = :'ref';

-- the payout events on their way to the wallet
SELECT id, event_type, status, retry_count, error_message, created_at, processed_at
  FROM outbox_events WHERE aggregate_type = 'Payout' AND aggregate_id = :'ref' ORDER BY created_at;
```

At Stripe, look up the transfer by its group `wd-<reference>` or by `stripe_transfer_id`, and the payouts on the
connected account `stripe_account_id` by their `flowwallet_withdrawal` metadata. The reads with curl:

```bash
curl -G https://api.stripe.com/v1/transfers -u "<your sk_test_ key>:" -d transfer_group=wd-$REF
curl https://api.stripe.com/v1/payouts/<po_...> -u "<your sk_test_ key>:" -H "Stripe-Account: <acct_...>"
```

How each withdrawal status matches a payout status is in the table at the end of
[ARCHITECTURE.md](../ARCHITECTURE.md#withdrawals). The wallet's status follows the payout's events, so it can trail the
payout's own status for as long as an event takes to arrive.

### A withdrawal that stays `PENDING`

1. The wallet's `outbox_events` row. `PENDING` or `PROCESSING` means the command is not sent yet: check the broker and
   Wallet Service's WARN `Fast path could not send outbox event <id>; the poller retries it.`, and the row goes out on a
   later try. `FAILED` goes to [A failed row in the wallet's outbox](#a-failed-row-in-the-wallets-outbox). `COMPLETED`,
   or no row once it was cleaned up, means the command reached Kafka: go on.
2. The `payouts` row. No row means Payment Service has not accepted the command: check that it runs, that
   `flow-wallet-payment` has no lag on `wallet.withdrawals`, and the dead-letter topic
   ([A command on the dead-letter topic](#a-command-on-the-dead-letter-topic)).
3. The payout's status:
   - `ACCEPTED` or `TRANSFERRED`: the executor is working it. `next_attempt_at` and `attempts` show where it stands, and
     the WARN lines `Payout <reference> <step> outcome unknown, attempt <n>, next at <time>: <cause>` and
     `Payout <reference> <step> refused (<code>), retrying under key generation <n> at <time>` say why it waits. An
     unknown outcome is retried for about 7 hours and a payout refused for funds or a limit for about 24 hours before
     the payout comes back or an operator takes over. Check that `PAYMENT_PAYOUT_EXECUTOR_ENABLED` is not `false`.
   - `PAYOUT_CREATED`: Stripe has not paid it yet, or the Connect webhook was lost. The payout reconciler asks Stripe
     about it from 15 minutes after acceptance on, at most every 15 minutes
     ([Webhook secrets and the reconcilers](#webhook-secrets-and-the-reconcilers)).
   - `NEEDS_ATTENTION` from `ACCEPTED` or `TRANSFERRED`:
     [Payouts waiting for an operator](#payouts-waiting-for-an-operator).
   - `PAID`, `REVERSING` or `RETURNED`: an event has not reached the wallet. Read Payment Service's `outbox_events` rows
     for the reference. A `FAILED` one goes to
     [A failed row in Payment Service's outbox](#a-failed-row-in-payment-services-outbox). When they are `COMPLETED`,
     read the wallet's `processed_events` for the reference: a `REJECTED` row goes to [Refused events](#refused-events),
     and no row means lag on `flow-wallet-service` or a record on the wallet's dead-letter topic
     ([A record on the wallet's dead-letter topic](#a-record-on-the-wallets-dead-letter-topic)).

### A withdrawal that stays `RETURN_PENDING`

- `REVERSING`: the executor reverses the transfer. A refused reversal is retried for about 6 hours before an operator
  takes over.
- `NEEDS_ATTENTION` from `REVERSING`: [Payouts waiting for an operator](#payouts-waiting-for-an-operator).
- `RETURNED`: the `PayoutReturnedEvent` that credits the wallet has not been applied. Follow the events as for `PAID`,
  `REVERSING` or `RETURNED` above.

### A `PAID` withdrawal

`PAID` is not final. The payout reconciler asks Stripe about a paid payout every 6 hours for 10 days after it was paid,
and the Connect webhook can report a failure at any time. A failure takes the payout to `REVERSING` and the withdrawal
to `RETURN_PENDING`, and the money comes back through the usual path. More than 10 days after the payout was paid, only
a webhook reports a failure ([A missed Stripe event](#a-missed-stripe-event)).

## A debited withdrawal that never reached Payment Service

The wallet debited the withdrawal and wrote its command, and Payment Service has no `payouts` row for it. The money
stays debited, and the withdrawal stays `PENDING`, until an operator acts
([ADR 0034](adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md)).

### A failed row in the wallet's outbox

Signal: `outbox.events.failed` above 0 on Wallet Service, or `curl -s localhost:8081/actuator/outbox` answers
`{"failed":n}` with `n` above 0. The last attempt logs `Attempt <n> of <max> to send outbox event <id> failed (marked
FAILED): <cause>`, usually inside the poller's ERROR `Fallback Poller: outbox event <id> was not sent; moving on to the
next`.

Check in `wallet_db`:

```sql
SELECT id, aggregate_id, event_type, retry_count, error_message, created_at
  FROM outbox_events WHERE status = 'FAILED' ORDER BY created_at;
```

Every row there is a debited withdrawal's command, because Wallet Service's outbox carries nothing else, and
`aggregate_id` is its reference. Keep the `error_message` values: the requeue clears them.

Action: fix the cause, usually a broker that is down or unreachable, then requeue:

```bash
curl -s -X POST localhost:8081/actuator/outbox
```

The answer is `{"requeued":n}`. The requeue returns every `FAILED` row of Wallet Service to `PENDING`, which is safe:
Payment Service accepts each reference once and acknowledges a repeat with the same terms.

Verify: `curl -s localhost:8081/actuator/outbox` answers `{"failed":0}`, the rows reach `COMPLETED`, Payment Service
logs INFO `Accepted withdrawal <reference>`, and `payment_db` has a `payouts` row for each reference.

### A command on the dead-letter topic

Signal: `payment.consumer.dead.letters` rising, and Payment Service's ERROR `Dead-lettered
wallet.withdrawals-<partition> at offset <offset> to wallet.withdrawals.payment.DLT: <cause>`.

Check:

1. Read the record on `wallet.withdrawals.payment.DLT` ([Kafka records](#kafka-records)). Its key is the reference, and
   `kafka_dlt-exception-message` gives the cause.
2. Look for a `payouts` row with the reference in `payment_db`. A row means the command was accepted in the meantime,
   and there is nothing to do.
3. Tell the causes apart:
   - An outage that outlasted the consumer's retries, such as a `payment_db` outage: fix it, then republish.
   - An unreadable record or one of an invalid shape. Wallet Service does not write one, so it is a defect, or a record
     from something else when no withdrawal has the key: then stop and escalate. The same record is dead-lettered
     again, so fix the defect first, then rebuild the command ([A command lost from both](#a-command-lost-from-both)).
   - ERROR `Withdrawal <reference> arrived again with other terms`: a command whose terms differ from the payout already
     accepted under the reference. Never republish it. Wallet Service sends one command per debit, so this is a defect
     or a record written to the topic by something else, which can start a payout with no debit behind it
     ([ADR 0035](adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md)). Compare it with the `payouts` and
     `withdrawals` rows, and stop and escalate.

Action: republish the record unchanged to `wallet.withdrawals`, with the reference as its key and the header
`eventType:WithdrawalRequestedEvent`. The value is the record's own value, which is also the `payload` of the wallet's
outbox row while that row is kept:

```bash
REF=<reference>
VALUE=$(docker exec flow-postgres psql -U flowadmin -d wallet_db -At -c \
  "SELECT payload FROM outbox_events WHERE aggregate_type = 'Withdrawal' AND aggregate_id = '$REF'")
EVENT_TYPE=WithdrawalRequestedEvent TOPIC=wallet.withdrawals
```

Then publish it as in [Kafka records](#kafka-records).

Verify: Payment Service logs INFO `Accepted withdrawal <reference>`, or `Withdrawal <reference> was already accepted;
redelivery acknowledged` for a command it already had, and `payment_db` has the `payouts` row. The record stays on the
dead-letter topic, which keeps its records without a time limit, so note its offset as replayed.

### A command lost from both

When the command is lost, its `FAILED` row deleted or its dead letter gone, rebuild it from the withdrawal. First
confirm that the withdrawal is `PENDING` with its `WITHDRAWAL` ledger row, that `payment_db` has no `payouts` row for
the reference, that the wallet's outbox has no row for it, and that `flow-wallet-payment` has no lag on
`wallet.withdrawals`, so the command is not still on its way. The command takes a fresh `eventId`: Payment Service
decides a repeat by the reference, not by the event id. The value holds the user id, which the command needs, so keep
it out of anything you share:

```bash
REF=<reference>
VALUE=$(docker exec flow-postgres psql -U flowadmin -d wallet_db -At -c "
  SELECT json_build_object('eventId', gen_random_uuid()::text, 'schemaVersion', 1,
                           'transactionReference', w.transaction_reference, 'userId', wa.user_id,
                           'amount', w.amount, 'currency', wa.currency)::text
    FROM withdrawals w JOIN wallets wa ON wa.id = w.wallet_id
   WHERE w.transaction_reference = '$REF' AND w.status = 'PENDING'")
EVENT_TYPE=WithdrawalRequestedEvent TOPIC=wallet.withdrawals
```

Publish it as in [Kafka records](#kafka-records), and verify as for a republished record.

## Events the wallet did not apply

### A failed row in Payment Service's outbox

Signal: `outbox.events.failed` above 0 on Payment Service, or `curl -s localhost:8082/actuator/outbox` answers
`{"failed":n}` with `n` above 0.

Check in `payment_db`:

```sql
SELECT id, aggregate_type, aggregate_id, event_type, retry_count, error_message, created_at
  FROM outbox_events WHERE status = 'FAILED' ORDER BY created_at;
```

`aggregate_type` is `PaymentTransaction` for a deposit's event and `Payout` for a payout's, and `aggregate_id` is the
reference. A `PaymentCompletedEvent` or a `PayoutReturnedEvent` there is a credit the wallet never made. Keep the
`error_message` values: the requeue clears them.

Action: fix the cause, then `curl -s -X POST localhost:8082/actuator/outbox`, which answers `{"requeued":n}`. The
wallet discards an event it already settled by its event id.

Verify: the answer to a `GET` is `{"failed":0}`, and the wallet's `processed_events` has a row for each event id.

### A record on the wallet's dead-letter topic

Signal: `wallet.consumer.dead.letters` rising, and Wallet Service's ERROR `Dead-lettered payment.events-<partition> at
offset <offset> to payment.events.wallet.DLT: <cause>`.

Check: read the record on `payment.events.wallet.DLT` ([Kafka records](#kafka-records)). A record without a known
`eventType` header, with JSON that does not parse, with no value or with no `eventId` was dead-lettered at once. Any
other record failed through the consumer's retries, for example while `wallet_db` was down.

Action: fix the cause, then republish the record unchanged to `payment.events`, with its key and its `eventType` header.
A dead-lettered record left no `processed_events` row, so its own event id passes the barrier
([ARCHITECTURE.md](../ARCHITECTURE.md#the-wallet-consumer)). The value is the record's own, which is also the `payload`
of Payment Service's outbox row while that row is kept:

```bash
REF=<reference> EVENT_TYPE=<eventType header> TOPIC=payment.events
VALUE=$(docker exec flow-postgres psql -U flowadmin -d payment_db -At -c \
  "SELECT payload FROM outbox_events WHERE aggregate_id = '$REF' AND event_type = '$EVENT_TYPE'")
```

Then publish it as in [Kafka records](#kafka-records).

Verify: `processed_events` has a row with the record's event id, and for a credit `balance_history` has the
`DEPOSIT` or `WITHDRAWAL_RETURNED` row under the reference. The record stays on the dead-letter topic, so note its
offset as replayed.

### Refused events

Signal: Wallet Service's ERROR `Refused event <id> for transaction <reference>: <reason>`. No metric counts refusals, so
look at the rows in `wallet_db`:

```sql
SELECT rejection_reason, event_type, count(*)
  FROM processed_events WHERE outcome = 'REJECTED' GROUP BY rejection_reason, event_type;

SELECT event_id, event_type, transaction_reference, rejection_reason, processed_at
  FROM processed_events WHERE outcome = 'REJECTED' ORDER BY processed_at DESC LIMIT 50;
```

A `REJECTED` row holds the event's id, so the payload republished unchanged meets that barrier and credits nothing. For a
refusal the handler decided, the wallet logs `Event <id> was already processed; nothing changed`; for `INVALID_AMOUNT`
and `INVALID_ENVELOPE` it logs the ERROR `Refused event ...` again and then `Event <id> was already refused; the refusal
is on record`. What to do depends on the reason:

| Reason | Event | Action |
|---|---|---|
| `WALLET_NOT_FOUND` | `PaymentCompletedEvent` | Once the user has opened the wallet, republish the payload under a fresh `eventId` |
| `WALLET_NOT_FOUND` | a payout event | A payout for a user and currency that hold no wallet has no debit behind it. Stop and escalate |
| `INVALID_AMOUNT`, `INVALID_ENVELOPE` | any | Payment Service sent a wrong event. Find the defect, correct the payload from Payment Service's row (`payment_transactions` or `payouts`), and republish it under a fresh `eventId` |
| `DUPLICATE_REFERENCE` | any | Never republish: the payment or the return is already credited |
| `WITHDRAWAL_NOT_FOUND` | a payout event | No withdrawal has the reference, or a refused one has it, so no debit is behind the payout: a command written to the topic by something other than Wallet Service ([ADR 0035](adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md)), or a defect. Stop and escalate |
| `WITHDRAWAL_MISMATCH` | a payout event | The payout's wallet or amount differs from the withdrawal's. Compare the `payouts` row with the `withdrawals` row and its wallet, and stop and escalate |

To republish under a fresh `eventId`, take the stored payload with its id replaced, then publish it to `payment.events`
as in [Kafka records](#kafka-records), with the row's `event_type` as `EVENT_TYPE` and its `transaction_reference` as
`REF`. For an `INVALID_*` row, correct the value before you publish it.

```bash
EVENT_ID=<event_id of the REJECTED row> TOPIC=payment.events
VALUE=$(docker exec flow-postgres psql -U flowadmin -d wallet_db -At -c "
  SELECT jsonb_set(payload::jsonb, '{eventId}', to_jsonb(gen_random_uuid()::text))::text
    FROM processed_events WHERE event_id = '$EVENT_ID' AND outcome = 'REJECTED'")
```

Verify: a new `processed_events` row under the new event id, `CREDITED` for a deposit, with its `DEPOSIT` row in
`balance_history`. The ledger's unique (`transaction_reference`, `type`) key still stops a second credit for a payment
that was credited in the meantime.

## Payouts waiting for an operator

A payout the executor cannot finish safely waits in `NEEDS_ATTENTION` until a person acts, and the wallet shows
`PENDING` or `RETURN_PENDING` until then. The endpoint, its answers and its refusals are in
[api.md](api.md#operator-the-payouts-actuator-endpoint-8082-directly), and the reasoning in
[ADR 0037](adr/0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md).

Signal: `payment.payouts.awaiting.operator` above 0 ([Alerts](#alerts)), `payment.payouts.needs.attention` rising, and
Payment Service's ERROR `Payout <reference> needs attention from <step> (<cause>): <detail>`.

### Procedure

1. Expose the endpoint ([Exposing the payouts endpoint](#exposing-the-payouts-endpoint)).
2. List the waiting payouts, longest-waiting first, with their counts by step:

   ```bash
   curl -s localhost:8082/actuator/payouts
   ```

3. Read the one to act on. `attentionFrom`, `cause`, `detail`, `since`, `actions` and the Stripe ids are what the
   decision needs:

   ```bash
   curl -s localhost:8082/actuator/payouts/$REF
   ```

   `cause` and `detail` are `null` only for a payout that is not in `NEEDS_ATTENTION`.
4. Look at Stripe for the objects that `detail` and the Stripe ids name.
5. Decide with the table in [What to do for each cause](#what-to-do-for-each-cause).
6. Act, with the action's name at the end of the path:

   ```bash
   curl -s -X POST -H 'X-FlowWallet-Operator: on-call' localhost:8082/actuator/payouts/$REF/requeue
   ```

   - `200`: the answer is `{action, payout, leftInConnectedAccount}`, with the payout after the write.
   - `422`: the payout's state or what Stripe holds refuses the action. The detail names the action to use instead,
     or says that a paid payout needs correction by hand.
   - `503`: nothing moved. A return or a `reverse` waits a quiet period after `since`,
     `PAYMENT_PAYOUT_EXECUTOR_UNKNOWN_BACKOFF` (1 minute by default), and the detail gives the time to retry after.
     Otherwise the payout changed while Stripe was asked: read it again before you decide.
   - `502`: Stripe could not be asked, and nothing moved. Retry.
   - No answer: read the payout before you repeat the write. A repeat after a write that committed answers `422` with
     the payout's current status.
7. Check the wallet with the queries in [Tracing one withdrawal](#tracing-one-withdrawal), as in
   [What the wallet shows afterwards](#what-the-wallet-shows-afterwards).
8. Restart Payment Service without the endpoint.

Verify: the gauge for the payout's `from` drops by one, `payment.payouts.operator.actions` counts the action as
`applied`, and Payment Service logs INFO `Operator requeued payout <reference> to <step> (was <cause>: <detail>)`, or
WARN `Operator returned payout <reference> ...` or `Operator sent payout <reference> from TRANSFERRED to its reversal`.

### What to do for each cause

| Cause | From | Action |
|---|---|---|
| `UNKNOWN_LIMIT` | any step | Once Stripe answers again, `requeue`. The step looks for what Stripe holds under the same key generation before it creates anything, and adopts what an earlier try created |
| `UNKNOWN_LIMIT` handed over again with the same detail | `ACCEPTED` | `return-without-reversal`. It is refused while a transfer in the group is not reversed in full, and the refusal names that transfer |
| `UNKNOWN_LIMIT` handed over again with the same detail | `TRANSFERRED` | `reverse`. It is refused while a payout of the transfer may reach the bank, and the refusal names that payout |
| `UNKNOWN_LIMIT` handed over again with the same detail | `REVERSING` | As `REVERSAL_REFUSED` |
| `DEFECT` | `ACCEPTED` | Report the defect, an idempotency key sent with other parameters, then `return-without-reversal` |
| `DEFECT` | `TRANSFERRED` | Report the defect, then `reverse` |
| `DEFECT` | `REVERSING` | Report the defect, and `requeue` once the fix is deployed |
| `MISMATCH` or `MULTIPLE` | `ACCEPTED` | Reverse every transfer of the group `wd-<reference>` in full in the Dashboard, then `return-without-reversal`, which a transfer reversed in full does not block. `requeue` meets the same cause, because the transfer step counts a reversed transfer too |
| `MISMATCH` or `MULTIPLE` | `TRANSFERRED` | Wait until Stripe reports each payout of the reference that may reach the bank failed or canceled, then `reverse`. When one is left with the payout's amount and currency, `requeue`, and the payout step adopts it. When one with other terms was paid, see [Cases with no template](#cases-with-no-template) |
| `LIVE_PAYOUT` | `REVERSING` | Wait. When Stripe reports the payout failed or canceled, `requeue`, and the reversal step lists the payouts again and reverses. When it was paid, see [A second payout paid during a reversal](#a-second-payout-paid-during-a-reversal) |
| `REVERSAL_REFUSED` | `REVERSING` | Fix what Stripe's code in the detail names, then `requeue`; the first try after it uses a fresh key generation. Or `return-without-reversal`: the wallet is credited at once, and the transfer's part that no reversal brought back stays in the connected account as the platform's loss, stated in `leftInConnectedAccount` |

The requeue's INFO line gives the cause and detail it cleared, so a second hand-over can be compared with the first.

Prefer `requeue` once the cause has cleared: Stripe reachable again, a limit lifted, a live payout reported failed or
canceled, or one live payout with the payout's terms, which a refused `reverse` points to. The executor repeats the step
under the same key generation after looking for what Stripe holds, so it adopts what an earlier try created and
completes the payout. A return credits the wallet at once, and `reverse` brings the transfer back out of the connected
account once no payout of it can reach the bank. Never create a transfer or a payout by hand: the one Dashboard write
this file asks for is a full reversal of a stray transfer.

### What the wallet shows afterwards

| Action | Payout | Withdrawal in Wallet Service |
|---|---|---|
| `requeue` | back in its step, worked at the executor's next run | unchanged |
| `return-without-reversal` from `ACCEPTED` | `RETURNED` with `OPERATOR_RETURNED`, and a `PayoutReturnedEvent` | `PENDING` to `RETURNED`, credited once with a `WITHDRAWAL_RETURNED` row |
| `return-without-reversal` from `REVERSING` | `RETURNED` with `OPERATOR_RETURNED`, and a `PayoutReturnedEvent` | `RETURN_PENDING` to `RETURNED`, credited once |
| `reverse` | `REVERSING` with `PAYOUT_FAILED` and a `PayoutFailedEvent`, then `RETURNED` after the executor's reversal | `PENDING` to `RETURN_PENDING` at once, then `RETURNED` with the credit |

A `requeue` or a `reverse` hands the payout back to the executor, so with `PAYMENT_PAYOUT_EXECUTOR_ENABLED=false`
nothing works it.

## Corrections by hand

Only the case below has a template for correcting the rows by hand. For the other cases this section says what is true
and to stop and escalate. A correction changes status columns only, in one transaction per database. It writes no
event and never touches the ledger or a balance.

### A second payout paid during a reversal

The case: a payout waits from `REVERSING` with `LIVE_PAYOUT`, and the payout its detail names was paid. The money
reached the bank, the wallet shows `RETURN_PENDING`, `requeue` meets the same payout again, and
`return-without-reversal` refuses with "a paid one needs correction by hand"
([ADR 0035](adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md)).

Confirm each of these first, and stop and escalate if one fails:

1. On the connected account `stripe_account_id`, exactly one payout with the reference in `flowwallet_withdrawal` is
   `paid`, with the payout row's amount and currency, and every other one is `failed` or `canceled`. A paid payout with
   other terms is a case in [Cases with no template](#cases-with-no-template).
2. The transfer `stripe_transfer_id` has no reversal: its `amount_reversed` is 0 and the row's `stripe_reversal_id` is
   empty.
3. In `wallet_db`, the withdrawal is `RETURN_PENDING` and no `WITHDRAWAL_RETURNED` row has the reference. Such a row
   means the bank and the wallet were both paid.
4. In `payment_db`, no `outbox_events` row of the reference is `PENDING`, `PROCESSING` or `FAILED`, so no event of it
   is still on its way to the wallet.

Copy both rows. The copies hold the user id, so keep them local and delete them once the case is closed:

```bash
docker exec flow-postgres psql -U flowadmin -d payment_db -x -c \
  "SELECT * FROM payouts WHERE transaction_reference = '$REF'" > payout-$REF.txt
docker exec flow-postgres psql -U flowadmin -d wallet_db -x -c \
  "SELECT * FROM withdrawals WHERE transaction_reference = '$REF'" > withdrawal-$REF.txt
```

Then correct `payment_db` first. Set `ref`, and `paid_po` to the paid payout's id:

```sql
\set ref 6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d
\set paid_po po_...
BEGIN;
UPDATE payouts
   SET status = 'PAID', stripe_payout_id = :'paid_po', paid_at = now(),
       attention_from = NULL, attention_cause = NULL, attention_detail = NULL,
       reason_code = NULL, failure_code = NULL, version = version + 1, updated_at = now()
 WHERE transaction_reference = :'ref' AND status = 'NEEDS_ATTENTION' AND attention_from = 'REVERSING'
   AND stripe_reversal_id IS NULL;
```

The `UPDATE` must report `UPDATE 1`: then `COMMIT;`, otherwise `ROLLBACK;` and stop. Then `wallet_db`:

```sql
\set ref 6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d
BEGIN;
UPDATE withdrawals
   SET status = 'PAID', return_reason = NULL, version = version + 1, updated_at = now()
 WHERE transaction_reference = :'ref' AND status = 'RETURN_PENDING';
```

Again `COMMIT;` only after `UPDATE 1`.

The CHECKs that bind are `payouts_attention_from_when_needed` and `payouts_attention_cause_only_when_needed` (the
attention columns are empty outside `NEEDS_ATTENTION`), `payouts_payout_when_created` (a `PAID` payout has its payout
id), `payouts_transfer_when_transferred` (and its transfer and account, which a row from `REVERSING` already holds) and
`withdrawals_return_reason_when_returning` (a `PAID` withdrawal has no return reason). The unique
`payouts_stripe_payout_key` refuses a payout id another row holds. A refused statement aborts the transaction: roll it
back and stop.

`paid_at` and `updated_at` are `TIMESTAMP` columns without a time zone, and `now()` gives the time in the session's
zone. Compare it with the times in the copies, and write the time in their zone if they differ. `paid_at` starts the
payout reconciler's recheck: it asks Stripe about the paid payout every 6 hours for 10 days, and a later failure takes
the payout to `REVERSING` and the withdrawal to `RETURN_PENDING` through the usual path. The debit in the ledger stands,
because the money reached the bank.

Verify: the payout reads `PAID` with an empty `actions` list, the gauge for `from=reversing` dropped by one, the
withdrawal reads `PAID`, and the reference has no new `balance_history` row.

### Cases with no template

- A payout with other terms than the row's, from `TRANSFERRED` or `REVERSING`, that was paid. The bank received that
  payout's amount from the connected account, while the wallet was debited the withdrawal's amount. The rows would have
  to record a payout of other terms and the return of a difference, and neither table can: a return credits the
  withdrawal's full amount, once. Stop and escalate.
- Objects created by hand at Stripe without the `flowwallet_withdrawal` metadata, such as a transfer outside the group
  `wd-<reference>` or a payout made in the Dashboard. Neither the executor nor the endpoint sees them, so what the rows
  say can differ from what Stripe did, and only Stripe's records show which. Stop and escalate.

## Withdrawals refused for a short reserve

Signal: Payment Service's WARN `The platform's PLN reserve does not cover a withdrawal`, with the wallet's `503`
"Withdrawals are temporarily unavailable. Nothing was debited; retry later with the same Idempotency-Key." Or
withdrawals `RETURNED` with `PLATFORM_FUNDS_SHORT` and Payment Service's INFO `Payout <reference> returned before its
transfer: PLATFORM_FUNDS_SHORT (<code>)`, when two withdrawals passed the preflight together and the later transfer
found the balance short. Such a withdrawal is already credited back.

Check: the reserve is the platform's available PLN card funds at Stripe, less what payouts not yet transferred owe. Read
the balance in the Dashboard or with `curl https://api.stripe.com/v1/balance -u "<your sk_test_ key>:"` (the `available`
entry for `pln`, in grosze, and its `source_types.card`), and the owed sum in `payment_db`:

```sql
SELECT coalesce(sum(amount), 0) FROM payouts
 WHERE currency = 'PLN' AND (status = 'ACCEPTED' OR (status = 'NEEDS_ATTENTION' AND attention_from = 'ACCEPTED'));
```

Action: top up the platform's balance with a PaymentIntent paid on the platform with the test card
`4000 0037 2000 0278`, `pm_card_bypassPendingInternational`, whose funds Stripe makes available at once. `amount` is in
grosze, so `50000` is 500.00 PLN:

```bash
curl https://api.stripe.com/v1/payment_intents -u "<your sk_test_ key>:" \
  -d amount=50000 -d currency=pln -d "payment_method_types[]=card" \
  -d payment_method=pm_card_bypassPendingInternational -d confirm=true
```

It credits no wallet. Payment Service receives its `payment_intent.succeeded` webhook for an intent it never created,
answers `200` and logs the WARN listed in [Known noise](#known-noise). Stripe keeps its fee from the charge, as from
every deposit.

Verify: the available PLN balance rose, and a retry of the refused withdrawal with the same key gets its `202`. A user
whose withdrawal came back with `PLATFORM_FUNDS_SHORT` withdraws again under a new key.

## A bank account set back to NOT_SET

Signal: Payment Service's INFO `Payout <reference> failed at its bank account, which is set back to NOT_SET`, or WARN
`Payout <reference> reversing: BANK_ACCOUNT_REJECTED (<code>)`. The withdrawal comes back with `PAYOUT_FAILED` or
`BANK_ACCOUNT_REJECTED`, and the user's next withdrawal gets `422` with `NO_BANK_ACCOUNT`, which spends its key.

Action: none for the operator. The user sets a new bank token with `PUT /api/wallets/bank-account` and withdraws under
a new key.

Verify: the returned withdrawal reads `RETURNED` with its `WITHDRAWAL_RETURNED` row. After the new token, the user's
`GET /api/wallets/bank-account` answers `READY`.

## Webhook secrets and the reconcilers

Signal: a WARN at startup that the platform's or the Connect webhooks are disabled, `400` answers in the output of
`stripe listen`, Payment Service's WARN `API exception 400 at /api/payments/webhooks/stripe/connect: Invalid Stripe
signature` (or the same for `/api/payments/webhooks/stripe`), or the reconcilers' WARN lines `Reconciler completed
transaction <reference> without its webhook` and `Payout <reference> settled (<from> to <to>) without its webhook`.

Check: that `stripe listen` runs with both targets, as in [development.md](development.md#5-forward-stripe-webhooks),
and that `STRIPE_WEBHOOK_SECRET` and `STRIPE_CONNECT_WEBHOOK_SECRET` hold the `whsec_` value it printed, or each
registered endpoint's own secret. Without the platform secret a paid deposit waits for `PendingPaymentReconciler`, from
15 minutes to a day after it started. Without the Connect secret payouts settle at the payout reconciler's pace: a
created payout from 15 minutes after acceptance, at most every 15 minutes, and a paid one every 6 hours for 10 days.

Action: set the secrets without printing them, and restart Payment Service. A changed secret takes effect only at
startup ([ADR 0036](adr/0036-connect-webhooks-have-their-own-endpoint-and-secret.md)).

Verify: the startup WARN is gone, `stripe listen` shows `200` for the next deliveries, and no new `without its webhook`
WARN appears.

An ERROR `Stripe payout <id> on account <id> disagrees with payout <reference> on its <fields>; nothing was changed`
means a verified Connect event names a payout whose account, amount, currency, payout id or reference differs from the
stored row. The row keeps its state. Compare the payout at Stripe with the `payouts` row, and stop and escalate.

## Deposits

### A deposit that stays `PENDING`

Signal: a user reports a paid deposit that was not credited, or a `payment_transactions` row stays `PENDING`, or
`FAILED` after a declined card, for more than 15 minutes. Payment Service's WARN `Reconciler could not check
transaction <reference>: <error>` means the reconciler's read failed.

Check in `payment_db`, then look up the PaymentIntent `provider_transaction_id` in the Dashboard:

```sql
SELECT status, amount, currency, provider_transaction_id, provider_event_id, last_reconciled_at, created_at, updated_at
  FROM payment_transactions WHERE transaction_reference = :'ref';
```

- `requires_payment_method`, `requires_confirmation`, `requires_action` or `processing`: the payment is not settled.
  Nothing to do.
- `succeeded`: the webhook was lost or refused. `PendingPaymentReconciler` runs every 5 minutes and applies it once the
  payment is 15 minutes old, until it is a day old
  ([ADR 0032](adr/0032-pending-payments-are-rechecked-with-the-provider.md)). An older one needs its event resent
  ([A missed Stripe event](#a-missed-stripe-event)). An ERROR about other terms is the next entry.
- `canceled`: the reconciler records the failure, and nothing is credited.

Verify: the row reaches `SUCCESS`, then follow [Paid but not credited](#paid-but-not-credited) to the credit.

### A webhook whose terms differ from the deposit

Signal: Payment Service's ERROR `Ignoring success event <id> for tx <reference>: the provider reports a different
<field>`, repeated each time the reconciler looks until the payment is a day old.

Check: the row's `amount` and `currency` against the PaymentIntent's, which Stripe shows in the currency's minor unit.

Action: nothing credits the payment. The intent was changed outside FlowWallet, or a defect sent other terms: stop and
escalate. Never set the row to `SUCCESS` by hand: that writes no `PaymentCompletedEvent`, so the wallet stays
uncredited, and it hides the disagreement.

### Paid but not credited

Follow one reference through each step, in order:

```sql
-- 1. payment_db: the payment
SELECT status, amount, currency FROM payment_transactions WHERE transaction_reference = :'ref';

-- 2. payment_db: its event
SELECT event_type, status, error_message
  FROM outbox_events WHERE aggregate_type = 'PaymentTransaction' AND aggregate_id = :'ref';

-- 3. wallet_db: what the consumer did with it
SELECT event_id, event_type, outcome, rejection_reason FROM processed_events WHERE transaction_reference = :'ref';

-- 4. wallet_db: the credit
SELECT wallet_id, entry_no, amount, balance_after, created_at
  FROM balance_history WHERE transaction_reference = :'ref' AND type = 'DEPOSIT';
```

1. `PENDING` or `FAILED` goes to [A deposit that stays PENDING](#a-deposit-that-stays-pending).
2. `FAILED` goes to [A failed row in Payment Service's outbox](#a-failed-row-in-payment-services-outbox). `PENDING` or
   `PROCESSING` is still being sent. `COMPLETED`, or no row once it was cleaned up after 7 days, means the event reached
   Kafka.
3. `REJECTED` goes to [Refused events](#refused-events). No row means lag on `flow-wallet-service` or a record on the
   wallet's dead-letter topic
   ([A record on the wallet's dead-letter topic](#a-record-on-the-wallets-dead-letter-topic)).
4. The `DEPOSIT` row is the credit, with the wallet's entry number and the balance after it.

### Balance sums

Each wallet's balance equals the sum of its ledger, with `DEPOSIT`, `TRANSFER_IN` and `WITHDRAWAL_RETURNED` counted in
and `TRANSFER_OUT` and `WITHDRAWAL` counted out. In `wallet_db`, the sums per currency and the wallets that differ:

```sql
WITH ledger AS (
  SELECT wallet_id,
         sum(CASE WHEN type IN ('DEPOSIT', 'TRANSFER_IN', 'WITHDRAWAL_RETURNED') THEN amount ELSE -amount END) AS net
    FROM balance_history GROUP BY wallet_id)
SELECT w.currency, sum(w.balance) AS balances, sum(coalesce(l.net, 0)) AS ledger
  FROM wallets w LEFT JOIN ledger l ON l.wallet_id = w.id
 GROUP BY w.currency ORDER BY w.currency;

WITH ledger AS (
  SELECT wallet_id,
         sum(CASE WHEN type IN ('DEPOSIT', 'TRANSFER_IN', 'WITHDRAWAL_RETURNED') THEN amount ELSE -amount END) AS net
    FROM balance_history GROUP BY wallet_id)
SELECT w.id, w.currency, w.balance, coalesce(l.net, 0) AS ledger
  FROM wallets w LEFT JOIN ledger l ON l.wallet_id = w.id
 WHERE w.balance <> coalesce(l.net, 0);
```

A wallet that differs from its ledger is a defect or a write by hand: stop and escalate.

Credited deposits against succeeded payments, per currency. Compare the two answers; a difference is a payment still
on its way, a refused event or a `FAILED` outbox row, which [Paid but not credited](#paid-but-not-credited) finds:

```sql
-- payment_db
SELECT currency, count(*), sum(amount) FROM payment_transactions WHERE status = 'SUCCESS' GROUP BY currency;

-- wallet_db
SELECT w.currency, count(*), sum(h.amount)
  FROM balance_history h JOIN wallets w ON w.id = h.wallet_id
 WHERE h.type = 'DEPOSIT' GROUP BY w.currency;
```

The platform's Stripe balance does not equal the wallets' sum: the platform carries Stripe's fee on each deposit, and a
top-up adds money that no wallet holds. What matters for withdrawals is the PLN reserve of
[Withdrawals refused for a short reserve](#withdrawals-refused-for-a-short-reserve) against the sum of the PLN wallets'
balances. While the reserve is below that sum, not every user can withdraw their whole balance, and the platform needs a
top-up.

### A missed Stripe event

`stripe listen` forwards events only while it runs, and keeps no history. The reconcilers cover most losses: a deposit
is asked about from 15 minutes to a day after it started, a created payout from 15 minutes after acceptance with no
end, and a paid payout for 10 days after it was paid. Outside those windows, for a deposit still unsettled a day after
it started or a payout that failed more than 10 days after it was paid, resend the event: from the Dashboard to a
registered endpoint, or with `stripe events resend <evt_...>` while `stripe listen` runs. A resent event goes through
the same checks as the first delivery, and one already applied changes nothing.

## Known gaps

- Wallet Service's `outbox` endpoint has no guard against requests from a browser, unlike Payment Service's actuator. A
  web page open on the operator's host could send it a `POST` and requeue the `FAILED` withdrawal commands. They are
  debited commands that Payment Service accepts once per reference, so no money moves twice, but use curl only.
- Wallet Service logs each reserve `503` and each preflight `502` at ERROR with a stack trace
  ([Known noise](#known-noise)).
- No metric counts `REJECTED` rows. Their ERROR line and the query in [Refused events](#refused-events) are the signal.
