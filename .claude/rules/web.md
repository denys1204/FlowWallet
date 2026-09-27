---
paths:
  - "**/*Controller*.java"
  - "**/*Request.java"
---

# Controllers and request DTOs

- Without `produces` on the mapping, Spring MVC negotiates the response only after the handler has returned,
  so a request whose `Accept` rules out JSON gets a 406 after the work is done. A mapping that moves money
  declares `produces = MediaType.APPLICATION_JSON_VALUE` (see `TransferController`).
- Class-level `@Validated` routes parameter validation through an AOP proxy. Standalone `MockMvc` creates none,
  so parameter constraints silently don't run in tests unless the controller is wrapped with a
  `MethodValidationInterceptor` proxy. Wallet controller tests build their `MockMvc` with
  `ControllerMockMvc.of` (wallet test sources), which adds the proxy, `GlobalExceptionHandler` and
  `CurrentUserIdResolver`.
- `UUID.fromString` is not a validator (it accepts `1-1-1-1-1`), and Hibernate Validator 9.1's `@UUID` is an
  unreliable one. Its validator throws on a 36-character value with a fifth dash, which the platform's
  last-resort handler answers with a 500, and it accepts non-ASCII digits. The transfer endpoint checks its ids
  with an ASCII `@Pattern` instead (`TransferController`, `TransferRequest`). `DepositController` uses `@UUID`
  for its key and so answers such a key with a 500. Where `@UUID` stays, it defaults to versions 1 to 5, so set
  `version` explicitly.
- Hibernate Validator's `@Digits` measures a `BigDecimal` as it is, trailing zeros included (it strips them
  only from other `Number` types), so `@Digits(fraction = 2)` refuses `25.100`. Precision is checked in code
  (`AmountPrecision`).
