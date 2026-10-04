# Contributing to FlowWallet

FlowWallet is an engineering showcase that runs against Stripe test mode only. This file is the working agreement
for changing it: how to build and test, the conventions code and docs follow, and the change map, the list of
everything that has to move together when one thing changes. `CLAUDE.md` imports this file, so coding agents work
by the same rules.

The system itself is described elsewhere. Setup is in [docs/development.md](docs/development.md). The design is in
[ARCHITECTURE.md](ARCHITECTURE.md), the decisions are in the ADRs under [docs/adr/](docs/adr/README.md), and the
invariants and framework traps are in `CLAUDE.md`. Before changing a flow, read its section of `ARCHITECTURE.md` and
the ADRs it links.

## Build and test

```bash
./mvnw clean install                 # build all five modules and run every test
./mvnw test                          # tests only (JUnit 6 + Mockito; no integration tests yet)
./mvnw install -DskipTests           # install modules so -pl builds can resolve siblings

# one module / one class / one method (after an install, or add -am)
./mvnw -pl flow-wallet-payment test -Dtest=StripeRequestMapperTest
./mvnw -pl flow-wallet-service test -Dtest='DepositServiceTest#aMissingWalletIsRefusedBeforeAnythingIsCharged'
./mvnw -pl flow-wallet-service -am test -Dtest=DepositServiceTest -Dsurefire.failIfNoSpecifiedTests=false

docker compose up -d                 # Postgres 17 (wallet_db + payment_db), Kafka, Kafka-UI on :8090
./mvnw -pl flow-wallet-gateway spring-boot:run    # :8080
./mvnw -pl flow-wallet-payment spring-boot:run    # :8082
./mvnw -pl flow-wallet-service spring-boot:run    # :8081
stripe listen --forward-to localhost:8080/api/payments/webhooks/stripe
```

- Never run two Maven builds at once in one checkout. They share `target/` and clobber each other.
- All three services import the repository-root `.env` through `spring.config.import` (both `./` and `../`, because
  the working directory is the module under the Maven plugin). Exported environment variables override it. Values
  stay unquoted: Spring reads the file as `.properties`, so quotes become part of the value. Compose ports bind to
  `127.0.0.1`; if `5432` is taken, set `DB_PORT` in `.env`.
- `.env` holds real Stripe test keys and is never committed. Take variable names from `.env.example`.

## Where each kind of text lives

Each fact has one home, and the other files link to it. [ADR 0001](docs/adr/0001-record-architecture-decisions.md)
holds the rule, and [ADR 0029](docs/adr/0029-working-rules-live-in-contributing.md) records why the working rules
live in this file.

| File | Holds |
|---|---|
| `README.md` | The front page: status, topology diagram, tech stack, quickstart, roadmap |
| `CONTRIBUTING.md` | How to work in the repository: commands, conventions, the change map |
| `ARCHITECTURE.md` | How the system works: modules, the deposit flow, the outbox, the wallet consumer, transfers, identity |
| `docs/business-rules.md` | What the wallet allows and refuses, in plain terms, linking the details |
| `docs/api.md` | Endpoints, request and response bodies, error responses |
| `docs/data-model.md` | The schema of each service's database |
| `docs/events.md` | Kafka topics and event contracts |
| `docs/development.md` | Running, configuring and testing |
| `docs/adr/` | One decision that spans files, with its context and rejected alternatives |
| `CLAUDE.md` | A summary of the architecture, each invariant as a one-line rule with its ADR, framework traps |
| `.claude/rules/*.md` | A convention or trap for one kind of file, loaded through its `paths:` glob |
| Code comments | Only what a reader at that spot cannot get from the code and needs in order to change it safely |

## Change map

When the left column changes, everything in the right column changes in the same commit. One change often matches
several rows; do every row that applies. The most common documentation defect in this repository's history is a
second copy of a fact left behind, so before committing, run `git grep -n` from the repository root for the
identifier you changed and for a distinctive phrase of the sentence that described the old behaviour.

