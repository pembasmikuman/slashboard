# Slashboard

Client-only Fabric mod for Minecraft 26.3. Press `H` (rebind it in Options, Controls, Key Binds, under Slashboard), pick a command from a grouped menu, fill in its values, run it. Works on any server, nothing to install server-side.

See [docs/spec.md](docs/spec.md) for what it does and why.

## Build

Gradle runs on the Java 25 JDK that Prism already ships, so nothing extra needs installing.

```bash
JAVA_HOME=~/.local/share/PrismLauncher/java/java-runtime-epsilon ./gradlew build
```

The jar lands in `build/libs/`. Tests (the command tree walker) run as part of `build`.

## Deploy to Onyx

```bash
scp build/libs/slashboard-*.jar onyx:.local/share/PrismLauncher/instances/26.3/minecraft/mods/
```

Remove the older `slashboard-*.jar` from that folder when the version number changes.

## Files

- `config/slashboard.json` in the instance holds presets and recent commands. If it ever gets corrupted it's renamed to `slashboard.json.bad` and a fresh one is started.
