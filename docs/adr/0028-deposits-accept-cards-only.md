# 0028. Deposits accept cards only, from a payment-method list in configuration

- Status: Accepted, extends [0013](0013-deposit-initiation.md)
- Date: 2026-10-04

## Context

`StripeRequestMapper` created each deposit's PaymentIntent with automatic payment methods. The intent then offered
every method enabled in the Stripe dashboard, so dashboard settings outside the repository decided what the API
accepted. Some of those methods settle days after the customer pays and keep the intent in `processing` until
then. Others send the customer to a bank or wallet page and need a `return_url`, which the wallet API has no
place for. The server-side confirm in step 6 of `docs/development.md` had to pass one for that reason.

The roadmap adds withdrawals through Stripe Connect. Once a balance can be paid out, a deposit that settles days
later, or through a flow the API does not describe, makes it harder to know which money has settled.

## Decision

- `StripeProperties.paymentMethodTypes` (`stripe.payment-method-types`, `STRIPE_PAYMENT_METHOD_TYPES`, default
  `card`) lists the methods a deposit intent offers. The values come from `StripePaymentMethodType`, which has the
  one constant `CARD`. A name the enum does not list fails binding, and an empty list fails validation, so either
  stops Payment Service from starting.
- `StripeRequestMapper.toPaymentIntentParams` sends the list as `payment_method_types` and leaves
  `automatic_payment_methods` unset.
- The enum holds the methods the code has been reviewed for. The setting chooses among them for an environment.

In test mode on API version `2026-06-24.dahlia`, an intent created this way had `automatic_payment_methods` null and
confirmed server-side without a `return_url` with `pm_card_visa`. A card that requires 3D Secure answered
`requires_action` with `use_stripe_sdk`, which Stripe.js completes on the client, rather than an error. Both were
checked on 2026-10-04.

## Alternatives considered

- Automatic payment methods with `allow_redirects=never`: this removes the redirect methods but keeps the delayed
  ones, and the dashboard still decides the rest.
- `excluded_payment_method_types`: the exclusion list has to grow with every method Stripe adds.
- Restricting methods in the dashboard only: invisible in review, and different on every Stripe account.
- A constant list in the mapper: simpler, but enabling a reviewed method for one environment would take a
  rebuild.

## Consequences

- A deposit is paid by card. The server-side confirm in step 6 of `docs/development.md` needs no `return_url`.
- An intent keeps the methods it was created with, so one created with automatic payment methods still offers
  them. An initiated deposit replays its stored client secret without calling Stripe
  ([0024](0024-deposit-initiation-settles-its-own-races.md)), so its client is not affected.
- A deposit whose row was reserved but whose Stripe call never got recorded is retried under the same key
  ([0005](0005-client-supplied-idempotency-keys.md), [0024](0024-deposit-initiation-settles-its-own-races.md)). If
  that call reached Stripe within the last 24 hours and the method list has changed since, Stripe sees the same key
  with different parameters and answers with an idempotency error. Payment Service maps it to a `502`, like any
  other Stripe failure that is not a refusal ([0022](0022-stripe-charge-rules-checked-before-the-reservation.md)).
  Unlike other `502`s, a retry under the same key gets the same error until Stripe forgets the key, so the client
  has to start the deposit again with a new key. Only a deposit in flight when the method list changes can hit
  this.
- stripe-java 34 removes `PaymentIntentCreateParams.paymentMethodTypes` in favour of `allowedPaymentMethodTypes`
  (available since 33.2.0). The mapper call stops compiling on that upgrade and has to move to the new
  parameter.
