# ArcVotes architecture

## Trust boundary

The feature-specific JDK HTTP server listens on `127.0.0.1` by default. A managed reverse proxy terminates TLS and maps opaque external paths to the four stable internal routes. The plugin trusts forwarded client addresses only when the direct peer is loopback and the option is enabled. Authentication is always cryptographic or an exact configured bearer comparison; IP allowlists are defense in depth.

Handlers cap the body before parsing, reject duplicate fields and unsupported media types, validate the source-specific signature in constant time, and never log payloads, secrets, IP fields, or player names. A callback is acknowledged only after its durable intent is inserted or recognized as a duplicate.

## State and reward flow

`arc_votes_events` owns the normalized callback intent. `(source, external_id)` is unique, so provider retries are idempotent. A player receives a reward only while online. Before Vault is called, ArcVotes claims the event UUID in the shared `arc_one_time_uses` ledger. Known pre-mutation failures release the claim; successful deposits commit it; unknown outcomes are abandoned and retained for operator reconciliation instead of being retried automatically.

No Prometheus exporter is created. Health is contributed through `PaperPluginRuntime`, and low-cardinality structured events are written through `arc-core-logging`.
