# Developing FlowWallet

This file explains how to set up FlowWallet locally, configure it, and run its tests. For what the project is and its
current status, see [README.md](../README.md). For how the system works, see [ARCHITECTURE.md](../ARCHITECTURE.md). The
other reference docs cover the [API](api.md), the [data model](data-model.md) and [Kafka topics and events](events.md),
and the [operator runbook](operations.md) says what to do when a withdrawal, payout or deposit is stuck. How to change
the code, and what has to change with it, is in [CONTRIBUTING.md](../CONTRIBUTING.md).

## Getting started

### Prerequisites

- JDK 25 (for example Amazon Corretto 25).
- Maven is optional: `./mvnw` downloads Maven 3.9.9 on first run.
- Docker and Docker Compose. A full build runs the integration tests, which start Postgres and Kafka through
  Testcontainers and need a running Docker daemon (see [Testing](#testing)).
- A Stripe account in test mode, plus the [Stripe CLI](https://docs.stripe.com/cli/install) to forward
  webhooks locally.

### 1. Configure environment

Copy the committed template. The real `.env` is git-ignored.

```bash
cp .env.example .env
```

Set `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET` and `STRIPE_CONNECT_WEBHOOK_SECRET` (see step 5) and `POSTGRES_PASSWORD`.
Everything else has a working local default.

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
stripe listen --forward-to localhost:8080/api/payments/webhooks/stripe \
  --forward-connect-to localhost:8080/api/payments/webhooks/stripe/connect
```

The first target receives the platform's events (deposits), the second the events of connected accounts (payouts and
bank accounts). When it starts, `stripe listen` prints a webhook signing secret (`whsec_...`) and signs every event it
forwards, to either target, with it. Put that value in both `STRIPE_WEBHOOK_SECRET` and `STRIPE_CONNECT_WEBHOOK_SECRET`
and restart Payment Service. Endpoints registered in the Stripe Dashboard each have their own secret: the Connect one is
registered for connected accounts with the SDK's API version, `2026-06-24.dahlia`, and its secret goes in
`STRIPE_CONNECT_WEBHOOK_SECRET`. `stripe listen` delivers events in the account's default API version instead, and
Payment Service logs a WARN for each one it reads that way
([ADR 0036](adr/0036-connect-webhooks-have-their-own-endpoint-and-secret.md)). Until then that endpoint's webhooks are
disabled: Payment Service logs a WARN at startup and refuses every delivery to it with `400` without checking it. A paid
deposit is then credited only when the reconciler finds it, 15 minutes or more after it started
([ADR 0032](adr/0032-pending-payments-are-rechecked-with-the-provider.md)), and a payout settles only when the payout
reconciler asks Stripe about it. An empty value, a placeholder such as `whsec_dummy`, or anything that doesn't start
with `whsec_` counts as unset ([ADR 0017](adr/0017-webhooks-verified-before-they-are-read.md)).

### 6. Try a deposit

Go through the gateway, using any version 4 UUID as the user:

```bash
USER=$(uuidgen | tr 'A-Z' 'a-z')

curl -X POST localhost:8080/api/wallets \
  -H "X-User-Id: $USER" -H 'Content-Type: application/json' -d '{"currency":"USD"}'

curl -X POST localhost:8080/api/wallets/USD/deposits \
  -H "X-User-Id: $USER" -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' -d '{"amount":"50.00"}'
```

The deposit returns `providerData.clientSecret`. A real client would confirm the payment with Stripe.js. From
a terminal you can confirm it with a test card instead. The PaymentIntent id is the part of the client
secret before `_secret_`. The intent accepts cards only, so the confirm needs no `return_url`:

```bash
curl https://api.stripe.com/v1/payment_intents/<pi_...>/confirm \
  -u "<your sk_test_ key>:" -d payment_method=pm_card_visa
```

Stripe sends the webhook, the outbox publishes, and the wallet credits the balance:

```bash
curl localhost:8080/api/wallets/USD -H "X-User-Id: $USER"
curl localhost:8080/api/wallets/USD/history -H "X-User-Id: $USER"
```

### 7. Try a withdrawal

A withdrawal pays PLN out to a Stripe test bank account, so it needs a PLN wallet with money in it and a bank account.
Reuse `$USER` from the deposit. Open the wallet, deposit 100.00 PLN and confirm the payment as in step 6, but with the
card `pm_card_bypassPendingInternational`. Stripe makes that card's funds available at once, so the deposit also fills
the platform's reserve, the Stripe balance a payout is paid from. Withdraw less than you deposited, because the Stripe
fee of each deposit shrinks the reserve ([ADR 0035](adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md)).

```bash
curl -X POST localhost:8080/api/wallets \
  -H "X-User-Id: $USER" -H 'Content-Type: application/json' -d '{"currency":"PLN"}'

curl -X POST localhost:8080/api/wallets/PLN/deposits \
  -H "X-User-Id: $USER" -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' -d '{"amount":"100.00"}'

curl https://api.stripe.com/v1/payment_intents/<pi_...>/confirm \
  -u "<your sk_test_ key>:" -d payment_method=pm_card_bypassPendingInternational

curl localhost:8080/api/wallets/PLN -H "X-User-Id: $USER"
```

Wait until the balance reads `100.00`, because a withdrawal refused for funds spends its key.

The bank account is a token. The client creates it with the publishable key, so bank details never reach FlowWallet. Use
Stripe's Polish test account number, which Stripe pays out to successfully, and send the token with the user's
acceptance of Stripe's Connected Account Agreement. The answer is the bank account's state, `READY` with `last4` `2874`:

```bash
curl https://api.stripe.com/v1/tokens -u "<your pk_test_ key>:" \
  -d "bank_account[country]=PL" -d "bank_account[currency]=pln" \
  -d "bank_account[account_number]=PL61109010140000071219812874"

curl -X PUT localhost:8080/api/wallets/bank-account \
  -H "X-User-Id: $USER" -H 'Content-Type: application/json' \
  -d '{"token":"<btok_...>","tosAccepted":true}'
```

Withdraw with a new key and keep the key, because it also addresses the withdrawal. `-i` shows the `202` and its
`Location`. The balance drops at once and the history shows a `WITHDRAWAL` entry. Sending the same request again returns
the same `202` and moves nothing. Read the withdrawal until its `status` is `PAID`:

```bash
KEY=$(uuidgen | tr 'A-Z' 'a-z')

curl -i -X POST localhost:8080/api/wallets/PLN/withdrawals \
  -H "X-User-Id: $USER" -H "Idempotency-Key: $KEY" \
  -H 'Content-Type: application/json' -d '{"amount":"20.00"}'

curl localhost:8080/api/wallets/PLN/withdrawals/$KEY -H "X-User-Id: $USER"
```

The payout settles from Stripe's Connect webhook, which needs `stripe listen` with `--forward-connect-to` (step 5) and
`STRIPE_CONNECT_WEBHOOK_SECRET`. Without it the payout reconciler settles the withdrawal, first 15 minutes after it was
accepted.

## Configuration

Every setting except the two webhook signing secrets has a local default and can be overridden in `.env` or with an
environment variable. The Stripe API key default (`sk_test_dummy`) only lets the services start, and
`STRIPE_WEBHOOK_SECRET` and `STRIPE_CONNECT_WEBHOOK_SECRET` have no default, so webhooks stay disabled until they are
set: no payment works without a real test API key, and without the signing secrets a paid deposit and a payout wait for
their reconcilers. `.env.example` has the full list with comments. The main ones:

| Variable | Default | Used by |
|----------|---------|---------|
| `GATEWAY_PORT` | `8080` | Gateway |
| `WALLET_SERVICE_PORT` | `8081` | Wallet (listen port), Gateway (routing, unless `WALLET_SERVICE_URI` is set) |
| `PAYMENT_SERVICE_PORT` | `8082` | Payment (listen port), Gateway (routing, unless `PAYMENT_SERVICE_URI` is set), Wallet (calls it, unless `WALLET_PAYMENT_BASE_URL` is set) |
| `GATEWAY_ADDRESS` / `WALLET_SERVICE_ADDRESS` / `PAYMENT_SERVICE_ADDRESS` | `127.0.0.1` each | Gateway, Wallet, Payment: the interface each binds to. Loopback keeps their unauthenticated API and actuator off the LAN when run directly on a host; a container has to set these to `0.0.0.0` so the service is still reachable from other containers. The gateway logs a WARN at startup when it listens beyond loopback, because it must then sit behind an authentication layer that sets `X-User-Id` ([ADR 0031](adr/0031-callers-are-authenticated-in-front-of-the-gateway.md)) |
| `GATEWAY_HTTPCLIENT_RESPONSE_TIMEOUT` / `_CONNECT_TIMEOUT_MS` | `20s` / `2000` | Gateway: how long it waits for an upstream response and a connection, before the elastic Netty pool would otherwise wait forever. `20s` sits above the wallet's own worst case for a deposit call to Payment Service (`WALLET_PAYMENT_CONNECT_TIMEOUT` + `WALLET_PAYMENT_READ_TIMEOUT`, `2s` + `10s`), so that timeout fires first in the ordinary case. A withdrawal adds up to three pool waits of `DB_POOL_CONNECTION_TIMEOUT_MS` to its preflight and can outlast it: the gateway then answers `504` for a debit that may still commit, and a retry with the same key gets its `202` ([ADR 0033](adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md)) |
| `DB_HOST` / `DB_PORT` | `localhost` / `5432` | Payment, Wallet; `DB_PORT` is also the host port Compose publishes Postgres on |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `flowadmin` / `flowsecret` | Payment, Wallet and Docker Compose |
| `PAYMENT_DB_NAME` / `WALLET_DB_NAME` | `payment_db` / `wallet_db` | Payment / Wallet (the init script always creates these two names) |
| `DB_POOL_MAX_SIZE` / `DB_POOL_MIN_IDLE` | `10` / `2` | Payment, Wallet |
| `DB_POOL_CONNECTION_TIMEOUT_MS` | `5000` | Payment, Wallet: how long a request waits for a pooled connection. It must stay below `GATEWAY_HTTPCLIENT_RESPONSE_TIMEOUT` and `WALLET_PAYMENT_READ_TIMEOUT`, so a database the service cannot reach answers the caller with the service's `503` rather than a timeout further up ([ADR 0025](adr/0025-unreachable-database-answers-503.md)). A withdrawal checks out a connection three times, so its waits can add up to three times this value |
| `DB_LOG_SERVER_ERROR_DETAIL` | `false` | Payment, Wallet: whether exception messages carry Postgres' DETAIL line. `true` puts user ids and balances into the logs (a duplicate key's values, a refused row), so set it only for local debugging ([ADR 0027](adr/0027-user-ids-stay-out-of-logs-and-provider-metadata.md)) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Payment, Wallet |
| `KAFKA_TOPIC_PAYMENT_EVENTS_PARTITIONS` / `_REPLICAS` / `_MIN_INSYNC_REPLICAS` | `3` / `1` / `1` | Payment: applied when the topic is created. A three-broker cluster uses `3` / `2` for the last two; see [events.md](events.md) |
| `KAFKA_TOPIC_PAYMENT_EVENTS_DLT_PARTITIONS` / `_REPLICAS` | `3` / `1` | Wallet |
| `KAFKA_TOPIC_WALLET_WITHDRAWALS_PARTITIONS` / `_REPLICAS` / `_MIN_INSYNC_REPLICAS` | `3` / `1` / `1` | Wallet: applied when `wallet.withdrawals` is created; on an existing topic only a higher partition count and, because the wallet sets `modify-topic-configs`, a changed `_MIN_INSYNC_REPLICAS` take effect |
| `KAFKA_CONSUMER_GROUP_ID` | `flow-wallet-service` | Wallet |
| `KAFKA_LISTENER_CONCURRENCY` | `3` | Wallet |
| `PAYMENT_KAFKA_CONSUMER_GROUP_ID` / `PAYMENT_KAFKA_LISTENER_CONCURRENCY` | `flow-wallet-payment` / `3` | Payment: its consumer of `wallet.withdrawals`, under names of its own because both services read the same `.env` |
| `PAYMENT_CONSUMER_RETRY_MAX_ATTEMPTS` / `_INITIAL_INTERVAL_MS` / `_MAX_INTERVAL_MS` / `_MULTIPLIER` | `3` / `500` / `10000` / `2.0` | Payment: retries before a withdrawal command is dead-lettered, with the same bounds as the wallet's |
| `KAFKA_TOPIC_WALLET_WITHDRAWALS_DLT_PARTITIONS` / `_REPLICAS` | `3` / `1` | Payment: applied when `wallet.withdrawals.payment.DLT` is created; on an existing topic only a higher partition count takes effect |
| `WALLET_CONSUMER_RETRY_MAX_ATTEMPTS` | `3` | Wallet: retries before dead-lettering, with backoff from `WALLET_CONSUMER_RETRY_INITIAL_INTERVAL_MS` (`500`) up to `_MAX_INTERVAL_MS` (`10000`, at most `60000`) by `_MULTIPLIER` (`2.0`). The backoff sleeps on the consumer thread without polling, so the cap stays well below Kafka's `max.poll.interval.ms` (`300000`) |
| `WALLET_PAYMENT_BASE_URL` | `http://localhost:${PAYMENT_SERVICE_PORT}` | Wallet (Payment Service's own address, not the gateway's) |
| `WALLET_PAYMENT_CONNECT_TIMEOUT` / `_READ_TIMEOUT` | `2s` / `10s` | Wallet; at least 1ms each. A bank account request makes up to five Stripe calls in a row and can outlast the read timeout; the wallet then answers `502`, and the retry with the same token is safe, because each Stripe write is keyed and a read changes nothing. A withdrawal's preflight makes one Stripe read, 8s with the default Stripe timeouts; when this timeout ends the wait first, the wallet answers `502` with nothing debited |
| `WALLET_PAYMENT_PROVIDER` | `STRIPE` | Wallet; `STRIPE` is the only accepted value |
| `STRIPE_API_KEY` / `STRIPE_WEBHOOK_SECRET` | `sk_test_dummy` / none (webhooks disabled) | Payment: only a test-mode key (`sk_test_` or `rk_test_`) starts |
| `STRIPE_CONNECT_WEBHOOK_SECRET` | none (Connect webhooks disabled) | Payment: the secret of the endpoint for connected accounts, `/api/payments/webhooks/stripe/connect`; under `stripe listen` the same value as `STRIPE_WEBHOOK_SECRET` ([ADR 0036](adr/0036-connect-webhooks-have-their-own-endpoint-and-secret.md)) |
| `STRIPE_WEBHOOK_TOLERANCE_SECONDS` | `300` | Payment: how old a webhook's signed timestamp may be, on both endpoints |
| `STRIPE_PAYMENT_METHOD_TYPES` | `card` | Payment: the methods a deposit can be paid with. `card` is the only known one; any other name, or an empty value, fails startup |
| `STRIPE_API_CONNECT_TIMEOUT` / `_READ_TIMEOUT` | `2s` / `6s` | Payment: how long a Stripe API call may take to connect and to answer |
| `STRIPE_API_MAX_NETWORK_RETRIES` | `0` | Payment: how often stripe-java retries a failed call under the same idempotency key. Keep (1 + retries) × (connect + read), plus stripe-java's backoff of 0.5s doubling per retry, below `WALLET_PAYMENT_READ_TIMEOUT`; three such calls must fit `PAYMENT_PAYOUT_EXECUTOR_LEASE` |
| `PAYMENT_WEBHOOK_MAX_PAYLOAD_SIZE` | `256KB` | Payment: a larger webhook body gets `413` |
| `PAYMENT_RECONCILIATION_INTERVAL_MS` / `_MIN_AGE` / `_MAX_AGE` / `_BATCH_SIZE` | `300000` / `15m` / `24h` / `50` | Payment: how often the reconciler asks Stripe about deposits still pending or failed, from what age and until what age, and how many per run; `PAYMENT_RECONCILIATION_ENABLED` (`true`) turns it off ([ADR 0032](adr/0032-pending-payments-are-rechecked-with-the-provider.md)) |
| `PAYMENT_SCHEDULING_ENABLED` | `true` | Payment: `false` stops every scheduled job, the outbox poller, the reconciler, the payout executor and the payout reconciler included; only for a test that drives those steps itself |
| `PAYMENT_PAYOUT_CURRENCY` / `PAYMENT_PAYOUT_MIN_AMOUNT` | `PLN` / `5.00` | Payment: the currency a withdrawal is paid out in and its minimum ([ADR 0035](adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md)) |
| `PAYMENT_PAYOUT_EXECUTOR_ENABLED` / `_INTERVAL_MS` / `_BATCH_SIZE` / `_LEASE` | `true` / `10000` / `20` / `2m` | Payment: whether the payout executor runs, its pause between runs, rows per run, and how long a claim owns a row. The lease must cover three Stripe calls plus two 10s margins (44s with the defaults); instances' clocks must agree to within that margin. Outcomes are counted in `payment.payouts.executor.outcomes` (tags `step`, `outcome`), hand-overs to an operator in `payment.payouts.needs.attention` (tags `from`, `cause`) and an operator's actions in `payment.payouts.operator.actions` (tag `action`: `requeue`, `return_without_reversal` or `reverse`; tag `outcome`: `applied`, `refused` for a `404` or `422`, `retry` for a `503` from the quiet period or a changed row, or `stripe_unavailable` for a `502`). The gauge `payment.payouts.awaiting.operator` (tag `from`: `accepted`, `transferred` or `reversing`) is the number of payouts waiting for an operator. It reads the database each time it is read, and exists whether or not the `payouts` endpoint is exposed. The alerts on it and what each value means are in [the operator runbook](operations.md#alerts) |
| `PAYMENT_PAYOUT_EXECUTOR_UNKNOWN_*` / `_PAYOUT_RETRY_*` / `_REVERSAL_RETRY_*` | see `.env.example` | Payment: how long an unknown outcome, a payout refused for funds or a limit, and a refused reversal are retried before the money comes back or an operator takes over (about 7 h, 24 h and 6 h by default). `_UNKNOWN_BACKOFF` is also how long after a hand-over the `payouts` endpoint waits before it returns or reverses a payout ([api.md](api.md#operator-the-payouts-actuator-endpoint-8082-directly)) |
| `PAYMENT_PAYOUT_RECONCILER_ENABLED` / `_INTERVAL_MS` / `_BATCH_SIZE` / `_RUN_BUDGET` | `true` / `60000` / `50` / `60s` | Payment: whether the payout reconciler runs, its pause between runs, payouts asked about per run (one Stripe read each) and how long a run may take. It settles a payout whose Connect webhook is lost; outcomes are counted in `payment.payouts.reconciler.outcomes` (tags `status`, `outcome`) ([ADR 0036](adr/0036-connect-webhooks-have-their-own-endpoint-and-secret.md)) |
| `PAYMENT_PAYOUT_RECONCILER_CREATED_MIN_AGE` / `_PAID_RECHECK` / `_PAID_PERIOD` | `15m` / `6h` / `10d` | Payment: a created payout is asked about from this age on and at most this often; a paid one every `_PAID_RECHECK` for `_PAID_PERIOD` after it was paid, because Stripe can fail a paid payout days later. `_PAID_RECHECK` must be shorter than `_PAID_PERIOD` |
| `WALLET_SCHEDULING_ENABLED` | `true` | Wallet: `false` stops the outbox poller, reaper and cleanup; only for a test that drives those steps itself |
| `PAYMENT_MIN_DEPOSIT_AMOUNT` / `PAYMENT_MAX_DEPOSIT_AMOUNT` | `1.00` / `10000.00` | Payment |
| `OUTBOX_POLL_INTERVAL_MS` / `OUTBOX_BATCH_SIZE` | `10000` / `50` | Payment, Wallet |
| `OUTBOX_MAX_RETRIES` | `10` | Payment, Wallet: send attempts, the first included, before a row becomes `FAILED`, with backoff from `OUTBOX_RETRY_BACKOFF_BASE_MS` (`1000`) doubling up to `_MAX_MS` (`60000`). The default spends about four minutes in backoff (1, 2, 4, 8, 16, 32 s, then 60 s three times); the poll interval and each send's wait on the broker (up to the producer's `max.block.ms`, 60 s by default, when it is unreachable) add to that |
| `OUTBOX_REAPER_INTERVAL_MS` / `OUTBOX_STUCK_PROCESSING_THRESHOLD_MS` | `60000` / `300000` | Payment, Wallet |
| `OUTBOX_RETENTION_DAYS` / `OUTBOX_CLEANUP_CRON` | `7` / `0 0 3 * * *` | Payment, Wallet (`COMPLETED` rows only) |
| `ACTUATOR_EXPOSED_ENDPOINTS` | Payment and Wallet `health,info,metrics,outbox`; Gateway `health,info` | All three. It is one shared name, so a value set in `.env` replaces all three defaults at once; the template leaves it commented for that reason. The wallet's `outbox` endpoint requeues its `FAILED` rows, which are debited withdrawal commands Payment Service never received; the procedure is in [operations.md](operations.md). Payment's `payouts` endpoint is in no default, because its writes credit wallets and it authenticates nobody. Export it for the one Payment Service process, only while acting, as in `ACTUATOR_EXPOSED_ENDPOINTS=health,info,metrics,outbox,payouts ./mvnw -pl flow-wallet-payment spring-boot:run`, and never in `.env`, which all three services read. A value of `*` exposes it too. Its use is in [api.md](api.md#operator-the-payouts-actuator-endpoint-8082-directly) |
| `LOG_LEVEL` / `APP_LOG_LEVEL` | `INFO` / `DEBUG` | Payment, Wallet |
| `KAFKA_EXTERNAL_PORT` / `KAFKA_UI_PORT` | `9092` / `8090` | Docker Compose |

The wallet service won't start with values that make no sense, such as a negative retry count, a retry multiplier below
1.0, an initial interval above the maximum, a maximum interval above 60000 ms, a blank Payment Service URL, a Payment
Service timeout below 1ms, a provider other than `STRIPE`, a `wallet.withdrawals` topic with more in-sync replicas than
replicas, or an outbox batch size, attempt count, backoff, retention or stuck-processing threshold below 1 or a backoff
base above its maximum. The payment service does the same for a deposit range that is inverted, not positive, or too
wide for `NUMERIC(19,4)`, a Stripe timeout below 1ms or a negative Stripe retry count, a webhook tolerance that isn't
positive, a Stripe key that is not a test-mode key (`sk_test_` or `rk_test_`), a payment method list that is empty or
names a method it doesn't know, a webhook size limit outside 1B to 16MB, the same outbox values, a reconciler interval
below 1000 ms, a reconciler batch below 1, a reconciler `min-age` or `max-age` below 1s or a `min-age` that is not
shorter than the `max-age`, a `payment.events` topic with more in-sync replicas than replicas, a
`wallet.withdrawals.payment.DLT` topic with fewer than one partition or replica, consumer retry values the wallet would
refuse, a payout currency list that is empty, names a code that is not an upper-case ISO 4217 code, or has a minimum
that is missing, not positive, below Stripe's minimum payout where `StripePayoutMinimums` lists one for the account's
country, or off the payout grid, and a connected account identity with a blank field, a date of birth that is not in the
past, a country that is not two capital letters, a merchant category code that is not four digits or a terms IP that is
not an IPv4 address, and a payout executor whose lease does not cover three Stripe calls under the Stripe timeouts and
retries plus two 10s margins, or whose interval, lease, backoff or limits are out of range, and a payout reconciler
interval below 1000 ms, a batch below 1, a run budget or an age below 1s, or a `paid-recheck` that is not shorter than
the `paid-period`. A missing webhook secret, for either endpoint, doesn't stop it starting; it only disables that
endpoint's webhooks.

## Testing

There are 2089 tests, all green: 2044 unit tests (1384 in the payment service, 554 in the wallet service, 53 in the
outbox module, 44 in platform and 9 in the gateway) and 45 integration tests (41 in the wallet service, 4 in the
payment service).

`./mvnw test` runs the unit tests only. `./mvnw verify` and `./mvnw clean install` also run the integration tests
(`*IT`), which failsafe starts from each module's `target/` directory against `postgres:17-alpine` and
`apache/kafka:4.2.1` containers ([ADR 0039](adr/0039-integration-tests-run-per-service-on-testcontainers.md)). They need
a running Docker daemon and fail without one; `-DskipITs` leaves them out. Testcontainers looks for the daemon at
`/var/run/docker.sock`. OrbStack keeps its socket at `~/.orbstack/run/docker.sock` unless its setting to use the system
path is on, so either turn that on or export `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock` before the build. The
first build downloads the Testcontainers jars and the Ryuk image.

The integration tests cover the wallet's payment event consumer over a real broker and database: a completed payment
credits once, a redelivered event changes nothing and reaches no dead-letter topic, a redelivered failure is recorded
once, a failed payment retried to success is credited once, a failure that arrives after the credit takes nothing back,
a second event for a credited reference is stored as `DUPLICATE_REFERENCE`, and a payment for a user without a wallet is
stored with its payload and opens no wallet. An unreadable record goes to `payment.events.wallet.DLT`, is counted, stays
there without a time limit and leaves the next payment on its partition free to credit; a record with an unknown
`eventType` goes there too and credits nothing. Under concurrency, eight threads handling one event credit once and
leave one outcome, six events for one reference credit once and store five `DUPLICATE_REFERENCE` rows, and eight
different payments to one wallet at once are all credited. A deposit started through `POST
/api/wallets/{currency}/deposits` reaches a stub of Payment Service with the caller's key as its reference and the
caller's id, passes the provider's data back, and the completed payment that comes back is shown in the balance; a
deposit to a wallet the caller does not hold is refused before Payment Service is called. Transfers run over HTTP into
the service under real concurrency: 400 transfers between two wallets in both directions and 300 around a ring of three,
over 16 threads, all succeed without a deadlock, each with exactly one `TRANSFER_OUT` and one `TRANSFER_IN`, and every
balance ends where it started; twenty parallel requests with one key move the money once and get the same answer byte
for byte; one key raced from two senders, with and without a wallet in common, goes to one of them and the other gets
`409`; two `25.00` transfers from a `30.00` wallet let one through and refuse the other with `422`, and the winner's
replay is its first answer. The schema refuses direct SQL writes of a negative balance, a zero amount, a transfer leg
without a counterparty, a duplicate entry number, a second ledger row of one type under one reference and a lower-case
currency, each under its own constraint name. A withdrawal debits the wallet, writes its `PENDING` row and sends its
command from one commit, and the command on `wallet.withdrawals` has the shape of its golden sample; when the outbox row
cannot be written nothing is debited and the retry under the same key goes through once, and when the withdrawal's own
row fails after the command was queued no command is left behind; a missing bank account is refused with `422` under the
key and the retry repeats it, and a short platform reserve answers `503` with nothing written. Twenty withdrawals with
one key debit once and send one command, one key raced from two wallets goes to one of them, two withdrawals that
together overdraw a wallet let one through and record the other as refused, and withdrawals racing transfers for one
balance never overdraw it. Two returns for one withdrawal handled at once credit it back once, and a payout that is
paid, fails and comes back rises to `RETURNED` and is credited once. The schema's CHECKs on `withdrawals` refuse direct
SQL writes too. The tests publish the contract's golden wire samples, and after every test `LedgerAudit` checks that
each wallet's balance and entry numbers match its ledger.

In the payment service they run a deposit from the wallet's intent call to the record on `payment.events`: a signed
`payment_intent.succeeded` webhook settles the payment and its outbox row commits with it, the record the outbox sends
has the shape of the golden sample the wallet's tests publish, and a redelivered webhook adds no second event. A webhook
signed with another secret is refused and changes nothing. When the outbox row cannot be written, the payment stays
`PENDING` and Stripe's redelivery settles it once. A paid deposit whose webhook never came is settled by
`PendingPaymentReconciler` through the same handler, and the late webhook then adds nothing. `StripeClient` is a spy
that each test stubs; the signature check is real, and an unstubbed call fails against a closed local port.

Among the unit tests, the gateway's bind its own `application.yml`, so a YAML regression that drops the response or
connect timeout, or binds the gateway beyond loopback, fails there rather than in a live request; they also check that
`BindAddressCheck` runs when the application is ready, warns for `0.0.0.0`, a LAN address and no address at all, and
stays quiet for loopback. The rest go after the parts most likely to be wrong rather than the ones easiest to
reach. That means the asymmetric webhook state machine (a later failure must not undo an earlier success, but a later
success must override an earlier failure), the outbox's claim, retry and backoff boundaries, the outbox module's wiring
(a service without an `OutboxTopic` bean fails to start, and the entity and repository join the service's JPA scan),
Stripe signature verification against payloads signed with a real secret (placeholder secrets and malformed headers
refused, nothing parsed before the check), the webhook size cap, the check of a success against the stored amount and
currency, the RFC 9457 status mapping (a database the service cannot reach answers `503`, any other data access failure
`500`, and no detail quotes a rejected currency, provider, transaction reference or parameter value), the minor-unit
conversion that decides how much money actually leaves a card, the currencies Stripe charges and its minimum charges
checked before a row is reserved, a Stripe refusal told apart from a failure (400 against 502) and the wallet's three
kinds of 502, a deposit that loses the reservation to its own twin, a second recording of the same Stripe answer, the
Stripe call's timeouts and retries, the optimistic-lock retry on both webhook paths, the reconciler (each PaymentIntent
status mapped, a row another instance claimed left alone, a run stopped after three failed lookups in a row, settings
that cannot work refused, and the switch that turns scheduling off), the field names of the internal intent call on both
sides of it, the identity and idempotency-key rules, and a log line never naming a user id or a Stripe refusal repeating
its stack trace after the handler already logged it. Against each service's shipped `application.yml` they check that
the datasource URL keeps Postgres' error detail out of exception messages and that a request waits at most five seconds
for a pooled connection. For the wallet consumer they cover dispatch on the `eventType` header, dead-lettering of
unreadable records, refusals (an event amount off its currency's grid among them), duplicate classification (by the one
ledger type the write could have created, `DEPOSIT` or `WITHDRAWAL_RETURNED`, and by the event id alone for an event
that moves no money, since one reference can own one movement of each type), the barrier row being written before the
wallet is loaded, failed payments never touching a wallet (a redelivered failure is acknowledged, any other violation is
raised), payout events reaching their own steps, a Paid event refused without its payout id, a payout refusal stored
with its reason, a second return refused as `DUPLICATE_REFERENCE`, a withdrawal status that only rises, the payout
handler pinned to READ COMMITTED, and the error handler (an unreadable record dead-lettered at once, any other failure
only after its retries, each dead letter counted, and the dead-letter topic created with unlimited retention).

For Payment Service's consumer of withdrawal commands they cover the `eventType` header and the shape of a command (an
unreadable or invalid one dead-lettered at once), a redelivery with the same terms acknowledged and one with other terms
dead-lettered, a command the payout terms refuse returned with `TERMS_REFUSED` and its `PayoutReturnedEvent` written
after the row, any other failure dead-lettered only after its retries, each dead letter counted, and the dead-letter
topic created with unlimited retention.

For the payout calls to Stripe they cover the connected account opened under its key with the configured identity, a
manual payout schedule and no user id, a bank token attached as the default for its currency, the account's default bank
account for that currency read back after the attach (another request's included) and ready only when the account pays
out and its status is one Stripe pays out to, a refused token told apart from an unsettled attach, the unit each amount
is sent in, the platform's available card funds read for one currency in major units, the connected account named on a
payout and its lookups, the transfer group and the metadata that carry the reference and nothing else, the idempotency
key with its step and key generation, a refusal counted as definite only for a listed code on a 400 or 402 (a 409, a
404, a 5xx, a rate limit, a timeout or an unlisted code leaves the outcome unknown, and an idempotency error is a
defect), a payout's bank account, funds and daily limit refusals told apart, the payout lookup matched on its metadata,
a status this service does not know treated as one whose money may still reach the bank, a failed read never taken as an
empty answer, and a reversal that sends only the part not yet reversed or reports the earlier reversal labelled with the
reference, and a page that fails mid-list surfacing as a lookup failure.

For Payment Service's bank account endpoint they cover the first request reserving the row, opening the account under
its key and acceptance time and attaching the token, a later request attaching only the new token, the token last
applied answered from the row, a lost reservation continuing with the winner's row, a refused token answered `400` with
nothing recorded, an unsettled attach or an account Stripe did not open answered `502`, a bank account outside the
payout currencies recorded as not ready, the terms accepted at a whole second, a caller without a row read as `NOT_SET`,
`tosAccepted` and the `btok_` pattern checked before the service runs (a refused value is never quoted), `401` before
the body, the field names on both sides of the relay, and no log line naming the user.

For the preflight they cover a withdrawal outside the terms refused with a `400` before anything is read (a currency not
offered, an amount below the minimum, zero or negative, off the payout grid, over 15 integer digits, or with an exponent
that would wrap an int digit count), the detail never quoting the amount, no Stripe call while no bank account is ready
(one in another currency included), the owed sum read before the balance, the reserve as the available balance less what
untransferred payouts owe, a balance Stripe cannot report answered `502`, a lower-case or missing currency refused
before the service runs, `401` before the body, the field names, a configured minimum below Stripe's own for the
account's country refused at startup and one Stripe lists none for accepted, and only the card part of the balance
counted.

For the payout executor they cover each step claimed through its own repository update, a first transfer or payout try
that does not look up and a later one that looks for what Stripe holds before it creates (a transfer by its group, a
payout among the account's payouts since an hour before the transfer), a reversal step that lists on every try, a found
object adopted only when its terms and destination are the payout's, more than one found sent to an operator, a failed
payout found recorded and reversed, a later transfer try creating under the same key even when the bank account is no
longer ready, a move to `REVERSING` only after a listing found no payout that may reach the bank, a live payout stopping
a reversal, definite refusals returning or reversing by their reason while funds, limit and reversal refusals wait under
a fresh key, an unknown outcome or an unexpected exception backing off under the same key until its limit, an
idempotency error sent to an operator, no Stripe call without room on the lease at any of the step's guards, an outcome
recorded only under the claim's token (another claim or a transition first changes nothing and writes no event), each
transition with its outbox event, a bank rejection resetting only the bank account the claim saw and none on a later
try, whose refusal may be Stripe's stored answer, a run stopped by its budget, three failures in a row or an interrupt,
an unsettled outcome seen while interrupted given up without counting a try but waiting the unknown backoff, the token
at whole microseconds, the lease checked against three Stripe call budgets at startup, a claim that carries no user id,
metrics, log lines naming the reference and Stripe's ids, and the executor's switch.

For the Connect webhook and the payout reconciler they cover each endpoint verifying with its own secret (a body signed
with the platform secret refused by the Connect one, an unset, blank or placeholder Connect secret refusing every
delivery), the event's account and object id read from an event in another API version, the three payout types and the
two account types handled and any other ignored, an event without an account or a payout id ignored with a defect, the
`/stripe/connect` path never reaching the platform route, `400`, `413` and `500` as on the platform endpoint, a failed
Stripe read or a raced refresh answered `500`, every row status against every Stripe status (`paid` moving a created
payout once with one Paid event, `failed` or `canceled` reversing a payout however long ago it was paid with one Failed
event, a redelivery writing nothing), a `TRANSFERRED` row found by its reference adopting the payout first, a
`NEEDS_ATTENTION` row never adopting, a differing account, amount, currency, payout id or reference writing nothing, the
bank account reset only for a failed payout sent to the stored one, the executor's record after a settlement taken by
the transition, an account event setting the state from the default bank account Stripe holds (ready only in a payable
status, with payouts enabled and in a payout currency; a replaced default or a changed row skipped), created payouts
read first and paid ones filling the batch, each row claimed under its own status's cutoff before Stripe is read, a lost
claim costing no read, a run stopped by three failures in a row, its budget or an interrupt, a move logged and counted,
and the reconciler's settings, defaults and switch.

For the operator endpoint they cover a web-only endpoint under the id `payouts` whose write takes the reference and then
the action, a reference folded to lower case, an unknown action refused without being quoted and reaching no operation,
each action reaching its own operation, the list counting waiting payouts by step and saying when it is cut, a payout
offering only the actions its state allows, amounts printed on the payout grid and times as text, no answer carrying a
user id, the row's id or its version, a `requeue` that asks Stripe nothing, a return from the transfer step and a return
or reversal inside the quiet period refused before Stripe is asked, a return from `ACCEPTED` refused by a transfer not
reversed in full and passed when every transfer in the group is or none exists, a return from `REVERSING` refused while
a payout may reach the bank (pending, in transit, paid, unrecognized, or a failed one beside a paid one) and passed when
every payout failed or was canceled, the row's own payout retrieved when the listing lacks it, a transfer reversed in
full refused and the part left in the connected account reported, the listing window the executor's, a reversal refused
from `ACCEPTED` and `REVERSING` with the action to use, refused while a payout may reach the bank and inside the quiet
period, and sent to the executor otherwise, a failed Stripe read answered `502` and a row that changed while Stripe was
asked answered `503`, each writing nothing, the version read before Stripe was asked passed to the write, every Stripe
read ended before the write begins, no Stripe create ever called, operations that hold no transaction, refusals naming
Stripe's ids and never a user id or the rejected input, applied actions logged by reference, and actions counted by
action and outcome. For the store they cover the cause and detail recorded on a hand-over and cleared on every exit, a
requeue returning a waiting payout to its step with no event and changing nothing in any other status, a return or a
reversal writing exactly one event in the transaction that moves the row and nothing under an older version or once the
row left the wait, the waiting list oldest first up to its limit, and a snapshot without a user id. For the `Payout`
entity they cover the table of transitions with the operator's reversal, a detail cut to its column and a requeue that
keeps the key generation and the step's first attempt time. The gauge is checked to count waiting payouts by step under
a name apart from the hand-over counter, and a test of the shipped exposure keeps `payouts` out of Payment Service's
default.

For the wallet's bank account relay they cover the caller's token and acceptance reaching Payment Service, a refused
token passed on as a `400` in Payment Service's words, any other refusal treated as the wallet's own fault and a `502`,
a failure, a timeout or an unreadable answer answered `502` with a retry under the same token (and a plain retry for a
read), an answer without a body treated as unreadable, the real client proxy called over HTTP, a `406` before Stripe is
called, the `btok_` pattern and `tosAccepted` checked before the relay (a refused value is never quoted), `401` before
the body, no log line naming the user or the token on any failure path, and the bank account client built from Boot's
builder.

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

For withdrawals they cover the order of the service: the currency, key and amount normalised before any query, an amount
off the grid refused before a connection is taken, a missing wallet refused before the key or Payment Service is asked,
the key judged before the preflight and again under the wallet lock, the same amount reaching the preflight, the debit,
the rows and the command, and a preflight refusal never reaching the handler. The service opens no transaction of its
own and the shipped configuration keeps `open-in-view` off, because either would make the handler's lock return a wallet
the service had already read. For the handler they cover the wallet locked first and never loaded without the lock, a
withdrawal committed under the key while this one waited answered as a replay that writes nothing, a bank account that
is not set judged before the funds, an unaffordable amount leaving the balance and the entry number as they were, a
refusal returned rather than thrown so that its row commits and sending no command, the ledger row, the withdrawal and
the command saved in that order before one flush, the command carrying the locked wallet's user id and currency, the
debited amount and no wallet id, a fresh event id with the contract's schema version, a violation, an enqueue failure or
a lock failure reaching the service as thrown, the READ COMMITTED pin, and a handler with no way to call Payment
Service. They also cover the replay of a debit from its ledger row and of a refusal even after a top-up, neither asking
Payment Service, every cause of a `409`, a violation explained after the rollback (another wallet's key a `409`, the
caller's own a replay, one that no withdrawal explains rethrown), a lock or version failure answered `503` with the
advice to retry under the same key, and no log line naming the user on any path.

For the preflight call they cover each answer: a ready bank account with a reserve, a short reserve, a bank account that
is not set whatever the reserve says, a terms refusal in Payment Service's words, any other refusal treated as the
wallet's own fault and a `502` that logs no body, a failing or silent Payment Service, and an unreadable answer (no
body, an unknown bank account state, a ready one without a reserve) that is never read as ready or not set. They also
cover the request on the wire (the caller's id, the upper-case currency and the canonical amount as a JSON number), the
field names on both sides of the call, the real client proxy called over HTTP, and the client built from Boot's
builder. For the endpoints they cover the receipt and a replay from the stored row rendering to the same bytes (PLN and
JPY), a `Location` built from the normalised currency and the lower-cased key and never from the request's host, an
amount taken as a string or a JSON number, a `406` before the service runs, `401` before the key and the body, the key
and reference rules (a fifth dash and non-ASCII digits included, never quoted), the status of each refusal with a `422`
whose detail says the key is spent, and the status read: the reason that applies and `null` otherwise, only the caller's
wallet answered, and a missing withdrawal told apart from a missing wallet.

The integration tests above run the lock, the unique indexes and the commit of the outbox row with the debit against
real Postgres.

For the ledger they cover entry numbers rising by one per wallet across credits and both transfer legs, a
refused debit taking no number, history paged by entry number without skipping or repeating a movement while
credits arrive, the history `limit` bounds (1 to 100) and a non-numeric cursor refused with `400`, and every
field of the wallet and history responses. Money in a response is a string at the currency's scale, and a scan of
every response record fails on a numeric money field; a request amount is accepted as a string or a number.

```bash
./mvnw test
```

The unit tests mock their collaborators, so they can't prove the real database barrier. The integration tests run it:
the unique `processed_events.event_id` and `balance_history (transaction_reference, type)`, the classification by
read-back for a deposit and for a returned withdrawal (`DEPOSIT` and `WITHDRAWAL_RETURNED` as the expected ledger type;
that it looks at that one type only is pinned by mocks), and concurrent credits to one wallet under its row lock. The
unique `balance_history (wallet_id, entry_no)` and the numbering of both transfer legs run in the integration tests too
(`SchemaChecksIT`, and `LedgerAudit` after every test). The entry-number history queries and the migration that numbers
existing ledger rows were checked by hand on a scratch database seeded with interleaved ids: numbering follows id order
within each wallet, `last_entry_no` equals each wallet's row count, and history pages by entry number. Payment changeset
006 ran by hand against a schema migrated through 005. The payout reconciler's selections and its claim have run only
against real Postgres in the live run, where the selection of created payouts used `idx_payouts_created`.

For transfers the mocks pin the order of the calls and the decision after each one; the integration tests above show
what the mocks cannot: the lock order, the unique key and the overdraft check under real concurrency.
