# FlowWallet business rules

This page lists what the wallet allows and refuses, in business terms. Each section links the documents with
the details (status codes, error texts and edge cases) and the ADRs with the reasoning. Retries, the outbox and
other operational behaviour are described in [ARCHITECTURE.md](../ARCHITECTURE.md).

## Users and identity

- Every wallet request acts as the user named in the `X-User-Id` header, a version 4 or 7 UUID. FlowWallet
  does not authenticate the caller: an authentication layer in front of the gateway sets the header. By
  default the gateway listens only on its own machine.
- No request names an owner, so a user can act only on their own wallets.
- The wallet keeps no list of users. A sender gets the recipient's user id from the recipient.

Details: [identity and security model](../ARCHITECTURE.md#identity--security-model). Reasoning:
[ADR 0003](adr/0003-caller-identity-and-trust-boundary.md),
[ADR 0031](adr/0031-callers-are-authenticated-in-front-of-the-gateway.md).

## Wallets

- A user opens a wallet explicitly, one per currency. A deposit, a transfer, a withdrawal or a payment event never opens
  one.
- The currency is an ISO 4217 code, in either letter case. A code that names no currency of payment, such as
  gold (XAU), the testing code (XTS) or "no currency" (XXX), cannot be opened, because no deposit could fund it.
- A new wallet holds 0. A wallet cannot be closed, deleted or moved to another currency.
- A wallet is addressed by its owner and currency and has no id in the API. Someone else's wallet and a wallet
  that does not exist look the same.
- A balance never goes below zero. Spending it down to exactly zero is allowed.
- Money comes into FlowWallet only through deposits and leaves it only through withdrawals to the owner's bank
  account. Transfers move it between users.

Details: [API reference](api.md#wallet-service). Reasoning:
[ADR 0004](adr/0004-wallet-addressed-by-owner-and-currency.md),
[ADR 0012](adr/0012-balances-and-append-only-ledger.md),
[ADR 0022](adr/0022-stripe-charge-rules-checked-before-the-reservation.md).

## Amounts

- Every amount that moves money is positive.
- An amount sits on its currency's grid: whole units for the 15 zero-decimal currencies (JPY and KRW among
  them), the Icelandic króna (ISK) and the Ugandan shilling (UGX), and at most two decimals for every other
  currency. An amount off the grid is refused rather than rounded.
- The wallet shows every amount at its currency's scale, as a decimal string: 75.00 USD reads `"75.00"`, and
  1000 JPY reads `"1000"`.

Details: [API reference](api.md#wallet-service). Reasoning:
[ADR 0015](adr/0015-currency-precision-and-no-rounding.md), [ADR 0023](adr/0023-isk-charged-in-whole-units.md),
[ADR 0030](adr/0030-amounts-in-responses-are-decimal-strings.md), [ADR 0038](adr/0038-ugx-charged-in-whole-units.md).

## Deposits

- A deposit tops up a wallet the user already holds, in that wallet's currency. The client pays Stripe directly, so
  payment details never reach the wallet.
- A deposit is paid by card. Other methods Stripe offers, such as bank debits or payments that redirect to a
  bank's page, are not accepted.
- The balance changes when Stripe confirms the payment. Starting a deposit moves no money.
- A deposit is between a configured minimum and maximum, 1.00 and 10,000.00 by default. The bounds are the same
  figures in each currency's own units: 1 to 10,000 JPY, and equally 1 to 10,000 KWD.
- A deposit is only possible in a currency Stripe charges, and it must reach Stripe's minimum charge for the
  currency where Stripe lists one, such as 50 JPY, 175 HUF or 15 CZK. The higher of that minimum and the
  configured one applies. Both rules come from a copy of Stripe's currency page taken on 2026-09-27.
- A deposit these rules refuse records nothing. If Stripe still refuses a payment for its own reasons, the
  deposit is refused too, and its idempotency key stays tied to the refused request: correct it and use a new key.
- The payment provider comes from configuration, not from the client. Stripe is the only one.
- A declined payment moves no money, and the same deposit can still be paid afterwards.
- There is no refund or reversal: a credited deposit stays credited.

Details: [deposit flow](../ARCHITECTURE.md#end-to-end-deposit-flow), [API reference](api.md#wallet-service),
[configuration](development.md#configuration). Reasoning: [ADR 0013](adr/0013-deposit-initiation.md),
[ADR 0022](adr/0022-stripe-charge-rules-checked-before-the-reservation.md),
[ADR 0028](adr/0028-deposits-accept-cards-only.md).

## Transfers

- A transfer moves money between two users' wallets in the same currency. Nothing converts between currencies.
- The recipient must already hold a wallet in that currency.
- A user cannot transfer to themselves.
- The sender's balance must cover the amount.
- Apart from the amount rules above, only the sender's balance limits a transfer: the deposit range does not
  apply, and there are no daily or per-transfer limits.
- A transfer is all or nothing: both balances and both ledger entries change together, or nothing changes.
- The recipient sees the sender's user id and the sender's idempotency key in their history.

Details: [transfers](../ARCHITECTURE.md#transfers-between-wallets), [API reference](api.md#wallet-service).
Reasoning: [ADR 0014](adr/0014-transfers-in-one-local-transaction.md),
[ADR 0011](adr/0011-wallet-row-locking.md).

## Bank account

- A user sets one bank account per currency to receive withdrawals. It belongs to the user, whatever wallets
  they hold.
- The bank details stay at Stripe. The client turns them into a single-use Stripe token, and FlowWallet sees only
  the token, Stripe's ids and the last four digits. Raw bank numbers are refused.
- Setting a bank account needs the user's acceptance of Stripe's Connected Account Agreement. The first one opens
  the user's connected account at Stripe.
- A new token replaces the bank account for its currency. A token works once: a refused token needs a new one, and
  repeating the token that was last applied returns the stored answer.
- A bank account is ready when Stripe can pay out to it now. Right after a bank account is set, and whenever Stripe
  reports a change to the connected account or its bank account, FlowWallet reads the account again and sets the state
  from what it reads.
- A payout that fails at the bank makes that bank account not ready, and the user sets one again before the next
  withdrawal. A canceled payout leaves the bank account as it is.

Details: [API reference](api.md#wallet-service). Reasoning:
[ADR 0035](adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md),
[ADR 0036](adr/0036-connect-webhooks-have-their-own-endpoint-and-secret.md).

## Withdrawals

- A withdrawal pays part of a wallet's balance out to the user's bank account through Stripe Connect. FlowWallet runs
  in Stripe test mode only, so no real money moves.
- It needs a bank account that is ready in the wallet's currency. Payment Service pays out in PLN only by default, so a
  withdrawal from a wallet in another currency is refused.
- The amount is positive, on the grid above, and has no more decimals than a payout in its currency allows. It is at
  least the configured minimum, 5.00 by default, which is never below Stripe's own minimum payout where Stripe lists
  one. Nothing else limits it but the balance and the platform's reserve.
- The debit is immediate: the amount leaves the balance when the wallet accepts the request, and a `WITHDRAWAL` entry
  shows in the history. The answer is `202`, accepted and not yet paid. The bank details never reach the wallet.
- A withdrawal is `PENDING` while its payout is on its way and `PAID` when Stripe reports the payout paid. It is
  `RETURN_PENDING` when the payout failed or could not be made and the money is on its way back, and `RETURNED` when the
  money is back in the wallet. A request the wallet turns down is `REFUSED`.
- Stripe can report a payout paid and then fail it days later. The withdrawal then moves from `PAID` to
  `RETURN_PENDING`, and the money comes back.
- The money comes back exactly once and in full, as a `WITHDRAWAL_RETURNED` entry under the withdrawal's key, however
  many events arrive about the payout. The wallet credits it only when Payment Service reports the return: once the
  transfer to the user's connected account is reversed, when none was made, or when an operator returns the payout.
- A payout that fails at the bank also makes the bank account not ready, so the user sets one again before the next
  withdrawal.
- FlowWallet charges no fee: the amount debited is the amount paid out. The platform carries Stripe's fees.
- A withdrawal refused for want of a ready bank account or of funds is recorded as `REFUSED` and answered `422`, and its
  key is spent. The same key keeps getting that `422`, even after a top-up or a bank account set later, so a client that
  retries automatically never pays out a withdrawal the user saw refused. A new attempt takes a new key. The bank
  account is judged before the funds.
- Terms that Payment Service refuses (a currency it does not pay out in, an amount below the minimum or off the payout
  grid) are a `400` that records nothing. The key stays free, but a new attempt takes a new key, because the terms come
  from configuration and could pass later.
- While the platform's reserve cannot cover a payout, withdrawals are temporarily unavailable: the answer is `503`,
  nothing is debited and nothing is recorded, and the same key can be sent again later.
- After a lost response or a `5xx`, the client sends the same request again under the same key and never switches to a
  new one, or the money could go out twice.
- A withdrawal cannot be cancelled after the debit. It can stay `PENDING` or `RETURN_PENDING` while Payment Service
  waits on Stripe or an operator resolves its payout.
- Only the owner can read a withdrawal. Another user's withdrawal looks like one that does not exist.

Details: [withdrawals](../ARCHITECTURE.md#withdrawals), [API reference](api.md#withdrawals). Reasoning:
[ADR 0033](adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md),
[ADR 0034](adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md),
[ADR 0035](adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md),
[ADR 0036](adr/0036-connect-webhooks-have-their-own-endpoint-and-secret.md),
[ADR 0037](adr/0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md).

## Idempotency keys

- Every deposit, transfer and withdrawal request carries an `Idempotency-Key` UUID chosen by the client. The server
  never generates one.
- Repeating a request with the same key and the same terms gets the same answer, and the money moves once.
- Reusing a key with different terms, or a key another user has used, is refused without saying why.
- A deposit whose payment has completed cannot be replayed, and its key is refused. A transfer can be replayed
  at any time and returns the original receipt, even after the balance was spent. A withdrawal can be replayed at
  any time too and returns its original receipt, whatever its status has become.
- A request the wallet refuses records nothing and does not use up its key. A transfer refused for low funds
  can go through under the same key after a top-up. There are two exceptions. A deposit that Stripe itself
  refuses keeps its key tied to the refused request. A withdrawal refused for want of a bank account or of funds is
  recorded, and its key is spent: the same key gets the same refusal even after a top-up.
- A deposit, a transfer and a withdrawal are protected separately, so a key used for one does not block the others.
  Use a fresh key for each operation anyway.
- When the service is briefly unavailable, the client cannot tell whether its request went through. Sending
  the same request again with the same key is safe: the money still moves at most once, and the answer says
  what happened.

Details: [API reference](api.md#wallet-service). Reasoning:
[ADR 0005](adr/0005-client-supplied-idempotency-keys.md),
[ADR 0022](adr/0022-stripe-charge-rules-checked-before-the-reservation.md),
[ADR 0025](adr/0025-unreachable-database-answers-503.md).

## Ledger and history

- Every balance change writes one ledger entry for each wallet it touches, together with the balance change. A
  deposit writes a `DEPOSIT` entry; a transfer writes `TRANSFER_OUT` for the sender and `TRANSFER_IN` for the
  recipient; a withdrawal writes `WITHDRAWAL`, and its money coming back writes `WITHDRAWAL_RETURNED`.
- The ledger only grows. The application never changes or deletes an entry.
- Each wallet numbers its entries 1, 2, 3 in the order they happened, with no gaps. The number belongs to the
  wallet, so the two legs of a transfer take their numbers from their own wallets.
- A user sees their own wallet's full history, newest first by entry number, so the newest entry's balance after
  is the wallet's balance. History is paged by entry number, and paging never shows an entry twice or skips one,
  even while money arrives.

Details: [data model](data-model.md), [API reference](api.md#wallet-service). Reasoning:
[ADR 0012](adr/0012-balances-and-append-only-ledger.md),
[ADR 0021](adr/0021-per-wallet-ledger-entry-numbers.md).

## Payment outcomes

- A payment changes only on Stripe's word: a webhook signed with the configured Stripe signing secret, or
  Stripe's own answer when Payment Service asks about a payment whose webhook has not arrived. Until the signing
  secret is set, every webhook is refused.
- Only two outcomes change a payment: the payment succeeded or it failed. Every other event, and every event
  about a payment FlowWallet did not start, is accepted and changes nothing.
- The wallet credits the amount the deposit was started with. Stripe's figure is only compared with it: a
  success whose payment intent has not succeeded, or whose amount or currency differs from the deposit, changes
  nothing.
- A payment starts pending and ends succeeded or failed. Succeeded is final; failed is not, since the customer
  can retry the same payment.
- A payment is credited at most once, however many times its events are delivered.
- Because a failure moves no money, the order in which a payment's outcomes arrive does not matter.
- A deposit that is still pending, or that failed after a declined card, is checked with Stripe from 15 minutes
  after it started until a day after, about every 15 minutes. A payment Stripe reports as succeeded is credited,
  and one Stripe canceled fails; one that is not settled yet stays as it is. Nothing cancels a payment. A payment
  that settles more than a day after it started and whose webhook is lost needs an operator.
- An outcome the wallet cannot apply, such as a conflicting second completion for a payment already credited, a
  credit for a wallet it cannot find, or a credit whose amount is off its currency's grid, is recorded as
  rejected with its payload and changes no balance. Only an operator can act on it, by sending the payment again
  as a new event once the cause is fixed; it is still credited at most once.

Details: [webhooks](api.md#provider-webhook), [the wallet consumer](../ARCHITECTURE.md#the-wallet-consumer),
[events](events.md). Reasoning: [ADR 0017](adr/0017-webhooks-verified-before-they-are-read.md),
[ADR 0009](adr/0009-payment-event-contract.md), [ADR 0010](adr/0010-idempotent-payment-event-consumer.md),
[ADR 0019](adr/0019-payment-event-amounts-on-the-grid.md),
[ADR 0032](adr/0032-pending-payments-are-rechecked-with-the-provider.md).
