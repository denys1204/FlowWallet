# 0031. Callers are authenticated in front of the gateway, which listens on loopback by default

- Status: Accepted, supersedes the intended authentication model of
  [0003](0003-caller-identity-and-trust-boundary.md)
- Date: 2026-10-04

## Context

[0003](0003-caller-identity-and-trust-boundary.md) takes `X-User-Id` on trust and names an intended model in which
the gateway validates a token and sets the header. FlowWallet does not authenticate anyone, and a trusted
authentication service outside the repository is meant to supply the identity. Meanwhile the gateway set no
`server.address`, so Netty listened on every interface, and the gateway forwards the header as the client sent it.
Anyone on the same network who knew a user's id could act as that user and spend the balance, and the withdrawals
on the roadmap would let that money leave the system.

## Decision

- An authentication layer in front of the gateway authenticates the caller and sets `X-User-Id`. It removes any
  `X-User-Id` the client sent, and it never gives one user id to a second person. FlowWallet's services take the
  header on trust, as [0003](0003-caller-identity-and-trust-boundary.md) describes, and that layer is not part of
  this repository.
- The gateway binds to `server.address`, which is `GATEWAY_ADDRESS` and `127.0.0.1` by default. With the default
  address and no authentication layer it is reachable only from the same machine, where a developer's client sends
  `X-User-Id` directly.
- `BindAddressCheck` logs a WARN at startup when the address is not a loopback address, including `0.0.0.0` and no
  address at all. A container deployment has to set `GATEWAY_ADDRESS=0.0.0.0` to be reachable, and gets the
  warning.
- The webhook route stays open to Stripe and is authenticated by Stripe's signature, not by `X-User-Id`
  ([0017](0017-webhooks-verified-before-they-are-read.md)).

## Alternatives considered

- Validating a token in the gateway: the identity provider lives outside FlowWallet, and the gateway would duplicate
  its job.
- The gateway removing `X-User-Id` from every request: it cannot tell a header set by the authentication layer from
  one a client wrote, so it would remove the identity it should forward.
- Listening on every interface with a warning in the README: a reader who skips the README exposes the gateway, and
  nothing at runtime says so.
- Refusing to start on a non-loopback address: a container has to listen on `0.0.0.0` to be reachable at all, and
  the authentication layer is outside what the gateway can check.

## Consequences

- A local run is reachable only from the developer's machine. `stripe listen` and the `curl` calls in
  `docs/development.md` use `localhost` and keep working.
- A gateway bound wider logs the WARN on every start, because it cannot tell whether an authentication layer sits in
  front of it.
- Wallet Service and Payment Service already bind to `127.0.0.1` by default (`WALLET_SERVICE_ADDRESS`,
  `PAYMENT_SERVICE_ADDRESS`), so none of the three services listens beyond the machine unless configured to. Only
  the gateway warns when it does.
- The default CORS policy allows any origin (`GATEWAY_CORS_ALLOWED_ORIGINS=*`), so a web page open in the developer's
  browser can also call the loopback gateway with an `X-User-Id` it knows. The origins are to be narrowed once a
  frontend exists.
