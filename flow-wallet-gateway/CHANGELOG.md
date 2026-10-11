# Changelog

## 1.0.0 (2026-10-11)


### Features

* **config:** move operational values out of constants and into configuration ([3dd0ea7](https://github.com/denys1204/FlowWallet/commit/3dd0ea73077ea702fee58fd4f9299547f6407e6b))
* **gateway:** implement API Gateway routes and fix vulnerabilities (Phase 1) ([4548b19](https://github.com/denys1204/FlowWallet/commit/4548b1905af80c5af97d3a8fc5e1cb827e4acd4c))
* **gateway:** listen on loopback by default and warn when exposed ([8ffda2f](https://github.com/denys1204/FlowWallet/commit/8ffda2fc4be45cd54df03942d5120f5909117178))
* let the services read the shared .env ([5d57a8b](https://github.com/denys1204/FlowWallet/commit/5d57a8b315af5011fff74167f4772f907105169e))
* **wallet:** open withdrawals ([9e1410c](https://github.com/denys1204/FlowWallet/commit/9e1410c8b4fc4a2996f9837b63f329b2032967e3))


### Bug Fixes

* **gateway:** bound the httpclient response and connect timeouts ([89f3b3f](https://github.com/denys1204/FlowWallet/commit/89f3b3f71ec60341fdb51a8ce973887d7c799137))
* stop creating wallets as a side effect of a payment ([630724e](https://github.com/denys1204/FlowWallet/commit/630724e1b7b053515450bf2927ad940ed2722f9f))
