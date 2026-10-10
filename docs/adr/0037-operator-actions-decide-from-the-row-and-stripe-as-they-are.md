# 0037. Operator actions decide from the row and Stripe as they are, and one sends a stuck transfer to its reversal

- Status: Accepted, extends [0016](0016-error-model-and-status-codes.md) and
  [0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md)
- Date: 2026-10-10

## Context

[0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md) makes the `payouts` actuator endpoint the exit from
`NEEDS_ATTENTION`, with two actions: `requeue` returns the row to `attention_from`, and `return-without-reversal`
checks Stripe and returns the payout to the wallet with `OPERATOR_RETURNED`. About the hand-over, the row records only
`attention_from`. That design leaves five gaps.

A row waiting from `TRANSFERRED` has no exit but SQL or a deploy. `return-without-reversal` refuses there, because a
reversal can still bring the transfer back, and `requeue` does not help when the cause persists:

- a payout refusal whose code is not on `StripeConnectPayouts`' list is an unknown outcome, so the step meets the
  refusal stored under its key for 24 hours, then the same code once the key expires, and goes back to
  `UNKNOWN_LIMIT` after the configured tries, about 7 hours with the default waits;
- after a `DEFECT`, an `IdempotencyException`, the step sends the same key with the same parameters again;
- after a `MISMATCH` against a failed payout with other terms, the next listing finds the same payout.

The Alternatives of [0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md) reject a design with SQL as the
only way out.

A row waiting from `ACCEPTED` on `MULTIPLE` or `MISMATCH` stays stuck after the stray transfers are reversed in the
Dashboard: `return-without-reversal` refuses when any transfer exists in the group, and the executor's lookup counts
reversed transfers, so `requeue` meets the same cause.

A row enters `NEEDS_ATTENTION` right after the Stripe calls of its last try. One of them may have timed out on this
side while Stripe still runs it, so a check made at once can miss an object that lands a moment later. The executor
waits `payment.payout.executor.unknown-backoff` after an unknown outcome for the same reason.

An operator's check reads Stripe outside any transaction
([0006](0006-short-transactions-across-bean-boundaries.md)), so the row can change between the read and the write:
another operator's `requeue`, a try by the executor and a hand-over back to `NEEDS_ATTENTION` leave the status as the
check read it.

The cause and detail of a hand-over go only to the executor's ERROR line and the `payment.payouts.needs.attention`
counter, while the action an operator should take depends on them.

## Decision

`PayoutsEndpoint` is `@WebEndpoint(id = "payouts")`, so it has no JMX operations, and it runs on Payment Service's own
port, to which the gateway routes only webhooks. It stays out of the default `ACTUATOR_EXPOSED_ENDPOINTS`, and an
operator exposes it for one process while acting. Its writes credit wallets and authenticate nobody, so it is as
protected as that port ([0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md)), and loopback alone does not
protect it from a browser: a page open on the operator's host can POST to `localhost` with no body and no preflight, and
through DNS rebinding read the answers. `ActuatorRequestGuard` therefore refuses with 400 a path on any route that
carries a path parameter, an escape, a doubled slash or a dot segment, which Spring MVC would match as another path than
the one the guard reads and which no route of Payment Service needs; it refuses with 403 every actuator request that
carries an `Origin` header or a `Sec-Fetch-Site` other than `none`, which probes, scrapers and curl never send, and
answers `payouts` only under a loopback `Host`, so in a cluster an operator reaches it through `kubectl port-forward`. A
`payouts` write also needs the `X-FlowWallet-Operator` header, with any value: a page cannot set a custom header across
origins without a preflight, which the actuator never answers, so a browser that sends neither `Origin` nor
`Sec-Fetch-Site` on an empty form post still cannot send the write. `ManagementPortCheck` fails startup on a separate
management port, whose child server would listen on every interface and run without the guard, a filter of the main
context.

| Operation | HTTP | Stripe calls |
| --- | --- | --- |
| `attention()` | `GET /actuator/payouts` | none |
| `payout(reference)` | `GET /actuator/payouts/{reference}` | none |
| `act(reference, action)` | `POST /actuator/payouts/{reference}/{action}` | reads only |

The list holds at most `PayoutsEndpoint.LIST_LIMIT` (100) waiting rows, the longest-waiting first, with the number
waiting from each step and whether the list was cut, which one row read beyond the limit decides. The single GET shows a
payout in any status. A view carries the reference, the status, `attention_from`, the cause and detail, the hand-over
time as `since`, the terms and reasons, `key_gen`, `attempts`, the Stripe ids and times, and the actions the row's state
allows, and never the user id, the row id or `version`. Amounts are decimal strings at
`StripeCurrencyRules.payoutScale`, as in [0030](0030-amounts-in-responses-are-decimal-strings.md), and times are
ISO-8601 strings. The reference is lower-cased before the lookup, and one that matches no row is a 404. An action other
than the three is a 400. Neither detail quotes the input ([0026](0026-problem-details-never-quote-rejected-input.md)).
The endpoint takes no body and no query string: the actuator would merge either over the reference and the action in the
path, so `ActuatorRequestGuard` refuses them with 400.

