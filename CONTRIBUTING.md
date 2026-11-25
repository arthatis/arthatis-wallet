# Contributing

Repo: https://github.com/arthatis/arthatis-wallet

1. Fork and clone this repo.
2. For big changes (flows, protocol, data format), open an issue first. Product decisions and the protocol spec are kept by the maintainers.
3. Run the tests before a PR: `npm test` in `server/` and `web/`, `./gradlew :core:test :app:testDebugUnitTest` in `mobile/`.
4. Send small, focused PRs. Changes that alter the product's direction are discussed in an issue first.
5. Never put secrets (tokens, keys, SMTP credentials) or user data in a PR.

Contributions are licensed under the AGPL-3.0.
