# 0014. A transfer is one local transaction in wallet_db with a fixed decision order

- Status: Accepted
- Date: 2026-09-26

## Context

`POST /api/wallets/{currency}/transfers` moves money from the caller's wallet to the wallet another user holds in the
same currency. Both wallets live in `wallet_db`. Before money moves, a transfer has to settle whether its
Idempotency-Key already names a transfer, whether the caller can afford it and whether the recipient holds a wallet.
Requests race on keys and wallets, and the order of the checks decides both the answer and what a caller learns about
other users. A ledger constraint violation can be a lost race for the key or a CHECK that caught a bug, and in Postgres
it aborts the transaction ([0007](0007-unique-constraints-decide.md)). A replay has to match the first answer byte for
byte ([0005](0005-client-supplied-idempotency-keys.md)).

## Decision

A transfer is one local transaction in `wallet_db`: the debit, the credit and both ledger legs commit together or not at
all. Payment Service and Kafka take no part, and there is nothing to compensate.

`TransferService.transfer` is not `@Transactional` ([0006](0006-short-transactions-across-bean-boundaries.md)). It
builds a `TransferCommand` with the currency from `Currencies.normalise`, the key and the recipient lower-cased with
`Locale.ROOT`, and the amount from `AmountPrecision.canonical`. That is the one form in which every value is locked,
stored and compared, and nothing after it normalises again. What the request alone decides is refused with 400 before a
connection is taken: the currency, the amount's precision, and a recipient equal to the caller once both ids are
lower-cased (`SelfTransferException`). A self-transfer therefore neither judges nor spends the key.

`TransferHandler.execute` is the only transactional method, `@Transactional(isolation = Isolation.READ_COMMITTED)`, and
it catches nothing. Its steps run in this order:

1. Lock both wallets in ascending user id ([0011](0011-wallet-row-locking.md)).
2. Throw `WalletNotFoundException` (404) if the caller holds no wallet in the currency.
3. Judge the key: `findByTransactionReferenceAndType(reference, TRANSFER_OUT)`, then `BalanceHistory.isRepeatOf` (same
   sender wallet, same recipient, amount by `compareTo`). A repeat is answered from the stored leg, and anything else
   throws `ConflictingTransferException` (409).
4. Call `Wallet.debit`, which throws `InsufficientFundsException` (422).
5. Throw `RecipientHasNoWalletException` (422) if the recipient holds no wallet in the currency.
6. Call `Wallet.credit`, then `save` the `TRANSFER_OUT` leg and `saveAndFlush` the `TRANSFER_IN` leg.

The key is judged under the locks and before the debit. Every transfer out of a wallet holds that wallet's lock, so
same-key requests from one wallet serialize and the second one's lookup sees what the first committed. Requests from
different sender wallets serialize the same way when their transfers share any wallet, such as the recipient. Only
transfers that share no wallet are decided by the ledger's unique `(transaction_reference, type)` index
([0012](0012-balances-and-append-only-ledger.md)).

Funds are judged before the recipient, so only a request the caller can afford learns that a recipient wallet is absent.
Opening a wallet costs nothing, so the caller's own 404 is no barrier. Confirming that a wallet exists takes a completed
transfer, which moves money and leaves the caller's id in the recipient's history. The detail, "The recipient holds no
{currency} wallet", names no id and does not say the user is unknown, because the wallet keeps no user registry
([0003](0003-caller-identity-and-trust-boundary.md)). The refusal is logged at WARN with both user ids. The debit is
made in memory and no statement runs before this refusal, so the rollback discards it with nothing flushed.

The sending leg is persisted before the receiving one and one flush writes both. Same-key transfers racing from
different wallets then take the index entries in the same order, and every constraint fires inside the repository call
rather than at commit.

After a `DataIntegrityViolationException`, `TransferService.explain` reads the ledger again once the rollback has ended
the transaction ([0007](0007-unique-constraints-decide.md)). A `TRANSFER_OUT` under the key means another sender wallet
reached the index first: 409. If `isRepeatOf` matches that row against the caller's own wallet
(`findByUserIdAndCurrency`), the answer is a replay, logged at WARN because the wallet lock should have kept that
request from reaching the index. No `TRANSFER_OUT` under the key means a CHECK fired or a value overflowed its column,
such as a recipient's balance growing past `NUMERIC(19,4)`. That violation is rethrown and `GlobalExceptionHandler`
answers 500 with the stack trace. A `ConcurrencyFailureException` becomes the 503 of `TransferBusyException`
([0011](0011-wallet-row-locking.md)).

