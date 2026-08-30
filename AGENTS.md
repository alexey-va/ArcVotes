# ArcVotes development contract

- Target Paper `1.21.11`, Java `25`, Kotlin `2.3.0`.
- Compose infrastructure from `arc-core`; do not add `arc-core-metrics` unless a future feature explicitly owns a metrics exporter.
- Never store callback secrets in tracked YAML, source, logs, tests, or fixtures. Production secrets come from environment variables or the plugin-local untracked `.env` file.
- Callback handlers are loopback-only application ingress intended to sit behind the managed TLS reverse proxy.
- Treat callbacks as at-least-once delivery. Persist a durable intent before acknowledging it and use the shared one-time-use ledger before mutating player value.
- Keep Paper API calls on the primary thread. JDBC and outbound HTTP stay off it.
- Do not run Testcontainers-backed `integrationTest` locally. CI owns that lane.
