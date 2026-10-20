# AGENTS.md

## What this is

Paper/ProtocolLib anti-cheat plugin. It extracts aim features from combat windows and POSTs them to an HTTP service; the model, key store, and admin backend are not in this repo (`oaiapi/` is intentionally ignored). Single Gradle project, no subprojects.

## Build

- JDK 21 is required by the Gradle toolchain even when producing Java 16 bytecode.
- Flavour comes from `-PmcVersion`, otherwise `gradle.properties` (`1.21.4`). `1.21*` selects `src/v121` and `--release 21`; everything else selects `src/v116` and `--release 16`. Keep 1.16 builds on Java 16 bytecode.
- README examples use `1.16.5` and `1.21.11`; the local default is `1.21.4`.

```powershell
.\gradlew.bat shadowJar -PmcVersion=1.16.5
.\gradlew.bat shadowJar -PmcVersion=1.21.11
```

- Output is `build/libs/OculusAI-<pluginVersion>-<mcVersion>.jar`. `build` also runs `shadowJar`; the plain `jar` task is disabled.
- The real plugin version is `-PpluginVersion` or `1.0.7`; `version` in `gradle.properties` is unused. `processResources` stamps `${version}` and `${flavour}` into `plugin.yml`/`build.properties` for the self-update check. Preserve the `inputs.property` declarations or incremental builds ship stale stamps.
- `runServer` uses the selected flavour and forces IPv4 (`preferIPv4Stack`) because API keys are bound to one address. ProtocolLib is a hard runtime dependency.

## Version trees

- Only one of `src/v116` or `src/v121` is added to the source set at a time, together with its resources. `src/main` is shared.
- Both version trees define the same fully qualified classes. `ui/Menus.java` and `ui/menu/*` are currently identical copies; mirror edits into both trees. `ui/Hud`, `packet/PacketBridge`, `packet/UseActions`, and `npc/NpcPackets` intentionally differ by version, so do not copy one over the other.
- `plugin.yml` exists only in the version resource trees; duplicate command/permission changes in both. Other resources (`config.yml`, `probcfg.yml`, `lang/*`, `build.properties`) live in `src/main/resources`.

## Wiring

- Services implement `Service` and are registered in a fixed order in `OculusAI.onEnable()`; enable runs forward, disable runs in reverse, and reload reuses the same order. Register a new service there, or inside its aggregate (`Configuration`, `Network`, `Players`, `Storage`, `Ui`), and add a getter if needed.
- Keep scheduling centralized: main-thread repeats go through `TickLoop`, background work through `AsyncWorker`, and API/event callbacks that touch Bukkit state go through `MainThread`. Do not add another Bukkit scheduler timer or thread pool outside `core`.
- Flow: ProtocolLib `PacketBridge` records rotation/attack samples into `PlayerData`; `Detection` builds windows; `FeatureExtractor`/`FeatureSchemas` build the feature map; `ApiClient.predict` runs on `AsyncWorker`; the result feeds buffer, history, journal, and punishment. Bukkit/player state is touched back on the main thread via `MainThread`/`TickLoop`.
- `config.yml` is the main config; `probcfg.yml` controls action bar/hologram display; `Language` loads `lang/<language>.yml`, defaults to `ru`, and falls back to `en`.

## Tests

- JUnit 5 only. A bare `.\gradlew.bat test` uses the default flavour (`1.21.4`).
- Focused run: `.\gradlew.bat test --tests "me.everyone.oculusai.detection.FeatureExtractorTest"`.
- Verify the other flavour explicitly: `.\gradlew.bat test -PmcVersion=1.16.5`. Tests cover only `src/main`; version-specific GUI/packet changes need `shadowJar` and a runtime check.
- There is no lint, formatter, or typecheck task. `test` plus `shadowJar` for the affected flavour is the available verification.
- Tests are server-free unit tests; Paper API is test runtime only.

## Invariants

- `FeatureSchemas.names(version)` is a contract with the model service. Do not reorder or rename existing feature columns; add a new schema version instead. `RunningStatsTest` asserts the v2/v3/v4 column counts.
- Only hits on player-like targets (players and dummies) count as combat; mob hits must stay excluded.
- Update downloads must remain HTTPS-only, parsed-host allowlisted (`github.com`, `objects.githubusercontent.com`), and SHA-256 verified. `UpdaterTest` guards `trusted`/`newer`; do not weaken these checks.
- For a new command: add a `SubCommand`, register it in `Commands.enable()`, add its permission to both `plugin.yml` files, and update the README command/permission tables. Add user-facing language keys to both `lang/en.yml` and `lang/ru.yml`.
- `.gitignore` ignores every `**/data/` directory; do not put source under a `data/` path.
