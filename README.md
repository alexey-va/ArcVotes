# ArcVotes

Small Paper plugin for RusCrafting vote links, authenticated monitoring callbacks, durable deduplication, and configurable Vault plus RedisEconomy rewards.

`/vote` reads accepted callbacks and marks each monitoring while that provider still considers the vote active, while keeping every destination clickable. [MinecraftRating](https://minecraftrating.ru/faq.html) and [MonitoringMinecraft](https://monitoringminecraft.com/promote/) document rolling 24-hour windows, while [GameMonitoring](https://gamemonitoring.ru/minecraft/servers/14210383/vote) documents 12 hours. [HotMC](https://hotmc.ru/vote-242482) says one vote per day; its observed rejection after a date boundary establishes a rolling 24-hour window rather than a calendar-day reset.

Administrators can use `/vote check <player>` for the same cached provider-specific view and `/vote history <player> [page]` for a newest-first MySQL history with reward state. `/vote status` reports only observable service health without exposing callback topology. History page size and the maximum accepted page number are live settings under `status`.

Supported callback routes:

| Monitoring | Route | Contract |
| --- | --- | --- |
| MinecraftRating | `POST /callbacks/minecraft-rating` | form fields `username`, `timestamp`, `signature` |
| HotMC | `POST /callbacks/hotmc` | multipart fields `nick`, `time`, `sign` |
| MonitoringMinecraft | `POST /callbacks/monitoring-minecraft` | bearer-authenticated JSON with `nickname`, `server_id`, `timestamp`, and optional `test` |
| GameMonitoring | `POST /callbacks/gamemonitoring` | signed JSON webhook followed by an authoritative vote API lookup |

The HTTP listener binds to loopback by default and is disabled in the bundled configuration. Put it behind the managed TLS reverse proxy, configure one unguessable public callback URL per route, then enable only the adapters whose secrets and expected entity identifiers are present.

Paper reconciles pending rewards for online players every five seconds by default, so a player does not need to reconnect after voting. The interval, per-player batch bound, standard Vault amount, and RedisEconomy currency/amount are configured under `reward`; the bundled plugin remains reward-disabled until an operator enables its production profile.

## Hot reload

Run `/arcvotes reload` with `arcvotes.admin.reload` after editing `config.yml`, `lang/ru.yml`, `lang/en.yml`, or the plugin-local `.env`. ArcVotes reads and validates an isolated candidate first and publishes the complete generation atomically; an invalid candidate leaves the current settings, callback ingress, and reward delivery active.

Live settings include locale selection and messages, monitoring enablement/presentation/authentication/network policy, reward enablement/components/amounts/currency/limits, vote-status cache settings, GameMonitoring HTTP limits, and the callback body/header/persistence/forwarded-address policy. Callback counters and in-flight requests survive reloads. Pending rows retain their original reward bundle, including a historical premium currency id.

`server-id` and every `mysql` field require a plugin/server restart. While the HTTP listener remains enabled, `http.bind-address`, `http.port`, `http.worker-threads`, and `http.queue-capacity` also require a restart. Those four listener fields can instead be changed live in two reloads: disable `http.enabled`, reload, edit/re-enable, then reload again. Enabling callbacks or rewards live still requires MySQL to have been initialized at startup and the required Vault/RedisEconomy providers to be available.

## Secrets

Tracked YAML contains environment-variable names only. Set the variables named in `config.yml`, or place `KEY=value` entries in the plugin-local `.env` file. The latter is ignored by Git and must be readable only by the Minecraft service account.

## Build

```bash
./gradlew --no-daemon clean test shadowJar -ParcCoreDir=../arc-core
```

The packaged plugin is `build/libs/ArcVotes-0.3.1.jar`. MySQL integration tests run in CI, not in the local lane.