The endpoint creates nothing at Stripe. `PayoutOperations` is deliberately not `@Transactional` and never calls
`createTransfer`, `createPayout` or `reverseTransfer`. Only the executor's claimed steps create at Stripe, and no claim
query matches a row in `NEEDS_ATTENTION`, so while a row waits nothing further starts at Stripe for its reference.
`failed` and `canceled` are final at Stripe, and a reversal is not undone, so a check that found no transfer left
unreversed and no payout that may reach the bank still holds at the write. `return-without-reversal` and `reverse` run
in a fixed order: read the row in a read-only transaction, check its state, wait out the quiet period, read Stripe with
no transaction open, then write in one short `PayoutStore` transaction that locks the row, checks its status and
`version`, applies one guarded `Payout` mutator and writes its outbox row.

| `attention_from` | `requeue` | `return-without-reversal` | `reverse` |
| --- | --- | --- | --- |
| `ACCEPTED` | back to `ACCEPTED` | to `RETURNED`, unless a transfer in the group is not reversed in full | refused |
| `TRANSFERRED` | back to `TRANSFERRED` | refused | to `REVERSING`, unless a payout of the transfer may reach the bank |
| `REVERSING` | back to `REVERSING` | to `RETURNED`, unless a payout may reach the bank or the transfer is reversed in full | refused |

A view's `actions` lists what this table allows for the row's `attention_from`, before Stripe is asked.

`requeue` keeps the rule of [0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md). It moves the row back to
`attention_from` under the row's lock, due at once with `attempts` at zero, and writes no event. It keeps `key_gen` and
the step's `*_attempted_at`, so the step looks for what Stripe holds before it creates, and a request of the last try
still running is answered under the same key. It makes no Stripe call and has no quiet period, so it works while
Stripe is down.

`return-without-reversal` keeps its refusal from `TRANSFERRED` and refines the other two steps:

- From `ACCEPTED`, it lists the transfers in the payout's `transfer_group`, every page, and refuses only for a transfer
  whose `amountReversed` is below its own amount. A transfer reversed in full by hand does not block, so a row waiting
  on `MULTIPLE` or `MISMATCH` returns once the stray transfers are reversed in the Dashboard.
- From `REVERSING`, it lists the connected account's payouts for the reference in the executor's window
  (`PayoutSteps.payoutsListedSince`, from an hour before `transferred_at`), retrieves the row's own `stripe_payout_id`
  when the listing lacks it, and refuses while any of them may reach the bank: every status but `failed` and
  `canceled`, an unrecognized one included. It then retrieves the transfer and refuses when `amountReversed` covers
  the payout's amount, because the reversal exists, and `requeue` lets the reversal step record it with the payout's
  own reason instead of `OPERATOR_RETURNED`. Otherwise it returns the payout, and the answer's
  `leftInConnectedAccount` is the part of the transfer no reversal brought back, the platform's loss of
  [0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md).

`reverse` is a third action, allowed only from `TRANSFERRED`. It lists the payouts in the same window and refuses while
any may reach the bank; a row in `TRANSFERRED` holds no payout id, because recording one moves it to `PAYOUT_CREATED`.
When exactly one such payout has the row's amount and currency, the refusal says to requeue, because the payout step's
lookup adopts that payout, as after an unknown outcome whose payout Stripe did create; otherwise it names the payout,
which an operator waits on or corrects by hand once paid. `Payout.operatorReverse` then moves the row to `REVERSING`
with `PAYOUT_FAILED` and no `failure_code`, and `PayoutStore.operatorReverse` writes the `PayoutFailedEvent` in the same
transaction. It is the payout's only Failed event, since `TRANSFERRED` sends none. The transition table of
[0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md) gains one row:

| From | To | Event |
| --- | --- | --- |
| `NEEDS_ATTENTION` from `TRANSFERRED` | `REVERSING`, by an operator | `PayoutFailedEvent` |

The executor works the reversal under its own claim, keys and retries. Its reversal step lists the payouts again,
sends the row to `NEEDS_ATTENTION` with `LIVE_PAYOUT` when one may reach the bank, and otherwise reverses the part not
yet reversed under `wd-reversal-<ref>-<gen>`, ending in `RETURNED` with its `PayoutReturnedEvent`. The rule of
[0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md) that money which reached a bank is never reversed
holds, with the endpoint's listing in front of the executor's.