An accepted ADR is not rewritten. Where a row names an ADR, a changed decision gets a new ADR that extends or
supersedes it, and only a factual correction is edited in place (see [ADRs](#adrs)).

| When you change | Also update |
|---|---|
| A flow's behaviour: a step or lock order, a retry or refusal rule, a payment, outbox or transfer status | Every place that describes it: the `ARCHITECTURE.md` section, the `CLAUDE.md` invariant, `docs/api.md`, `docs/business-rules.md`, `README.md`, the ADR, Javadoc, code comments and test comments |
| A business rule | `docs/business-rules.md`, and a test that pins the rule |
| An endpoint, a request or response body, or a status code | `docs/api.md` (the endpoint and the error list); `docs/business-rules.md` where the refusal is described; a new ADR extending ADR 0016 when a status gains a meaning; the `CLAUDE.md` invariant that begins "Problem responses carry no `type`"; for a Wallet Service mapping that changes state, the `produces` list in `.claude/rules/web.md`; a controller test, built with `ControllerMockMvc.of` in the wallet or a standalone `MockMvc` like `WebhookControllerTest` in Payment Service |
| A configuration setting | The `@ConfigurationProperties` class, with `@Validated` checks that fail startup on nonsense, and its `*PropertiesTest` (an older setting without a properties class keeps its fallback in the `@Scheduled`, `@Retryable` or `@Value` placeholder); the key in `application.yml` as `${ENV_VAR:default}`, with the same default as the Java code; `.env.example` with a comment; the table in `docs/development.md` when it is one of the main settings the table lists, and its paragraph on values that stop startup |
| A default value or a timeout | Every text that quotes the value: YAML comments, `.env.example`, `docs/development.md`, `ARCHITECTURE.md` and the ADRs. The deposit's timeouts nest, and no test checks the order across modules: the Stripe call budget ends before the wallet's connect plus read timeouts for the Payment Service call, those end before the gateway's response timeout, and the database pool wait stays below both (ADRs 0024 and 0025) |
| The schema | A new Liquibase changeset with the next number in that service's `changes/` directory, because a changeset that has already run is never edited (Liquibase checksums it); the entity in the same commit (`ddl-auto: validate`); `docs/data-model.md`; constraint names quoted in `CLAUDE.md`, ADRs and Javadoc |
| A Kafka event or the contract module | The record in the contract module's `event` package and its header value in `KafkaConstants`, following the evolution rules in the module's `package-info`; a case in `PaymentEventListener`, shipped before any producer sends the type (ADR 0009); `PaymentEventWireFormatTest` for the field names and `PaymentEventListenerTest` for the consumer; `docs/events.md`; the contract lists in `ARCHITECTURE.md`, ADR 0002 and `CLAUDE.md` |
| A call between two services | The request and response records copied by hand on both sides (ADR 0002) and the wire-format test on each side, as `PaymentIntentWireFormatTest` does in Payment Service and Wallet Service; the internal section of `docs/api.md`; the nested timeouts if a wait changes |
| An ADR, added, extended or superseded | The next free number; its row in `docs/adr/README.md`; the status line and index row of every ADR it extends or supersedes; the one-line invariant in `CLAUDE.md` and the `.claude/rules` text when the ADR sets a rule; the `ARCHITECTURE.md` section and the code comments that point at the rule it holds |
| A currency list, a copy of a Stripe table or a shared validation pattern | Both copies of the zero-decimal and whole-unit lists in `StripeCurrencyRules` and `AmountPrecision`, and the grid as described in `CLAUDE.md`, `docs/api.md` and `docs/business-rules.md`; `StripeChargeLimits` with its retrieval date, which is also quoted in `StripeCurrencyRules` and `docs/business-rules.md`; `TransferController.ANY_UUID` (also used by `DepositController`) and `CurrentUserIdResolver.RANDOM_UUID_REGEX` (also used by `TransferRequest`) |
| The topology: a service, port, route, topic or database | The diagram in `README.md` and in `ARCHITECTURE.md`, which stay identical; the gateway routes described in `ARCHITECTURE.md`, `docs/api.md` and `CLAUDE.md`; the ports in the commands of this file, the `README.md` quickstart and `docs/development.md` |
| A document, added, renamed or removed | The table in this file, the Documentation table in `README.md`, the list at the top of `ARCHITECTURE.md`, the introduction of `docs/development.md`, and every link to it |
| The number of tests | The count and the per-module split in `docs/development.md`, taken from the test reports of a full build |
| A file or package that a `.claude/rules` file names | The `paths:` globs and the text of every `.claude/rules` file that names it |
| A dependency, a tool version or a run command | The version once, in the root `pom.xml`, `docker-compose.yml` (Postgres, Kafka, Kafka-UI) or `.mvn/wrapper/maven-wrapper.properties` (Maven); the versions quoted in the `README.md` badges and tech stack, the first paragraph of `CLAUDE.md`, the commands in this file and the prerequisites in `docs/development.md`; a run command also sits in the `README.md` quickstart and `docs/development.md` |
| The module list | The modules and dependency management in the root `pom.xml`; the module count and list in `CLAUDE.md`, `README.md`, `ARCHITECTURE.md`, ADR 0002 and the command comments in this file |
| Integration tests arrive | The "no integration tests yet" notes in this file, `README.md` and `docs/development.md` |

## Code conventions

- Lombok over boilerplate; constructor injection through `@RequiredArgsConstructor`; records for DTOs and events.
- Entities follow `PaymentTransaction`: `@Entity @Getter @Builder @AllArgsConstructor @Table
  @NoArgsConstructor(access = PROTECTED)`, `SEQUENCE` ids with a named generator and `allocationSize = 50` matching
  the Liquibase `incrementBy: 50`, an explicit `@Column(name = ...)` on every non-id field, `@Enumerated(STRING)`,
  and static factories plus intent-named mutators instead of setters.
- A comment gives the local why in a few lines: what a reader at that spot cannot get from the code and needs in
  order to change it safely, such as a local invariant, a trap or a non-obvious ordering. Comments that restate the
  code are deleted. A decision that spans files, with its rejected alternatives, lives in an ADR, and the comment
  ends with `See docs/adr/NNNN-slug.md.`
- Javadoc always uses the multi-line form, never a one-line `/** ... */`. Public Javadoc keeps `@param`, `@return`
  and `@throws` only where they tell the caller something the signature does not.
- Comments, Javadoc and ADRs use plain present-tense English, with no ticket numbers and no wording tied to a diff
  ("now", "new", "this change"). Ticket numbers stay out of file names too.
- Formatting follows `.editorconfig`; there is no formatter plugin. Java lines stay within 120 columns. A wrapped
  argument or parameter list breaks after `(`, puts one item per line and closes `)` on its own line, and a list
  that fits on one line stays on one line. A wrapped call chain puts one call per line. There is no blank line after
  a class header. Imports stay sorted in IntelliJ's order. Markdown prose wraps at 120 columns, and a table row
  stays on one line.
- Rules that depend on the kind of file are in `.claude/rules/`: `web.md` for controllers, request and response DTOs and
  their tests, `persistence.md` for migrations and configuration, `integrations.md` for the Maven build, Kafka,
  MapStruct, Stripe and the shape of fake keys in tests. Read the one for the file you change.

## Tests

- A test name is a sentence describing the behaviour, and a comment says which failure the test guards against.
- New behaviour is pinned by a test.

## ADRs

[ADR 0001](docs/adr/0001-record-architecture-decisions.md) holds the format:

- A new decision that spans files gets a new ADR.
- `docs/adr/NNNN-slug.md`, headed `# NNNN. Title`, where the title states the decision, then a Status line and a
  Date line, then Context, Decision, Alternatives considered and Consequences.
- The Date is the date of the first commit that introduced the decision.
- An ADR names classes, methods, tables and constraints, never line numbers.
- Numbers are four digits, assigned in order and never reused.
- An accepted ADR is not rewritten when the decision changes. A new ADR extends or supersedes it, and the status
  lines of both carry the link: "Accepted, extends [N]" or "Accepted, supersedes the <part> of [N]" on the new one,
  "extended by [M]" or "<part> superseded by [M]" on the old one. A factual correction that leaves the decision
  unchanged, such as a renamed class, is edited in place.

## Commits

- Conventional commits: `type(scope): subject`, lower case and imperative, ideally within 72 characters. Types in
  use are `feat`, `fix`, `refactor`, `test`, `docs`, `style`, `chore` and `build`. The scope is the module or area:
  `payment`, `wallet` (`flow-wallet-service`), `platform`, `gateway`, `contract`, `config`, `logging`, `infra`
  (`docker-compose.yml`) or `deps`.
- The body explains why the change was made and names the ADRs and docs it updates.
- One logical change per commit. Every commit that touches code builds and passes `./mvnw clean install` on its
  own; a commit that changes only Markdown skips the build.

## Definition of done

- `./mvnw clean install` passes, and the test count in `docs/development.md` matches the reports.
- Every row of the change map that applies is done in the same commit.
- New behaviour is pinned by a test whose name and comment follow the rules above.
- `git grep` for the changed identifier and the old wording finds no stale copy, except in an accepted ADR whose
  decision changed, which a new ADR extends or supersedes.
