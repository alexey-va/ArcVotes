# ArcVotes architecture

## Trust boundary

The feature-specific JDK HTTP server listens on `127.0.0.1` by default. A managed reverse proxy terminates TLS and maps opaque external paths to the four stable internal routes. The plugin trusts forwarded client addresses only when the direct peer is loopback and the option is enabled. Authentication is always cryptographic or an exact configured bearer comparison; IP allowlists are defense in depth.

Handlers cap the body before parsing, reject duplicate fields and unsupported media types, validate the source-specific signature in constant time, and never log payloads, secrets, IP fields, or player names. A callback is acknowledged only after its durable intent is inserted or recognized as a duplicate.

## State and reward flow

`arc_votes_events` owns the normalized callback intent. `(source, external_id)` is unique, so provider retries are idempotent. `arc_votes_reward_components` snapshots every configured effect at callback time; existing pre-component rows retain their legacy Vault-only snapshot.

The Paper `/vote` surface queries distinct callback sources by normalized player name and the configured calendar-day window. Its bounded TTL cache is read-only derived state; callback persistence remains the source of truth.

Each Paper node periodically takes a main-thread snapshot of online names and issues one bounded asynchronous SQL query for their `PENDING` events. Per-player execution is serialized. Every reward component derives its own stable one-time-use identity before value mutation, so a committed standard deposit is not repeated while a premium component retries. Known pre-mutation failures release only that component claim; successful deposits commit it; unknown outcomes move the event to durable operator recovery.

The default currency is applied through Vault. Additional currencies use the exact RedisEconomy API contract discovered from the active provider; startup fails closed when a configured currency is absent or disabled. Paper API and provider mutations remain on the primary thread, while JDBC and ledger storage remain asynchronous.

No Prometheus exporter is created. Health is contributed through `PaperPluginRuntime`, and low-cardinality structured events are written through `arc-core-logging`.

## Configuration generations

`/arcvotes reload` constructs fresh, uncached settings and locale catalogs, validates them, prepares provider/ingress state, and then replaces one `AtomicReference`. A request or reward component already in progress retains the immutable generation it captured; new work observes the replacement. The HTTP socket, Bukkit reward listener, reward ledger, SQL runtime, and ingress counters have stable ownership and are never duplicated by an ordinary reload.

`server-id` and MySQL define durable namespaces/connections and are restart-only. An active HTTP socket also fixes its bind address, port, worker count, and queue capacity. All remaining plugin settings are generation-owned and live-reloadable. The reward poll uses one stable one-second scheduler tick with a live configurable due interval, so changing its interval does not invalidate asynchronous ledger completions.