`return-without-reversal` and `reverse` wait out a quiet period after the hand-over. Until
`payment.payout.executor.unknown-backoff` (1m by default, at least 30s) has passed since `updated_at`, they answer 503
before Stripe is asked, with the time to retry after. `updated_at` is the hand-over: `@UpdateTimestamp` sets it when
the record that moves the row into `NEEDS_ATTENTION` is flushed, and nothing writes a waiting row until an operator
moves it, since no executor or reconciler claim matches it and the Connect webhook settles nothing on it. A writer that
touched a waiting row would only start the quiet period again. The setting carries the premise the executor already
relies on, that a request Stripe was still running has ended by then, so the endpoint has no setting of its own.
`requeue` needs no quiet period, because its step looks up first under the same key.

`return-without-reversal` and `reverse` write only while the row is the one their check read. The snapshot read before
Stripe is asked carries `version`. `PayoutStore.operatorReturn` and `operatorReverse` lock the row by id and change
nothing when it has left `NEEDS_ATTENTION` (422) or its `version` differs from the one read (503). Each write a row
passes through on the way out of `NEEDS_ATTENTION` and back raises `version`: Hibernate's `@Version` on every UPDATE
it flushes, and the executor's claim UPDATEs in the query. So a requeue, a try and a hand-over back to
`NEEDS_ATTENTION` between the check and the write are all seen. The mutators refuse as a second layer:
`operatorReturn` refuses a row from `TRANSFERRED`, and `operatorReverse` one from any other step. A refusal there
under a matching status and version is a defect, which rolls the transaction back and answers 500.

Migration `010-add-payouts-attention-cause` adds `attention_cause` and `attention_detail`.
`Payout.needAttention(cause, detail, now)` sets them with `attention_from`, the detail cut to its 255 characters, and
every exit from `NEEDS_ATTENTION` clears all three. The detail is the one the executor logs with the cause, so it holds
no user id ([0027](0027-user-ids-stay-out-of-logs-and-provider-metadata.md)). Two CHECK constraints guard the columns:
`payouts_attention_cause_known` holds the cause to the `AttentionCause` names, and
`payouts_attention_cause_only_when_needed` keeps both columns empty outside `NEEDS_ATTENTION`. The second runs one way
only: a row that entered `NEEDS_ATTENTION` before the migration has no cause, and `needAttention` requires one for
every hand-over after it. The partial index `idx_payouts_attention` on `(updated_at, id)` where the status is
`NEEDS_ATTENTION` serves the list and the counts by step, whose queries write the status as a literal so that the
predicate matches.

Every refusal is an `ApiException` that `GlobalExceptionHandler` renders as a problem
([0016](0016-error-model-and-status-codes.md)). Its detail is written by the server, names Stripe's ids and statuses,
and never quotes the input or names a user id. The statuses keep the rule of
[0016](0016-error-model-and-status-codes.md) that each status stands for one remedy:

- `400`, fix the request: an action other than the three (`UnknownPayoutActionException`), or a path that is not
  plain, a query string or a body on `payouts`, or a write without `X-FlowWallet-Operator` (`ActuatorRequestGuard`).
- `403`, send the request from a terminal on the service's host: it came from a web page, or reached `payouts` under a
  host other than loopback (`ActuatorRequestGuard`).
- `404`, no such payout: no row has the reference (`PayoutNotFoundException`).
- `422`, use another action: the payout's state or what Stripe holds refuses this one, and the detail names the action
  to use instead, or says that a paid payout needs correction by hand (`PayoutActionRefusedException`). A payout that
  has left `NEEDS_ATTENTION` gets 422 with its status, so a repeat after a lost answer says what the first call did.
- `502`, Stripe could not be asked: nothing was written, and a retry is safe (`PayoutCheckUnavailableException`).
- `503`, retry later: the quiet period has not passed, or the row changed while Stripe was asked
  (`PayoutActionBusyException`). Nothing moved, and a retry is safe, as for the lost version check of
  `TransferBusyException`.

409 is not used, because in [0016](0016-error-model-and-status-codes.md) it means a key already taken. A database the
service cannot reach answers the 503 of [0025](0025-unreachable-database-answers-503.md) here as on every endpoint.
`requeue` makes no Stripe call, so it never answers 502 or the quiet period's 503.

