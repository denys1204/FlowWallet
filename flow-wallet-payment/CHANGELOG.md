# Changelog

## [1.0.3](https://github.com/denys1204/FlowWallet/compare/payment/v1.0.2...payment/v1.0.3) (2026-10-11)


### Bug Fixes

* **payment:** let a requeued payout go on once its stray transfers are reversed ([#11](https://github.com/denys1204/FlowWallet/issues/11)) ([29df32b](https://github.com/denys1204/FlowWallet/commit/29df32bd52b117c5042f3917c25634e999fcb0f6))

## [1.0.2](https://github.com/denys1204/FlowWallet/compare/payment/v1.0.1...payment/v1.0.2) (2026-10-11)


### Dependencies

* bump com.stripe:stripe-java from 33.1.0 to 34.0.0 ([#6](https://github.com/denys1204/FlowWallet/issues/6)) ([cac1426](https://github.com/denys1204/FlowWallet/commit/cac14265b006405efaa47a0a9aa6dde043a83c2a))

## [1.0.1](https://github.com/denys1204/FlowWallet/compare/payment/v1.0.0...payment/v1.0.1) (2026-10-11)


### Bug Fixes

* keep Kafka record content out of logs and dead-letter headers ([#7](https://github.com/denys1204/FlowWallet/issues/7)) ([dd6500a](https://github.com/denys1204/FlowWallet/commit/dd6500ae5e952e653bbb9c30a625fb6922a7ea15))

## 1.0.0 (2026-10-11)


### ⚠ BREAKING CHANGES

* stop asking the client which wallet to credit

### Features

* add custom exceptions and centralize provider resolution ([277bd8c](https://github.com/denys1204/FlowWallet/commit/277bd8c140f43d43a2f41a4cd8286d4db05f4125))
* **common:** add amount and currency to PaymentFailedEvent ([1f5e871](https://github.com/denys1204/FlowWallet/commit/1f5e871d0bca6183080bbef962faa6d24ed4e968))
* **config:** move operational values out of constants and into configuration ([3dd0ea7](https://github.com/denys1204/FlowWallet/commit/3dd0ea73077ea702fee58fd4f9299547f6407e6b))
* **contract:** add the withdrawal command and the payout events ([f914257](https://github.com/denys1204/FlowWallet/commit/f9142574e4264fb0fc91636cb90caa9d5e2e9f41))
* implement hybrid outbox pattern with Spring Events and Poller fallback ([542dd5c](https://github.com/denys1204/FlowWallet/commit/542dd5cfde622c9552d6cb4223652e8ad3a46ac4))
* let the services read the shared .env ([5d57a8b](https://github.com/denys1204/FlowWallet/commit/5d57a8b315af5011fff74167f4772f907105169e))
* **outbox:** implement robust state machine and optimistic locking ([7e10ea9](https://github.com/denys1204/FlowWallet/commit/7e10ea91c5e1c9265af5e975f273c53687fcb0ca))
* **outbox:** improve reliability and maintenance of transactional outbox ([0a2dc41](https://github.com/denys1204/FlowWallet/commit/0a2dc412de9ee079beaae55d71b3f6b3fd4ed7ad))
* **payment:** accept only card payments for deposits ([15b7fa2](https://github.com/denys1204/FlowWallet/commit/15b7fa2300c0b877d461a0fcb6a98cbb04e189e1))
* **payment:** accept withdrawal commands from wallet.withdrawals ([86cb4ab](https://github.com/denys1204/FlowWallet/commit/86cb4ab6afb24ccc192e36828094770066af4988))
* **payment:** add exponential retry backoff to outbox dispatch ([383b8f2](https://github.com/denys1204/FlowWallet/commit/383b8f2148662f8d7e86a25ffe3c3f3bc3e6ffc9))
* **payment:** add skeleton, liquibase, generic entities, and provider factory architecture ([23341d8](https://github.com/denys1204/FlowWallet/commit/23341d8b7b535270f452e1033173bbe9f37ae900))
* **payment:** add the payout accounts and payouts tables ([3bcd8dd](https://github.com/denys1204/FlowWallet/commit/3bcd8dd96b25ebb54d50674802a7b30fe074a2ef))
* **payment:** add the payouts actuator endpoint for operators ([f755972](https://github.com/denys1204/FlowWallet/commit/f7559721a0a38925f7a458f2f7e49072d9881779))
* **payment:** add the Stripe Connect payout calls behind PayoutProvider ([c0d7bab](https://github.com/denys1204/FlowWallet/commit/c0d7bab7232342b0ff79e82597d9bce241b12b39))
* **payment:** check a withdrawal against the terms and the reserve ([a8d7790](https://github.com/denys1204/FlowWallet/commit/a8d7790a6f46e3289b3909ffb8b0f78250fa3224))
* **payment:** create payment.events with a configurable min.insync.replicas ([5b6fc1e](https://github.com/denys1204/FlowWallet/commit/5b6fc1e73aa4f154e1042c29cfc107edfc1f2855))
* **payment:** enhance outbox resiliency and reduce kafka logs ([735646f](https://github.com/denys1204/FlowWallet/commit/735646fa7679e7721995a46606338eb3b76aaf36))
* **payment:** implement PaymentService and PaymentController for payment intent initiation ([d1a1ef0](https://github.com/denys1204/FlowWallet/commit/d1a1ef03620b037173e1864fc0d65a67c0ab0eea))
* **payment:** implement Stripe payment provider strategy with client wrapper and mapper ([5185e19](https://github.com/denys1204/FlowWallet/commit/5185e199b0b17ce528e4bcd76c2ff3955cdf5c06))
* **payment:** let users set the bank account their withdrawals go to ([8a3b563](https://github.com/denys1204/FlowWallet/commit/8a3b563897cdec910b6b3ad1ceb3d86b5392554d))
* **payment:** operationalize FAILED outbox events (metric + requeue endpoint) ([2d1e820](https://github.com/denys1204/FlowWallet/commit/2d1e820c57be52cca4ad65a34e5172a7a4ed583c))
* **payment:** re-check pending deposits with Stripe when the webhook is lost ([09b21da](https://github.com/denys1204/FlowWallet/commit/09b21daac54c598ed62bafe12d43885e3c334f9e))
* **payment:** reap outbox events stuck in PROCESSING back to PENDING ([c77e489](https://github.com/denys1204/FlowWallet/commit/c77e48927cbccd47299552b4cb3e0fc949b1dd43))
* **payment:** settle payouts from the Connect webhook and a reconciler ([dfb0f05](https://github.com/denys1204/FlowWallet/commit/dfb0f05c98070e3a0302e1d07d0c59d7e418c2a1))
* **payment:** work payouts through Stripe Connect in PayoutExecutor ([26bd6d9](https://github.com/denys1204/FlowWallet/commit/26bd6d9908e1523d7a4c6ebe7946a97fb5dae97a))
* **platform:** require identities to be unguessable ([43b479d](https://github.com/denys1204/FlowWallet/commit/43b479d0d2d212193dc93d0edf9dbc2b3a92b4a9))
* **platform:** validate currencies against ISO 4217 instead of counting characters ([73fa724](https://github.com/denys1204/FlowWallet/commit/73fa72401820d0e1ea1857859c25e08a50ec9980))
* **wallet:** open withdrawals ([9e1410c](https://github.com/denys1204/FlowWallet/commit/9e1410c8b4fc4a2996f9837b63f329b2032967e3))
* **wallet:** transfer money between two wallets in one currency ([cd91b70](https://github.com/denys1204/FlowWallet/commit/cd91b70dda00266a854286bf8104173ec92d0cff))


### Bug Fixes

* **config:** hide Postgres error detail and cap the pool wait ([72a366c](https://github.com/denys1204/FlowWallet/commit/72a366cd0b13a2fe8a508e6e0985d00ad313fc61))
* **config:** make every documented environment variable actually do something ([67fabe2](https://github.com/denys1204/FlowWallet/commit/67fabe2b8468ce0a4dbd43c0e85ab802f5a0cfa0))
* **contract:** give payment events an id and put their type on the wire ([8847dcf](https://github.com/denys1204/FlowWallet/commit/8847dcf1c5a2a03efc82dcffd9e386f8ea3df38e))
* **gateway:** keep wallet, payment off the LAN and kafka-ui read-only ([1343a0d](https://github.com/denys1204/FlowWallet/commit/1343a0d8ecd8d704cdb1b5eab5949c925765f327))
* **logging:** drop user ids from logs and Stripe metadata ([136a549](https://github.com/denys1204/FlowWallet/commit/136a549d26f61539bae9ae54b2993b1b07087268))
* **outbox:** make OutboxEventListener asynchronous and refine error handling ([27d0407](https://github.com/denys1204/FlowWallet/commit/27d04071f02855ce847fa77a9c46952b586f7932))
* **payment:** accept only whole ISK, sent to Stripe in hundredths ([a6e10ae](https://github.com/denys1204/FlowWallet/commit/a6e10ae2dd1bcbb3a54ab0669116a687b56ee80a))
* **payment:** acknowledge webhooks for intents this service never created ([87bfc2e](https://github.com/denys1204/FlowWallet/commit/87bfc2efa03de5afc073b4822fdfce3716967f1a))
* **payment:** bind the idempotency key to the terms it was used for ([08b8def](https://github.com/denys1204/FlowWallet/commit/08b8def236fcf0f49ff399af7e8e5f24109f2646))
* **payment:** bound the intent reference and resolve the caller first ([91bde02](https://github.com/denys1204/FlowWallet/commit/91bde0223a5f14ffe16ac918e3402939725c67fa))
* **payment:** charge the amount the customer actually asked for ([a26d06d](https://github.com/denys1204/FlowWallet/commit/a26d06df1bfe49b16f8e382d55e5791bee3c2aba))
* **payment:** count every failed outbox send and act only under the claim ([c12d312](https://github.com/denys1204/FlowWallet/commit/c12d312d7b6979cbbe2ac3daf18bcc26b5669130))
* **payment:** drop the duplicate index on provider_transaction_id ([3a1da00](https://github.com/denys1204/FlowWallet/commit/3a1da00fc4b6fbbb45a3893d1c5439f500f83be1))
* **payment:** give the outbox a ten-attempt default and validate its settings ([0be5bf8](https://github.com/denys1204/FlowWallet/commit/0be5bf85671c87ebd7abe9db4b97b6c8e59dcf72))
* **payment:** make payment creation idempotent ([d6c8102](https://github.com/denys1204/FlowWallet/commit/d6c81022545a4048522d621f62a5cf546b3e76d0))
* **payment:** make SUCCESS terminal in payment webhook state transitions ([9e85bb8](https://github.com/denys1204/FlowWallet/commit/9e85bb8a954229931887c15dcaa2f1133db02728))
* **payment:** name the minimum when a withdrawal is below it ([52b6220](https://github.com/denys1204/FlowWallet/commit/52b6220d99ee406b70133213d4db26d03bfc60b9))
* **payment:** read the connected account again after setting a bank ([6aa9fb1](https://github.com/denys1204/FlowWallet/commit/6aa9fb1e1506e76a72119c920ea5417ced446373))
* **payment:** recover a reserved payment instead of stranding it ([8309ae1](https://github.com/denys1204/FlowWallet/commit/8309ae12c9a34d8d6acba4c938bc7e477c586163))
* **payment:** refuse deposits Stripe cannot charge before reserving the key ([097e8f5](https://github.com/denys1204/FlowWallet/commit/097e8f578b5466aee76a1642354c012209f46cbe))
* **payment:** refuse to hand back an intent that was already paid ([ee62c7f](https://github.com/denys1204/FlowWallet/commit/ee62c7f71f7eb4de0a11def4a5dccbfa4cd13fa8))
* **payment:** resolve Spring Boot 4.x startup & config compatibility issues ([d47aee9](https://github.com/denys1204/FlowWallet/commit/d47aee9bdfc4f1e6f4c1f445b4df61026c4209f2))
* **payment:** resolve Stripe webhook signature case-sensitivity ([b333493](https://github.com/denys1204/FlowWallet/commit/b333493522c4d90d2b75510cf55c2bad6734ea47))
* **payment:** send UGX amounts in hundredths, as Stripe reads them ([e3d880f](https://github.com/denys1204/FlowWallet/commit/e3d880fe55307aecbfda1d5d339b6bf64351d7a8))
* **payment:** settle same-key initiation races and bound the Stripe call ([e1b618c](https://github.com/denys1204/FlowWallet/commit/e1b618c456cfacacd83d6254b93784ae48a8fb9b))
* **payment:** stamp payment events with the time Stripe settled the payment ([f640c34](https://github.com/denys1204/FlowWallet/commit/f640c34d60e7ded249036589f250f74c167c2aac))
* **payment:** stop deleting outbox events that were never delivered ([07d2a09](https://github.com/denys1204/FlowWallet/commit/07d2a09f210b025483f7e8eefaf0a4693fe29148))
* **payment:** switch off multipart parsing so the webhook size cap holds ([18d944d](https://github.com/denys1204/FlowWallet/commit/18d944df4c54e5e39199629825810861ea957152))
* **payment:** use REQUIRES_NEW for Outbox processed_at update ([e7b0897](https://github.com/denys1204/FlowWallet/commit/e7b089715ea91a9b6e1981fbcd3eb6d236293dd1))
* **payment:** verify webhooks before reading them and refuse all without a secret ([cd1bfc5](https://github.com/denys1204/FlowWallet/commit/cd1bfc5d5811c9f403d4ffd64bfd874e004d2436))
* resolve @Transactional self-invocation and improve Outbox reliability ([087e188](https://github.com/denys1204/FlowWallet/commit/087e1880b2984b8c25f9f576e722c692ca333713))
* stop quoting rejected input in problem details ([f0cdaab](https://github.com/denys1204/FlowWallet/commit/f0cdaabbb46a509880e4c1ac02827b9ce6ad935b))
* **wallet:** keep dead letters without a time limit and count them ([da308a2](https://github.com/denys1204/FlowWallet/commit/da308a2c68a840dbcf2d1633dc929e8e8e4b12a8))


### Code Refactoring

* stop asking the client which wallet to credit ([afd67cc](https://github.com/denys1204/FlowWallet/commit/afd67cc7aed1315d81e2ac05bc3db4c85b627406))
