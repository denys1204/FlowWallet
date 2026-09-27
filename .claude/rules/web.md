---
paths:
  - "**/*Controller*.java"
  - "**/*Request.java"
---

# Controllers and request DTOs

- Without `produces` on the mapping, Spring MVC negotiates the response only after the handler has returned,
  so a request whose `Accept` rules out JSON gets a 406 after the work is done. A mapping that changes state
  declares `produces = MediaType.APPLICATION_JSON_VALUE`: the transfer, the deposit (Payment Service creates the
  intent) and opening a wallet (`TransferController`, `DepositController`, `WalletController.open`).
- Spring MVC resolves arguments in declaration order, so `@CurrentUserId` is the first parameter of every
  mapping; declared later, a caller without `X-User-Id` and a bad body or parameter gets 400 instead of 401.
- Class-level `@Validated` routes parameter validation through an AOP proxy. Standalone `MockMvc` creates none,
  so parameter constraints silently don't run in tests unless the controller is wrapped with a
  `MethodValidationInterceptor` proxy. Wallet controller tests build their `MockMvc` with
  `ControllerMockMvc.of` (wallet test sources), which adds the proxy, `GlobalExceptionHandler` and
  `CurrentUserIdResolver`.
- `UUID.fromString` is not a validator (it accepts `1-1-1-1-1`), and Hibernate Validator 9.1's `@UUID` is an
  unreliable one. Its validator throws on a 36-character value with a fifth dash, which the platform's
  last-resort handler answers with a 500, and it accepts non-ASCII digits. UUIDs are checked with an ASCII
  `@Pattern` instead: both idempotency keys with `TransferController.ANY_UUID` (`DepositController` shares it),
  and a transfer's recipient with `CurrentUserIdResolver.RANDOM_UUID_REGEX` (`TransferRequest`).
- A problem detail never quotes the value it refuses: `GlobalExceptionHandler` logs every 4xx detail, so a
  quoted value with line breaks forges log lines. Name the field and the rule; a request `String` that reaches
  a detail is bounded first with `@Pattern`, like `CreateWalletRequest.currency`
  ([ADR 0026](../../docs/adr/0026-problem-details-never-quote-rejected-input.md)).
- Hibernate Validator's `@Digits` measures a `BigDecimal` as it is, trailing zeros included (it strips them
  only from other `Number` types), so `@Digits(fraction = 2)` refuses `25.100`. Precision is checked in code
  (`AmountPrecision`).
