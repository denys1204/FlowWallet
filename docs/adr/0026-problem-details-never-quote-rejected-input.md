# 0026. A problem detail never quotes the value it rejects

- Status: Accepted, extends [0016](0016-error-model-and-status-codes.md)
- Date: 2026-09-27

## Context

[0016](0016-error-model-and-status-codes.md) sends every `ApiException` message to the caller as the problem
`detail` and logs it, at WARN for a 4xx. It keeps balance figures and rejected header values out of details, and
`CurrentUserIdResolver` and the `Idempotency-Key` constraints refuse without quoting.

Other refusals still quoted what the caller sent. `InvalidCurrencyException` appended the unnormalised currency from
the path or the body, and `CreateWalletRequest.currency` carried only `@NotBlank`, so a body currency could hold line
breaks and be as long as Jackson reads a string (100 million characters by default). `PaymentProviderFactory.resolve`
appended the provider name, which on the webhook is the public path segment `{provider}`. A value with line breaks
wrote log lines of the caller's choosing, and a long one was copied into the log and the response. The framework
did the same for a parameter of the wrong type: `ResponseEntityExceptionHandler.handleTypeMismatch` answers
"Failed to convert 'before' with value: 'abc'", and `handleConversionNotSupported` puts the value in a 500's detail.

## Decision

No problem detail quotes a rejected request value, so neither does the log line the handler writes from it. A
refusal names the field or parameter and the rule it broke.

- `InvalidCurrencyException` says "Not an ISO 4217 currency code", and `UnsupportedPaymentProviderException` from
  `PaymentProviderFactory.resolve` says "Unsupported payment provider".
- `CreateWalletRequest.currency` is `@NotNull` and `@Pattern(regexp = "[A-Za-z]{3}")`, so a body currency of any
  other shape is refused by bean validation, in `errors`, before the service sees it.
- `GlobalExceptionHandler.handleTypeMismatch` answers "Invalid value for parameter 'before'", naming the parameter as
  the handler method declares it. `handleConversionNotSupported` answers like any other defect: a 500 with
  "Internal server error" and the cause in the log.
- A detail may carry a value only after validation has bounded it, as `NonPaymentCurrencyException` names a code
  that `Currency.getInstance` has accepted.

The `{currency}` path variables keep their check in `Currencies.normalise`, so a bad path currency is still a `400`
with a detail and no `errors` list, and the order of refusals does not change. The fixed detail removes the echo;
the servlet container already bounds the length of a path.

## Alternatives considered

- Escaping or truncating the value before it is quoted. Every refusal would need the same care, and a missed one
  reopens the hole, while the caller already knows what it sent.
- `@Pattern` on the `{currency}` path variables. It turns the path's `400` into a validation failure with `errors`,
  a different response for the same fault, and gains nothing once the detail is fixed.
- A message source that overrides the framework's `problemDetail.org.springframework.beans.TypeMismatchException`
  code. It works only where a `MessageSource` is wired into the handler, and the default would return wherever it is
  not.
- `@Size` alone on the body currency. It bounds the length but lets line breaks and punctuation through to the
  service.

## Consequences

- A client learns which field or parameter was wrong, not how the server read it.
- A new refusal whose detail would include request input bounds that input first, with a constraint or a check, or
  leaves it out.
- The framework's other default details stay as they are. `HttpMediaTypeNotSupportedException` quotes the parsed
  `Content-Type`, which the container bounds with its header limit and which this handler does not log.
