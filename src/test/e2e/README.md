# Real Paper voting menu tests

Run `./gradlew plugwrightTest` with Java 25. Gradle downloads Paper 1.21.11,
Plugwright 2.0.4 and Node 22.14.0. Paper binds to 127.0.0.1:25565 and its
disposable world/plugin data are recreated under `build/plugwright` each run.
Run local Paper suites sequentially.

Tests check configured voting-site names in chat, the inventory menu's voting
item and lore, its click-to-link response, and the admin status in link-only
mode. They do not visit voting sites or submit real votes. MySQL callbacks and
reward delivery keep their separate JVM/integration coverage. GitHub Actions
runs E2E independently and retains runner/Paper logs on every outcome.
