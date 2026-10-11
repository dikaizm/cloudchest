# CloudChest

Fabric mod (MC 26.3). Zips the world when it closes into a folder your cloud app syncs.

1. `JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew build` -> `build/libs/cloudchest-1.0.0.jar`
2. Drop jar in instance `mods/` (needs Fabric API), launch.
3. Play, quit -> zip appears in `MinecraftBackups/` on Google Drive, iCloud or Dropbox (first one found). To pick another folder, set `destination=` in `config/cloudchest.properties`.

## Issues / PRs

Bugs, questions and pull requests welcome: open an issue or PR on GitHub.

## Modes

`mode=mirror` (default): keeps one up-to-date copy at `MinecraftBackups/<world>/world/`, copying only changed files, so the cloud app uploads just those. Region files (`.mca`) are re-uploaded whole when any chunk in them changes. No history from CloudChest itself (Google Drive keeps file versions for a while).

`mode=zip`: one timestamped zip per backup, last `keep` kept. Full upload every time.

## Corruption check

Before copying, CloudChest checks every file it is about to back up: region files (`.mca`: header, chunk bounds, each chunk decompresses) and `level.dat`. If anything fails, the backup is skipped, your last good backup is kept, and a toast says which file. It catches truncated or garbled files, not logical damage (e.g. a valid chunk with wrong blocks).
