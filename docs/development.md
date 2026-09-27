# Developing FlowWallet

This file explains how to set up FlowWallet locally, configure it, and run its tests. For what the
project is and its current status, see [README.md](../README.md). For how the system works, see
[ARCHITECTURE.md](../ARCHITECTURE.md). The other reference docs cover the [API](api.md), the
[data model](data-model.md) and [Kafka topics and events](events.md).

## Getting started

### Prerequisites

- JDK 25 (for example Amazon Corretto 25).
- Maven is optional: `./mvnw` downloads Maven 3.9.9 on first run.
- Docker and Docker Compose.
- A Stripe account in test mode, plus the [Stripe CLI](https://docs.stripe.com/cli/install) to forward
  webhooks locally.

### 1. Configure environment

Copy the committed template. The real `.env` is git-ignored.

```bash
cp .env.example .env
```

Set `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET` (see step 5) and `POSTGRES_PASSWORD`. Everything else has a
working local default.

Docker Compose and all three services read this one file (the services import it through
`spring.config.import`), so each value lives in one place. Exported environment variables override it. Keep
the values unquoted, because the services read the file as `.properties`, where quotes become part of the
value. Postgres only takes the password when its volume is first created, so to change it later run
`docker compose down -v` first.

### 2. Start infrastructure

```bash
docker compose up -d
```

This starts PostgreSQL 17 (creating `wallet_db` and `payment_db`), a single-node Kafka broker (KRaft) and
Kafka-UI at `http://localhost:8090`. The services themselves aren't part of Compose, since there are no
service images yet.

Kafka-UI has no authentication of its own, so Compose runs it read-only (`KAFKA_CLUSTERS_0_READONLY`): it can
browse topics and consumer groups but not produce to `payment.events`, which the wallet consumer would credit
without question ([ADR 0010](adr/0010-idempotent-payment-event-consumer.md)).

All three ports are bound to `127.0.0.1` only. If one is already taken, say by another project's Postgres on
`5432`, set `DB_PORT`, `KAFKA_EXTERNAL_PORT` or `KAFKA_UI_PORT` in `.env`. The services pick up `DB_PORT`
from the same file. If you change `KAFKA_EXTERNAL_PORT`, set `KAFKA_BOOTSTRAP_SERVERS=localhost:<port>` too.

### 3. Build

```bash
./mvnw clean install
```

### 4. Run the services

All three need to run. Start them after Compose: Payment Service and Wallet Service don't start while Kafka is
unreachable, because they create their topics at startup. In separate terminals:

```bash
./mvnw -pl flow-wallet-gateway spring-boot:run
```

```bash
./mvnw -pl flow-wallet-payment spring-boot:run
```

```bash
./mvnw -pl flow-wallet-service spring-boot:run
```

### 5. Forward Stripe webhooks

```bash
stripe listen --forward-to localhost:8080/api/payments/webhooks/stripe
```

When it starts, `stripe listen` prints a webhook signing secret (`whsec_...`) and signs every event it
forwards with it. Put that value in `STRIPE_WEBHOOK_SECRET` and restart Payment Service. Until then webhooks
are disabled: Payment Service logs a WARN at startup, refuses every webhook with `400` without checking it,
and nothing gets credited. An empty value, a placeholder such as `whsec_dummy`, or anything that doesn't start
with `whsec_` counts as unset ([ADR 0017](adr/0017-webhooks-verified-before-they-are-read.md)).

### 6. Try a deposit

Go through the gateway, using any version 4 UUID as the user:

```bash
USER=$(uuidgen | tr 'A-Z' 'a-z')

curl -X POST localhost:8080/api/wallets \
  -H "X-User-Id: $USER" -H 'Content-Type: application/json' -d '{"currency":"USD"}'

curl -X POST localhost:8080/api/wallets/USD/deposits \
  -H "X-User-Id: $USER" -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' -d '{"amount":50.00}'
```

The deposit returns `providerData.clientSecret`. A real client would confirm the payment with Stripe.js. From
a terminal you can confirm it with a test card instead. The PaymentIntent id is the part of the client
secret before `_secret_`, and `return_url` is required because the intent accepts every payment method
enabled in your dashboard, and some of those redirect:

```bash
curl https://api.stripe.com/v1/payment_intents/<pi_...>/confirm \
  -u "<your sk_test_ key>:" -d payment_method=pm_card_visa -d return_url=https://example.com
```

Stripe sends the webhook, the outbox publishes, and the wallet credits the balance:

```bash
curl localhost:8080/api/wallets/USD -H "X-User-Id: $USER"
curl localhost:8080/api/wallets/USD/history -H "X-User-Id: $USER"
```

## Configuration

Every setting except the webhook signing secret has a local default and can be overridden in `.env` or with
an environment variable. The Stripe API key default (`sk_test_dummy`) only lets the services start, and
`STRIPE_WEBHOOK_SECRET` has no default, so webhooks stay disabled until it is set: no payment works until you
set real test values for both. `.env.example` has the full list with comments. The main ones:

| Variable | Default | Used by |
|----------|---------|---------|
| `GATEWAY_PORT` | `8080` | Gateway |
| `WALLET_SERVICE_PORT` | `8081` | Wallet (listen port), Gateway (routing, unless `WALLET_SERVICE_URI` is set) |
| `PAYMENT_SERVICE_PORT` | `8082` | Payment (listen port), Gateway (routing, unless `PAYMENT_SERVICE_URI` is set), Wallet (calls it, unless `WALLET_PAYMENT_BASE_URL` is set) |
| `WALLET_SERVICE_ADDRESS` / `PAYMENT_SERVICE_ADDRESS` | `127.0.0.1` / `127.0.0.1` | Wallet, Payment: the interface each binds to. Loopback keeps their unauthenticated API and actuator off the LAN when run directly on a host; a container image sets these to `0.0.0.0` so the service is still reachable from other containers. The gateway is the intended public entry point and keeps listening on every interface |
| `GATEWAY_HTTPCLIENT_RESPONSE_TIMEOUT` / `_CONNECT_TIMEOUT_MS` | `20s` / `2000` | Gateway: how long it waits for an upstream response and a connection, before the elastic Netty pool would otherwise wait forever. `20s` sits above the wallet's own worst case for a deposit call to Payment Service (`WALLET_PAYMENT_CONNECT_TIMEOUT` + `WALLET_PAYMENT_READ_TIMEOUT`, `2s` + `10s`), so that timeout fires first in the ordinary case |
| `DB_HOST` / `DB_PORT` | `localhost` / `5432` | Payment, Wallet; `DB_PORT` is also the host port Compose publishes Postgres on |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `flowadmin` / `flowsecret` | Payment, Wallet and Docker Compose |
| `PAYMENT_DB_NAME` / `WALLET_DB_NAME` | `payment_db` / `wallet_db` | Payment / Wallet (the init script always creates these two names) |
| `DB_POOL_MAX_SIZE` / `DB_POOL_MIN_IDLE` | `10` / `2` | Payment, Wallet |
| `DB_POOL_CONNECTION_TIMEOUT_MS` | `5000` | Payment, Wallet: how long a request waits for a pooled connection. It must stay below `GATEWAY_HTTPCLIENT_RESPONSE_TIMEOUT` and `WALLET_PAYMENT_READ_TIMEOUT`, so a database the service cannot reach answers the caller with the service's `503` rather than a timeout further up ([ADR 0025](adr/0025-unreachable-database-answers-503.md)) |
| `DB_LOG_SERVER_ERROR_DETAIL` | `false` | Payment, Wallet: whether exception messages carry Postgres' DETAIL line. `true` puts user ids and balances into the logs (a duplicate key's values, a refused row), so set it only for local debugging ([ADR 0027](adr/0027-user-ids-stay-out-of-logs-and-provider-metadata.md)) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Payment, Wallet |
| `KAFKA_TOPIC_PAYMENT_EVENTS_PARTITIONS` / `_REPLICAS` / `_MIN_INSYNC_REPLICAS` | `3` / `1` / `1` | Payment: applied when the topic is created. A three-broker cluster uses `3` / `2` for the last two; see [events.md](events.md) |
| `KAFKA_TOPIC_PAYMENT_EVENTS_DLT_PARTITIONS` / `_REPLICAS` | `3` / `1` | Wallet |
| `KAFKA_CONSUMER_GROUP_ID` | `flow-wallet-service` | Wallet |
| `KAFKA_LISTENER_CONCURRENCY` | `3` | Wallet |
| `WALLET_CONSUMER_RETRY_MAX_ATTEMPTS` | `3` | Wallet: retries before dead-lettering, with backoff from `WALLET_CONSUMER_RETRY_INITIAL_INTERVAL_MS` (`500`) up to `_MAX_INTERVAL_MS` (`10000`, at most `60000`) by `_MULTIPLIER` (`2.0`). The backoff sleeps on the consumer thread without polling, so the cap stays well below Kafka's `max.poll.interval.ms` (`300000`) |
| `WALLET_PAYMENT_BASE_URL` | `http://localhost:${PAYMENT_SERVICE_PORT}` | Wallet (Payment Service's own address, not the gateway's) |
| `WALLET_PAYMENT_CONNECT_TIMEOUT` / `_READ_TIMEOUT` | `2s` / `10s` | Wallet; at least 1ms each |
| `WALLET_PAYMENT_PROVIDER` | `STRIPE` | Wallet; `STRIPE` is the only accepted value |
| `STRIPE_API_KEY` / `STRIPE_WEBHOOK_SECRET` | `sk_test_dummy` / none (webhooks disabled) | Payment |
| `STRIPE_WEBHOOK_TOLERANCE_SECONDS` | `300` | Payment: how old a webhook's signed timestamp may be |
| `STRIPE_API_CONNECT_TIMEOUT` / `_READ_TIMEOUT` | `2s` / `6s` | Payment: how long a Stripe API call may take to connect and to answer |
| `STRIPE_API_MAX_NETWORK_RETRIES` | `0` | Payment: how often stripe-java retries a failed call under the same idempotency key. Keep (1 + retries) × (connect + read), plus about 0.5s of backoff per retry, below `WALLET_PAYMENT_READ_TIMEOUT` |
| `PAYMENT_WEBHOOK_MAX_PAYLOAD_SIZE` | `256KB` | Payment: a larger webhook body gets `413` |
| `PAYMENT_MIN_DEPOSIT_AMOUNT` / `PAYMENT_MAX_DEPOSIT_AMOUNT` | `1.00` / `10000.00` | Payment |
| `OUTBOX_POLL_INTERVAL_MS` / `OUTBOX_BATCH_SIZE` | `10000` / `50` | Payment |
| `OUTBOX_MAX_RETRIES` | `10` | Payment: send attempts, the first included, before a row becomes `FAILED`, with backoff from `OUTBOX_RETRY_BACKOFF_BASE_MS` (`1000`) doubling up to `_MAX_MS` (`60000`). The default spends about four minutes in backoff (1, 2, 4, 8, 16, 32 s, then 60 s three times); the poll interval and each send's wait on the broker (up to the producer's `max.block.ms`, 60 s by default, when it is unreachable) add to that |
| `OUTBOX_REAPER_INTERVAL_MS` / `OUTBOX_STUCK_PROCESSING_THRESHOLD_MS` | `60000` / `300000` | Payment |
| `OUTBOX_RETENTION_DAYS` / `OUTBOX_CLEANUP_CRON` | `7` / `0 0 3 * * *` | Payment (`COMPLETED` rows only) |
| `ACTUATOR_EXPOSED_ENDPOINTS` | Payment `health,info,metrics,outbox`; Wallet `health,info,metrics`; Gateway `health,info` | All three. It is one shared name, so a value set in `.env` replaces all three defaults at once; the template leaves it commented for that reason |
| `LOG_LEVEL` / `APP_LOG_LEVEL` | `INFO` / `DEBUG` | Payment, Wallet |
| `KAFKA_EXTERNAL_PORT` / `KAFKA_UI_PORT` | `9092` / `8090` | Docker Compose |