Only `TransferResponse.of` builds the receipt, from the `TRANSFER_OUT` leg: the one in memory for the first answer, the
stored one for a replay. Both hold the amount and balance at the ledger's scale, so they render alike, and `of` throws
`IllegalArgumentException` for any other type. The receipt has no timestamp, no movement id and nothing about the
recipient's wallet, and on a replay `balanceAfter` is a past balance. The first answer and every replay are 200, because
no URL addresses a transfer.

The mapping declares `produces = MediaType.APPLICATION_JSON_VALUE`, so an `Accept` header that rules out JSON gets 406
while the handler is chosen, before anything runs. `@CurrentUserId` is the first parameter of
`TransferController.transfer`, and Spring MVC resolves arguments in declaration order, so 401 comes before every refusal
except 406. A request with several faults gets the first of 406, 401, 400, 404, the key (200 replay or 409), 422 for
funds and 422 for the recipient.

## Alternatives considered

- Routing transfers through Payment Service or Kafka events with compensation. One local transaction already makes the
  movement atomic, and a saga would add intermediate states to compensate.
- Mapping every violation to 409, for instance in one `@Transactional` bean that catches it at the flush, as
  `PaymentTransactionStore.reserve` does. Besides working on an aborted transaction, it reports every violation as a
  used key. The ledger's CHECKs catch rules the code failed to keep, such as an overdraft past `Wallet.debit`, and a 409
  would hide the defect and send the client to a new key.
- A `@Transactional` `TransferService`. The handler would join its transaction and the classifying reads would run on
  the aborted one.
- Normalising inside the transaction, or in more than one place. A second place that lower-cases the key is a second
  place that can forget to.
- Executing a self-transfer. It would lock one row twice and write two legs that cancel out. It is not a 422 either,
  because no wallet's state enters into it.
- Judging the key before the locks. A retry could pass while the original is in flight, reach the debit after the
  original spent the money, and answer 422 for a transfer that happened. Judged after the debit, a retry after the
  balance was spent gets 422 the same way.
- Checking the recipient before funds. Anyone could ask, for free, whether a user holds a wallet in a currency.
- Wording that says no such user exists. The wallet cannot tell a user without a wallet from an id that belongs to
  nobody.
- 404 for a missing recipient wallet. Here 404 means the caller's own wallet, and a problem response has no type to
  branch on ([0016](0016-error-model-and-status-codes.md)).
- Answering the caller's own lost index race with 409. The client would retry with a new key and the money would move
  twice.
- Writing the legs in varying order. Two transfers racing on one key could take the index entries in opposite orders and
  wait on each other in a cycle.
- Flushing at commit. The handler would log the transfer and build the receipt before any constraint fired, and the
  violation would come from the commit instead of the repository call.
- A `createdAt` or movement id in the receipt. A replay could not match byte for byte, and a `createdAt` would also have
  to come back from the timestamp column unchanged.
- A second way to build the receipt, such as from the command and the wallet. It would be a second place for the scale
  to drift. A receipt built from `TRANSFER_IN` would show the sender the recipient's balance.
- 201 for the first answer and 200 for the replay. The two would tell a client different things about one transfer, and
  a 201 has no `Location` to point at.
- No `produces` on the mapping. Spring MVC negotiates the response after the handler returns, so the money would move
  before the 406, and a client that treats a 4xx as final would take it for a transfer that never happened.
- Resolving identity after the other parameters. A caller with no usable identity would learn what else was wrong with a
  request it had no standing to make.

## Consequences

- A transfer has no intermediate state and publishes no event. A refused one writes nothing, so its key stays free
  ([0005](0005-client-supplied-idempotency-keys.md)).
- The refusal order is part of the API. A check that needs no database goes into `TransferService` before the command is
  built. A check that needs one takes a place in `execute`, and that place decides both the answer to a request with
  several faults and what the caller learns about others. `TransferHandlerTest` pins the lock order, the key lookup
  after both locks, funds before the recipient, no flush before the recipient refusal, and the leg order with one flush.
- An unexplained violation fails loudly as a 500 and is never reported as a conflict or a success.
- A caller who can afford a transfer can still confirm that a recipient wallet is absent. Each attempt leaves a WARN
  line with both user ids.
- The statuses appear with the others in [0016](0016-error-model-and-status-codes.md).
