# Changelog

## 1.0.0 (2026-10-11)


### ⚠ BREAKING CHANGES

* **wallet:** return amounts as decimal strings at the currency's scale

### Features

* **config:** move operational values out of constants and into configuration ([3dd0ea7](https://github.com/denys1204/FlowWallet/commit/3dd0ea73077ea702fee58fd4f9299547f6407e6b))
* **contract:** add the withdrawal command and the payout events ([f914257](https://github.com/denys1204/FlowWallet/commit/f9142574e4264fb0fc91636cb90caa9d5e2e9f41))
* **gateway:** implement API Gateway routes and fix vulnerabilities (Phase 1) ([4548b19](https://github.com/denys1204/FlowWallet/commit/4548b1905af80c5af97d3a8fc5e1cb827e4acd4c))
* let the services read the shared .env ([5d57a8b](https://github.com/denys1204/FlowWallet/commit/5d57a8b315af5011fff74167f4772f907105169e))
* **payment:** let users set the bank account their withdrawals go to ([8a3b563](https://github.com/denys1204/FlowWallet/commit/8a3b563897cdec910b6b3ad1ceb3d86b5392554d))
* **platform:** require identities to be unguessable ([43b479d](https://github.com/denys1204/FlowWallet/commit/43b479d0d2d212193dc93d0edf9dbc2b3a92b4a9))
* **platform:** validate currencies against ISO 4217 instead of counting characters ([73fa724](https://github.com/denys1204/FlowWallet/commit/73fa72401820d0e1ea1857859c25e08a50ec9980))
* **wallet:** add the wallet and balance-history domain model ([8f9635e](https://github.com/denys1204/FlowWallet/commit/8f9635eeebd726e214c7d1c7e5a0306763b0021b))
* **wallet:** add the withdrawals table, the returned ledger type and an outbox ([1128986](https://github.com/denys1204/FlowWallet/commit/1128986c969bb7d6506b3cb93d27eff66eb262d4))
* **wallet:** credit balances from payment events, exactly once ([88d3784](https://github.com/denys1204/FlowWallet/commit/88d3784a16b4992a23bd223d3e325c754e53cfaf))
* **wallet:** give the wallet a REST layer ([7a501e7](https://github.com/denys1204/FlowWallet/commit/7a501e7c45522c1310f5f3535a9ed38f48d597d1))
* **wallet:** hold the currency casing invariant in the schema ([46e2fdb](https://github.com/denys1204/FlowWallet/commit/46e2fdbd92add704702043c22a6a3591a223422d))
* **wallet:** let one reference carry both legs of a transfer ([aaacf82](https://github.com/denys1204/FlowWallet/commit/aaacf825f69bf5abebf3b0816a58db2b626ac2f2))
* **wallet:** open withdrawals ([9e1410c](https://github.com/denys1204/FlowWallet/commit/9e1410c8b4fc4a2996f9837b63f329b2032967e3))
* **wallet:** order and page history by a per-wallet entry number ([1fed849](https://github.com/denys1204/FlowWallet/commit/1fed849e21839117bfd25e4db9e1e27e379f24e5))
* **wallet:** refuse a negative balance or an empty movement in the schema ([24c2567](https://github.com/denys1204/FlowWallet/commit/24c2567892e3f2030fa9dfd905efdc8d08340461))
* **wallet:** return amounts as decimal strings at the currency's scale ([821001f](https://github.com/denys1204/FlowWallet/commit/821001fb06955bc1d4bee0d931e8f9b5c2c7172d))
* **wallet:** settle withdrawals from the payout events ([08d5c5c](https://github.com/denys1204/FlowWallet/commit/08d5c5c2f720fce8e7d213c24eeb9f170ed5b11a))
* **wallet:** start deposits through the wallet, not around it ([8acc4eb](https://github.com/denys1204/FlowWallet/commit/8acc4eb1fc661269b94df66729f6fef2cc052d14))
* **wallet:** transfer money between two wallets in one currency ([cd91b70](https://github.com/denys1204/FlowWallet/commit/cd91b70dda00266a854286bf8104173ec92d0cff))


### Bug Fixes

* **config:** hide Postgres error detail and cap the pool wait ([72a366c](https://github.com/denys1204/FlowWallet/commit/72a366cd0b13a2fe8a508e6e0985d00ad313fc61))
* **gateway:** keep wallet, payment off the LAN and kafka-ui read-only ([1343a0d](https://github.com/denys1204/FlowWallet/commit/1343a0d8ecd8d704cdb1b5eab5949c925765f327))
* **logging:** drop user ids from logs and Stripe metadata ([136a549](https://github.com/denys1204/FlowWallet/commit/136a549d26f61539bae9ae54b2993b1b07087268))
* **payment:** accept only whole ISK, sent to Stripe in hundredths ([a6e10ae](https://github.com/denys1204/FlowWallet/commit/a6e10ae2dd1bcbb3a54ab0669116a687b56ee80a))
* **payment:** read the connected account again after setting a bank ([6aa9fb1](https://github.com/denys1204/FlowWallet/commit/6aa9fb1e1506e76a72119c920ea5417ced446373))
* **payment:** refuse deposits Stripe cannot charge before reserving the key ([097e8f5](https://github.com/denys1204/FlowWallet/commit/097e8f578b5466aee76a1642354c012209f46cbe))
* **payment:** send UGX amounts in hundredths, as Stripe reads them ([e3d880f](https://github.com/denys1204/FlowWallet/commit/e3d880fe55307aecbfda1d5d339b6bf64351d7a8))
* **platform:** answer an unreachable database with 503 ([18d52bb](https://github.com/denys1204/FlowWallet/commit/18d52bbdcc90392f936470bfb6d1b88c31ad58d9))
* stop creating wallets as a side effect of a payment ([630724e](https://github.com/denys1204/FlowWallet/commit/630724e1b7b053515450bf2927ad940ed2722f9f))
* stop quoting rejected input in problem details ([f0cdaab](https://github.com/denys1204/FlowWallet/commit/f0cdaabbb46a509880e4c1ac02827b9ce6ad935b))
* **wallet:** accept every UUID version as an idempotency key ([0d9c9d7](https://github.com/denys1204/FlowWallet/commit/0d9c9d76fa136ecc643d72e7c5e3c0757cd4fce6))
* **wallet:** check identity first and refuse non-JSON before any write ([f3d4e9a](https://github.com/denys1204/FlowWallet/commit/f3d4e9a2a3b3d0d9793680aa16a4acea517b0841))
* **wallet:** configure the dead-letter topic the way the main topic is configured ([91cca1d](https://github.com/denys1204/FlowWallet/commit/91cca1da250e97c17a9d31b18085c4c28d5bb07c))
* **wallet:** dead-letter a record with no event at once ([6f5b87a](https://github.com/denys1204/FlowWallet/commit/6f5b87a73daec03c8129b0fd261251935e0c0983))
* **wallet:** follow the payment service's port ([a1fd84c](https://github.com/denys1204/FlowWallet/commit/a1fd84c0edd43d86440489af1da832f919f32dfd))
* **wallet:** keep dead letters without a time limit and count them ([da308a2](https://github.com/denys1204/FlowWallet/commit/da308a2c68a840dbcf2d1633dc929e8e8e4b12a8))
* **wallet:** make the service startable for the first time ([980f773](https://github.com/denys1204/FlowWallet/commit/980f773ce54398411aa2ef159a80ed7f1775c837))
* **wallet:** pin the offset reset and cap the consumer retry backoff ([8d7eb09](https://github.com/denys1204/FlowWallet/commit/8d7eb097b08f90519aff2858d40c5485be07dab0))
* **wallet:** read the currency in a request body the way the path reads it ([6c4f342](https://github.com/denys1204/FlowWallet/commit/6c4f34208573e5b79657d09f128f4b8df09e0a08))
* **wallet:** refuse payment event amounts off the currency grid ([0134c04](https://github.com/denys1204/FlowWallet/commit/0134c0408f2624a7cc804139f2f4ad9520dc4cbb))
* **wallet:** refuse Payment Service client settings that fail every deposit ([d8cfafc](https://github.com/denys1204/FlowWallet/commit/d8cfafc2195edca5015223e5a78401fa47fc2e5c))