The wallet service won't start with values that make no sense, such as a negative retry count, a retry multiplier below
1.0, an initial interval above the maximum, a maximum interval above 60000 ms, a blank Payment Service URL, a Payment
Service timeout below 1ms, or a provider other than `STRIPE`. The payment service does the same for a deposit range that
is inverted, not positive, or too wide for `NUMERIC(19,4)`, a Stripe timeout below 1ms or a negative Stripe retry count,
a webhook tolerance that isn't positive, a webhook size limit outside 1B to 16MB, an outbox batch size, attempt count,
backoff, retention or stuck-processing threshold below 1, an outbox backoff base above its maximum, and a
`payment.events` topic with more in-sync replicas than replicas. A missing webhook secret doesn't stop it starting; it
only disables webhooks.

## Testing

There are 520 tests, all green: 245 in the payment service, 230 in the wallet service, 44 in platform and 1 in the
gateway (it binds the gateway's own `application.yml` into Spring Cloud Gateway's `HttpClientProperties`, so a YAML
regression that drops the response or connect timeout fails here rather than in a live request left waiting). The rest
go after the parts most likely to be wrong rather than the ones easiest to reach. That means the asymmetric webhook
state machine (a later failure must not undo an earlier success, but a later success must override an earlier failure),
the outbox's claim, retry and backoff boundaries, Stripe signature verification against payloads signed with a real
secret (placeholder secrets and malformed headers refused, nothing parsed before the check), the webhook size cap, the
check of a success against the stored amount and currency, the RFC 9457 status mapping (a database the service cannot
reach answers `503`, any other data access failure `500`, and no detail quotes a rejected currency, provider,
transaction reference or parameter value), the minor-unit conversion that decides how much money actually leaves a card,
the currencies Stripe charges and its minimum charges checked before a row is reserved, a Stripe refusal told apart from
a failure (400 against 502) and the wallet's three kinds of 502, a deposit that loses the reservation to its own twin, a
second recording of the same Stripe answer, the Stripe call's timeouts and retries, the optimistic-lock retry on both
webhook paths, the field names of the internal intent call on both sides of it, the identity and idempotency-key rules,
and a log line never naming a user id or a Stripe refusal repeating its stack trace after the handler already logged it.
Against each service's shipped `application.yml` they check that the datasource URL keeps Postgres' error detail out of
exception messages and that a request waits at most five seconds for a pooled connection. For the wallet consumer they
cover dispatch on the `eventType` header, dead-lettering of unreadable records, refusals (an event amount off its
currency's grid among them), duplicate classification (by the `DEPOSIT` entry alone, since one reference can own one
movement of each type), the barrier row being written before the wallet is loaded, failed payments never touching a
wallet (a redelivered failure is acknowledged, any other violation is raised), and the error handler (an unreadable
record dead-lettered at once, any other failure only after its retries, each dead letter counted, and the dead-letter
topic created with unlimited retention).

