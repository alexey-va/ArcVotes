# Real Paper voting menu tests

Run `./gradlew plugwrightTest` with Java 25. Gradle downloads Paper 1.21.11,
Plugwright 2.0.4 and Node 22.14.0. Paper binds to 127.0.0.1:25565 and its
disposable world/plugin data are recreated under `build/plugwright` each run.
Run local Paper suites sequentially.

Tests check configured voting-site names in chat, the inventory menu's voting
item and lore, its click-to-link response, and the admin status with real
provider readiness. The E2E fixture also enables the real loopback MonitoringMinecraft
callback, MySQL ledger, Vault, and RedisEconomy 4.5.12 providers. It submits a
synthetic bearer callback with a CI-generated secret, verifies the configured
1000 vault + 3 tokens reward through the provider balance commands, then
replays the same callback and proves both balances remain unchanged.

The callback secret is generated only in the CI process environment; it is not
stored in tracked YAML or fixtures. The fixture uses zero-balance Redis 7.4 and
the same ARC 1.4.3 provider distribution and RedisEconomy setup as the verified
ArcRanks E2E. GitHub Actions runs E2E independently and retains runner/Paper
logs on every outcome.
