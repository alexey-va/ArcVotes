# ArcVotes architecture

## Trust boundary

The feature-specific JDK HTTP server listens on `127.0.0.1` by default. A managed reverse proxy terminates TLS and maps opaque external paths to the four stable internal routes. The plugin trusts forwarded client addresses only when the direct peer is loopback and the option is enabled. Authentication is always cryptographic or an exact configured bearer comparison; IP allowlists are defense in depth.

Handlers cap the body before parsing, reject duplicate fields and unsupported media types, validate the source-specific signature in constant time, and never log payloads, secrets, IP fields, or player names. A callback is acknowledged only after its durable intent is inserted or recognized as a duplicate.

## State and reward flow

`arc_votes_events` owns the normalized callback intent. `(source, external_id)` is unique, so provider retries are idempotent. `arc_votes_reward_components` snapshots every configured effect at callback time; existing pre-component rows retain their legacy Vault-only snapshot.

Each Paper node periodically takes a main-thread snapshot of online names and issues one bounded asynchronous SQL query for their `PENDING` events. Per-player execution is serialized. Every reward component derives its own stable one-time-use identity before value mutation, so a committed standard deposit is not repeated while a premium component retries. Known pre-mutation failures release only that component claim; successful deposits commit it; unknown outcomes move the event to durable operator recovery.

The default currency is applied through Vault. Additional currencies use the exact RedisEconomy API contract discovered from the active provider; startup fails closed when a configured currency is absent or disabled. Paper API and provider mutations remain on the primary thread, while JDBC and ledger storage remain asynchronous.

No Prometheus exporter is created. Health is contributed through `PaperPluginRuntime`, and low-cardinality structured events are written through `arc-core-logging`.