For transfers they cover the lock order in both directions, the key judged only after both locks, and no other wallet
read in the transaction. With the sender sorting first and last, they cover a retry that still gets its receipt after
the balance was spent, funds checked before the recipient, a recipient without a wallet, and both ledger legs with their
counterparties and balances. A completed transfer and a refused recipient are each logged by wallet id, never by user
id. They also cover every cause of a `409`, the status of each refusal, the receipt rendering to the same bytes on a
replay, the amount grid (trailing zeros, zero-decimal currencies, the size bound), the recipient id and key rules
(including a fifth dash and non-ASCII digits, on the deposit key as well), a `406` before the service runs (also for a
deposit and a wallet opening), `401` coming before the header, body and parameter checks on every wallet endpoint (and
on Payment Service's intent endpoint), violations and lock failures leaving the handler unchanged, the explanation of a
violation after the rollback, the `503` mapping, and the READ COMMITTED pin.

For the ledger they cover entry numbers rising by one per wallet across credits and both transfer legs, a
refused debit taking no number, history paged by entry number without skipping or repeating a movement while
credits arrive, the history `limit` bounds (1 to 100) and a non-numeric cursor refused with `400`, and every
field of the wallet and history responses through the real mapper.

```bash
./mvnw test
```

All of these are unit tests with mocked collaborators, so they can't prove the real database barrier: the unique
constraints on `processed_events.event_id` and `balance_history (transaction_reference, type)`, the classification by
read-back, and the row lock. Those have been exercised by hand against real Postgres and Kafka (redelivery, duplicate
references, forty concurrent credits to one wallet), but no automated test runs them yet. The hand check ran while the
classification read any entry under the reference, and the lookup of the `DEPOSIT` entry alone has run only against
mocks. The unique `balance_history (wallet_id, entry_no)`, the entry-number history queries and the migration that
numbers existing ledger rows were checked by hand on a scratch database seeded with interleaved ids: numbering follows
id order within each wallet, `last_entry_no` equals each wallet's row count, history pages by entry number, and a later
transfer takes the next number on both wallets. Payment changeset 006 ran by hand against a schema migrated through 005.

For transfers the mocks pin the order of the calls and the decision after each one. What they can't show was
checked by hand against real Postgres, through the running wallet service on a scratch database:

- 400 transfers between two wallets in both directions and 300 around a ring of three, over 16 threads: no
  deadlock, every reference with exactly one `TRANSFER_OUT` and one `TRANSFER_IN`, and each balance equal to
  its seed plus what came in minus what went out.
- Twenty parallel requests with one key: all `200` with identical bodies, and one pair of legs, so the money
  moved once.
- One key raced from two senders, with and without a wallet in common: one sender gets `200`, the other
  `409`. Without a shared wallet, the unique index settles the race.
- Two concurrent `25.00` transfers from a `30.00` wallet: one `200`, one `422`, and the balance ends at
  `5.00`.
- A replay after the balance was spent still gets the original receipt, byte for byte.
- The three CHECKs refuse a direct SQL write of a negative balance, a zero amount or a transfer leg without
  a counterparty.

No automated test runs these checks yet. Integration tests with Testcontainers are the roadmap's "Proof
that it works" stage.
