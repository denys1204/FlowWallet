# 0002. Services share only a platform module and a wire contract

- Status: Accepted
- Date: 2026-08-30

## Context

FlowWallet runs two services behind a reactive gateway: Payment Service and Wallet Service. Both need the same
servlet-side plumbing (RFC 9457 error responses, the caller's identity from `X-User-Id`), and they exchange payment
events over Kafka. The two kinds of shared code follow different rules. Plumbing is ordinary code and changes as freely
as any other. The events are a boundary: producer and consumer are deployed separately, so a topic always holds
messages written by more than one version of the code.

A single shared module cannot express both sets of rules, so it ends up with the loosest ones that fit either. It also
becomes the default home for anything two services might one day share, including request and response types that
belong to one service. Once one service compiles against another's DTO, the two stop being independently deployable,
and that is hard to walk back.

## Decision

The reactor has five modules: `flow-wallet-contract`, `flow-wallet-platform`, `flow-wallet-gateway`,
`flow-wallet-payment` and `flow-wallet-service`.

- The two services depend on platform and contract only, never on each other, and talk over HTTP and Kafka. Payment
  Service knows nothing about wallets: it takes an instruction and answers. The gateway depends on neither library. It
  routes by path ([0013](0013-deposit-initiation.md)) and deserializes no body.
- Contract holds only what crosses the wire: `PaymentCompletedEvent`, `PaymentFailedEvent`, the `payment.events` topic
  name, the `eventType` header name and its values, and `PAYMENT_EVENT_SCHEMA_VERSION`. It declares no dependencies and
  its code uses nothing outside the JDK. The events are plain records, and a consumer brings its own serializer. The
  evolution rules are in [0009](0009-payment-event-contract.md).
- Platform holds shared servlet-side infrastructure and nothing domain-shaped: `GlobalExceptionHandler`, `ApiException`,
  the `@CurrentUserId` resolver, the `X-User-Id` constant in `HttpHeaders`, and `@Iso4217Currency`.
- Platform features register through `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
  `CurrentUserIdAutoConfiguration` and `WebExceptionHandlerAutoConfiguration` both carry
  `@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)`, so every servlet service gets them
  without declaring anything and a reactive application never does. The handler bean is `@ConditionalOnMissingBean`,
  so a service can replace it by declaring its own `GlobalExceptionHandler` bean.
- Each service owns its database, `payment_db` or `wallet_db`, and never touches the other's.
- A type or constant that belongs to one service stays in that service, even when it looks shareable. The outbox row's
  aggregate type, `PaymentEventMapper.AGGREGATE_TYPE_PAYMENT_TRANSACTION`, labels a row in Payment Service's own table
  and never reaches the wire, so it lives next to the mapper that writes it.

## Alternatives considered

- One shared `flow-wallet-common` module, the layout the repository started with. It took the loosest rules that fit
  either purpose, and service-owned DTOs landed in it.
- A synchronous REST DTO in `flow-wallet-contract`, shared by the wallet's client and Payment Service's controller. It
  rebuilds the coupling the split removes.
- A domain helper, such as the currency list, in `flow-wallet-platform`. Platform would stop being domain-free and
  drift into a second `common`.
- A Maven dependency from one service on the other. The services would stop being independently deployable.
- A contract module with framework dependencies. A contract that drags a framework onto every consumer's classpath
  stops being a contract. With no dependencies, the module has nowhere to put anything that is not a contract.
- Each service declaring its own `@RestControllerAdvice` and registering the resolver itself. Error responses would
  differ from service to service.
- The outbox aggregate-type constant in `flow-wallet-contract` next to the event types. No consumer has a use for it.

## Consequences

- A shape both services need is copied, not shared. Wallet Service's `CreatePaymentIntentCommand` and
  `PaymentIntentResult` mirror Payment Service's `CreatePaymentIntentRequest` and `PaymentIntentResponse`.
  `AmountPrecision` copies the currency grid from `StripeCurrencyRules`
  ([0015](0015-currency-precision-and-no-rounding.md)). The compiler cannot see across a copy, so a change to one side
  has to be made to the other by hand.
- The gateway's own errors (`404` for an unrouted path, `5xx` when a downstream service is unreachable) come in Spring
  Boot's default WebFlux format, not as the platform's problem responses.
- No transaction spans both databases. A deposit crosses from one to the other through an HTTP call and a Kafka event
  ([0013](0013-deposit-initiation.md), [0008](0008-transactional-outbox.md),
  [0010](0010-idempotent-payment-event-consumer.md)).
- Code that both services seem to need goes to contract if it crosses the wire and to platform if it is servlet
  plumbing with no domain meaning. Anything else stays in the service that owns it, and the other service keeps its own
  copy.