`PayoutAttentionGauge` registers the gauge `payment.payouts.awaiting.operator`, tagged `from` (`accepted`,
`transferred`, `reversing`): the rows waiting from each step, counted in the database each time the gauge is read. It
is a bean apart from the endpoint, so the gauge exists whether or not the endpoint is exposed, and its name differs
from the hand-over counter `payment.payouts.needs.attention`, because a registry keeps one meter type per name. The
counter `payment.payouts.operator.actions` is tagged `action` (`requeue`, `return_without_reversal`, `reverse`) and
`outcome`: `applied`, `refused` for a 404 or 422, `retry` for a 503 from the endpoint's own checks and
`stripe_unavailable` for a 502. An applied action is logged with the reference and never a user id: a requeue at INFO
with the cause and detail it cleared, a return or a reversal at WARN, and a return from `REVERSING` with the amount
left in the connected account and that account's id.

## Alternatives considered

- SQL as the exit from `TRANSFERRED`. [0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md) rejects it, and
  an UPDATE skips the listing, the version check and the Failed event.
- Only adding codes to `StripeConnectPayouts`' refusal list. That is the fix for a code seen in a log, but it takes a
  deploy and does nothing for `DEFECT` or `MISMATCH`.
- `reverseTransfer` called by the endpoint. It would be a create outside the executor's claim, keys and retries.
- A return from `TRANSFERRED` after a listing. It credits the wallet and abandons a transfer that a reversal can bring
  back.
- A requeue under a fresh key generation. A refusal that persists comes back under the fresh key, and a request of the
  last try that Stripe still runs could leave a second object beside the one the fresh key creates.
- 409 or 422 for a row that changed while Stripe was asked. In [0016](0016-error-model-and-status-codes.md) 409 means
  a key already taken, and 422 would say the state refuses the action when a fresh read may allow it.
- `payment.payout.executor.lease` as the quiet period. It measures how long a claim is owned, not how long a request
  that timed out on this side can still run at Stripe.
- No quiet period. A return from `ACCEPTED` right after `UNKNOWN_LIMIT` could miss a transfer still landing, and the
  wallet would be credited while the transfer sits in the connected account until someone reverses it by hand.
- `WebEndpointResponse` with plain JSON bodies. The refusals would leave the problem format that every other endpoint
  answers with.
- `@Endpoint`. Its operations would exist over JMX as well, where a JMX exposure setting could publish the write.
- Loopback as the only guard. A browser on the operator's host reaches loopback, and a page it shows can send the
  write.
- A required JSON body on the write, so that Boot demands a `Content-Type` a cross-origin page cannot send without a
  preflight. The operator header does the same for the write without a body the actuator would merge over the path,
  and the loopback `Host` check covers the list.
- A refresh-account action. The user whose bank state is stale has no payout row to select, finding the account takes
  a user id the endpoint must not take, and no money is at risk, since a stale `NOT_SET` only refuses withdrawals.
- A dry run on GET (`?stripe=true`). The refusals already name Stripe's ids and statuses, and a read that calls Stripe
  doubles the guard paths to test.
- Read-only access by default (`management.endpoint.payouts.access`). Exposure is the switch
  [0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md) names, and the `outbox` endpoint keeps the same
  default access.
- An `attention_at` column for the hand-over. `updated_at` already holds that time while the row waits.
- An `OPERATOR_REVERSED` reason for `reverse`. `PAYOUT_FAILED` tells the wallet what it needs, that the payout did not
  reach the bank, and keeps the wire contract as it is, while the WARN line and the counter record that an operator
  acted.

## Consequences

- A return from `REVERSING` costs up to three Stripe reads; a return from `ACCEPTED` and a reversal cost one listing
  each.
- A money action waits out the quiet period after the hand-over, 1m by default.
- The actuator refuses every request a browser page sends, and `payouts` answers only through a loopback host, so an
  operator uses curl on the service's host or through `kubectl port-forward`. Payment Service cannot run its actuator
  on a separate management port, so probes and scrapers use the service's own port.
- The 502 and 503 refusals are logged at ERROR with a stack trace, as every 5xx `ApiException` is.
- A row that entered `NEEDS_ATTENTION` before migration 010 shows no cause or detail.
- A paid second payout found from `REVERSING`, and a live payout with other terms from `TRANSFERRED` once it is paid,
  still need both rows corrected by hand. So does an object created by hand at Stripe without `flowwallet_withdrawal`
  metadata, which neither the executor nor the endpoint sees.
- `requeue` of a row waiting from `ACCEPTED` on `MULTIPLE` or `MISMATCH` meets the same cause, because the executor's
  lookup counts reversed transfers; the exit is to reverse the stray transfers in the Dashboard and return the payout.
- With `payment.payout.executor.enabled=false`, a requeued or reversed row stays in its executor step until an executor
  runs.
- `reverse` leaves the reserve check of [0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md) as it is,
  because the transfer has already left the platform's balance. A return from `ACCEPTED` stops counting the row, and a
  requeue keeps it counted.
