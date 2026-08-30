# ArcVotes

Small Paper plugin for RusCrafting vote links, authenticated monitoring callbacks, durable deduplication, and configurable Vault plus RedisEconomy rewards.

Supported callback routes:

| Monitoring | Route | Contract |
| --- | --- | --- |
| MinecraftRating | `POST /callbacks/minecraft-rating` | form fields `username`, `timestamp`, `signature` |
| HotMC | `POST /callbacks/hotmc` | multipart fields `nick`, `time`, `sign` |
| MonitoringMinecraft | `POST /callbacks/monitoring-minecraft` | bearer-authenticated JSON with `nickname`, `server_id`, `timestamp`, and optional `test` |
| GameMonitoring | `POST /callbacks/gamemonitoring` | signed JSON webhook followed by an authoritative vote API lookup |

The HTTP listener binds to loopback by default and is disabled in the bundled configuration. Put it behind the managed TLS reverse proxy, configure one unguessable public callback URL per route, then enable only the adapters whose secrets and expected entity identifiers are present.

Paper reconciles pending rewards for online players every five seconds by default, so a player does not need to reconnect after voting. The interval, per-player batch bound, standard Vault amount, and RedisEconomy currency/amount are configured under `reward`; the bundled plugin remains reward-disabled until an operator enables its production profile.

## Secrets

Tracked YAML contains environment-variable names only. Set the variables named in `config.yml`, or place `KEY=value` entries in the plugin-local `.env` file. The latter is ignored by Git and must be readable only by the Minecraft service account.

## Build

```bash
./gradlew --no-daemon clean test shadowJar -ParcCoreDir=../arc-core
```

The packaged plugin is `build/libs/ArcVotes-0.2.0.jar`. MySQL integration tests run in CI, not in the local lane.
