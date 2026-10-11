# CloudChest

Fabric mod (MC 26.3). Zips the world when it closes into a folder your cloud app syncs.

1. `JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew build` -> `build/libs/cloudchest-1.0.0.jar`
2. Drop jar in instance `mods/` (needs Fabric API), launch.
3. Play, quit -> zip appears in `MinecraftBackups/` on Google Drive, iCloud or Dropbox (first one found). To pick another folder, set `destination=` in `config/cloudchest.properties`.

## Issues / PRs

Bugs, questions and pull requests welcome: open an issue or PR on GitHub.
