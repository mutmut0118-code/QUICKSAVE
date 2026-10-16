# Quicksave — Fabric mod for Minecraft 1.21.1

Bethesda-style quicksaving for singleplayer. When you die you never respawn: the game
closes the world, restores your last save over it and reopens it, like Skyrim.

- Mod id `quicksave`, package `com.muto118.quicksave`, Java 21.
- Fabric Loom + **Yarn** mappings. Fabric API is the only hard dependency. Mod Menu +
  Cloth Config come later (M4) as optional dependencies.
- One jar, split `main` (common/server) and `client` source sets.
- This file is the source of truth for the design and for progress. Keep **Status** current.
  (`Quicksave Mod — Design Doc (Fabric 1.21.1).md` is the original upload; CLAUDE.md supersedes it.)

## Status

**Current milestone: M1 done and confirmed working by the user in a local game (2026-10-16). Next: M2.**

| Milestone | State |
| --- | --- |
| M1 Proof of loop | Done. `/qs save`, death screen swap, close → pre-load backup → restore → reopen. `runSelfTest` passes headless (both phases: death → Load, and quit-while-dead → rejoin → Load). Confirmed working by the user locally. |
| M2 Real UX | Not started |
| M3 Content-addressed storage | Not started |
| M4 Load menu + config screen | Not started |
| M5 Pack hardening + Skyrim reskin | Not started |
| v2 Hot restore | Not started |

What M1 deliberately does **not** have yet: keybinds, countdown, death cam, toasts, pause-menu
buttons, config file, combat/situation blocking, crash-marker recovery prompt on launch
(the marker *is* written; nothing reads it yet), hardcore option (hardcore always gets the
vanilla screen), "Start" autosave, multiple slots.

M1 storage layout (temporary, replaced in M3):

```
.minecraft/quicksaves/<world folder>/
  saves/quick/world/...          full copy of saves/<world>/ minus session.lock
  saves/quick/complete           written last; a slot without it is ignored
  saves/quick.tmp/               in-progress save, moved into place atomically
  _pre_load/world/...            copy of the world taken right before each restore
  _pre_load/restore.inprogress   exists only while a restore runs
```

### Verified facts (checked against decompiled 1.21.1 / Yarn 1.21.1+build.3)

Names (all confirmed to exist with these signatures):

| Name | Notes |
| --- | --- |
| `MinecraftClient#setScreen(Screen)` | intermediary `method_1507`. If `screen == null` and the player is dead and `showsDeathScreen()`, vanilla builds `new DeathScreen(null, hardcore)` *inside* setScreen, so the mixin also handles the null-while-dead case at HEAD. |
| `MinecraftClient#disconnect(Screen)` | → `disconnect(Screen, false)`. Spins `render()` until `integratedServer.isStopping()`, which is `!serverThread.isAlive()`. The server thread closes the session (releasing `session.lock`) in `shutdown()` before exiting, so the lock is free when disconnect returns. `ReloadController` still polls the lock (30 s timeout) as a guard. |
| `ClientWorld#disconnect()` | exists; called before `client.disconnect(...)` exactly like `GameMenuScreen`/`DeathScreen.quitLevel`. |
| `MinecraftClient#createIntegratedServerLoader()` | returns `net.minecraft.server.integrated.IntegratedServerLoader` (note the package). |
| `IntegratedServerLoader#start(String name, Runnable onCancel)` | `name` is the world **folder** name. |
| `ServerWorld#savingDisabled` | public field. |
| `MinecraftServer#saveAll(boolean suppressLogs, boolean flush, boolean force)` | saves player data (`saveAllPlayerData`), then `save(...)`: each world with `savingDisabled && !force`, then level.dat incl. the SP host player via `getPlayerManager().getUserData()`. `force=true` overrides savingDisabled. |
| `MinecraftServer#isDedicated()`, `#isRemote()` | `IntegratedServer#isRemote()` is `lanPort > -1`, i.e. true once opened to LAN. |
| `MinecraftServer#getSavePath(WorldSavePath.ROOT)` | `saves/<world>/.` → normalize, `getFileName()` is the folder name. |
| `ThreadExecutor#isOnThread()` | used to assert server thread. |
| `ServerLivingEntityEvents.AFTER_DEATH` | Fabric API, `(LivingEntity entity, DamageSource source)`. |
| `CommandRegistrationCallback.EVENT` (v2) | `(dispatcher, registryAccess, environment)`; `environment.integrated` is a public boolean field. |
| `DeathScreen.message` | `private final Text message` (nullable) → `DeathScreenAccessor`. DeathScreen buttons activate after 20 ticks; QuicksaveDeathScreen copies that. Background gradient `0x60500000 → 0xA0803030`. |
| `MinecraftClient#tick` death check | Calls `setScreen(null)` only when `currentScreen == null` and the player is dead, so a non-`DeathScreen` replacement is not re-created every tick. |
| `NoticeScreen(Runnable, Text, Text)`, `MessageScreen(Text)` | exist. |

**savingDisabled and chunk unloads (design question from the spec):** answered from source.
In 1.21.1, `ServerChunkLoadingManager#tick` skips `unloadChunks()` entirely while
`world.isSavingDisabled()`, so region chunk unloads and the 20-chunks-per-tick background
saves stop. But these still write while savingDisabled is set:
- `PointOfInterestStorage#tick` (→ `SerializingRegionBasedStorage#tick`) saves dirty POI sections every tick (`poi/`).
- `ServerEntityManager#tick` → `unloadChunks()` writes entity sections when their tracking status becomes HIDDEN (`entities/`).
- The periodic autosave (`saveAll(true, false, false)`, every 6000 ticks) still writes player data, advancements, stats and level.dat (only per-world chunk saves honour savingDisabled).

**Decision:** an off-thread copy is NOT safe with savingDisabled alone. M1 copies on the
server thread inside `WorldFlusher.runFlushed` (a hitch proportional to world size). M3
keeps the copy on the server thread but makes it cheap (only changed files are hashed/
copied). Moving it off-thread would need mixins gating the POI tick, entity unloads and the
autosave; revisit only if hitches are a problem.

**Measured in the user's local game (M1, new world):** `/qs save` took 200–300 ms. The user
saw a brief, abrupt camera freeze during the save; the world otherwise looked fine. Load
time felt "normal" (like opening the world from the menu). For M2: show the
"Quicksaving..." toast on the frame *before* the copy starts (render first, then
`server.execute` the save) so the hitch reads as intentional. M3's incremental copy is the
real fix and should cut it to tens of ms. Re-measure after M3 on a bigger world.

### Build environment notes

- Versions (verified on meta.fabricmc.net): MC 1.21.1, Yarn `1.21.1+build.3`, loader `0.19.5`,
  Fabric API `0.116.17+1.21.1`, Loom `1.17.21`, Gradle wrapper `9.7.1` (Loom 1.17 needs Gradle ≥ 9.5).
- Loom 1.17 remaps mixin annotations directly in the jar (no refmap is produced). Verify a
  new mixin target by checking the remapped jar for the intermediary name.
- In the Claude Code cloud container, Maven Central (`repo.maven.apache.org`) answers 429/404.
  A machine-local init script `~/.gradle/init.d/central-mirror.gradle` points at
  `https://maven-central.storage-download.googleapis.com/maven2/` first. It is NOT in the
  repo; recreate it in a fresh container if dependency resolution fails.
- `./gradlew runSelfTest` (headless: `xvfb-run -a ./gradlew runSelfTest`, Mesa llvmpipe is
  fine) drives the whole M1 loop in a real client and writes `run/selftest/selftest-result.txt`
  (PASS/FAIL) plus screenshots in `run/selftest/screenshots/`. The harness lives in the
  `selftest` source set (`src/selftest`), which is never packaged into the mod jar. Extend it
  for each milestone.

## Design

### Core approach

A save is a snapshot of the world folder. A load closes the world, copies the snapshot
back, and reopens it. This is mod-agnostic: every mod's data in the world folder rolls
back with no compatibility code. Singleplayer only; the mod is inactive on dedicated
servers (logs that) and while the world is open to LAN (toast in M2).

**Snapshot contents:** everything in `saves/<world>/` (level.dat, region/, entities/, poi/,
DIM-1/, DIM1/, dimensions/, data/, playerdata/, advancements/, stats/, and anything mods add).
Skip `session.lock`. Storage lives OUTSIDE the world folder at
`.minecraft/quicksaves/<world folder name>/`, so snapshots never copy themselves and the
vanilla world list/backups stay clean.

**Consistent snapshot (server thread):** set `savingDisabled` on every ServerWorld →
`server.saveAll(false, true, true)` → copy files → restore each world's previous
`savingDisabled`. See "savingDisabled and chunk unloads" above: the copy stays on the server
thread.

### Death pipeline

1. Vanilla death plays normally (red tint, fall-over animation). Drops don't matter.
2. A mixin on `MinecraftClient#setScreen` swaps `DeathScreen` for `QuicksaveDeathScreen` if this
   world has at least one save. If it has none, the vanilla death screen shows (player never
   gets stuck).
3. Death cam (M2): third-person camera pulls back from the body (`deathCamSeconds`, default 2;
   `deathCamStyle` `"pullback" | "orbit" | "fixed"`).
4. Countdown screen (M2) shows "Loading last save..." (`deathCountdownSeconds`, default 3;
   0 = wait for click), buttons **Load Last Save** / **Load...** / **Quit to Title**. No Respawn.
5. Close the world: `client.world.disconnect()`, then `client.disconnect(new MessageScreen(...))`.
   Wait until the integrated server has stopped and `session.lock` is free.
6. Before restoring, copy the current world to `quicksaves/<world>/_pre_load/` with a
   `restore.inprogress` marker.
7. Restore the snapshot with real file copies, NEVER hardlinks (Minecraft rewrites region files
   in place, so hardlinks corrupt the stored snapshot). Files created after the save are deleted.
8. Reopen with `client.createIntegratedServerLoader().start(levelName, onCancel)`.
9. If the player quits while dead, vanilla shows the death screen again on rejoin, so the same
   mixin catches it (no dodging the reload by relogging).

On launch (M2), if a `restore.inprogress` marker exists, offer to finish the restore or roll back.
Quickload (F9) and the load menu enter the same pipeline at step 5.

**Safety rules:** never restore while the server runs; restore only after disconnect returns and
session.lock is free. Always take the pre-load backup first; if a restore fails partway, roll
back from it. If a marker from an unfinished restore exists, refuse to overwrite `_pre_load`.

### Controls (M2)

Quicksave **F6** (F5 is vanilla's perspective key), Quickload **F9** (confirm prompt,
`confirmQuickload`), Load menu unbound by default. All under Controls › Quicksave. Pause menu
gets **Save** (opens a name dialog → manual save) and **Load** buttons above "Save and Quit to Title".
Registered with `KeyBindingHelper` + `ClientTickEvents.END_CLIENT_TICK`; requests go to the
integrated server with `server.execute(...)`.

### Slots

| Type | Trigger | Kept |
| --- | --- | --- |
| quicksave | F6 | rotating, 3 (`quicksaveSlots`) |
| autosave | every 10 min of play, on dimension change, after sleeping, on Save and Quit, plus a "Start" autosave on a new world's first join | rotating, 3 (`autosaveSlots`) |
| manual | pause menu "Save", named | until deleted |
| _pre_load | automatically before any load | 1, overwritten each time |

### Storage (M3)

Content-addressed:

```
.minecraft/quicksaves/<world>/
  objects/<hash prefix>/<xxHash64>   each unique file stored once
  saves/<slot>/manifest.json         path → hash for every world file
  saves/<slot>/meta.json             name, type, real time, game day/time, dimension, xyz, health, playtime
  saves/<slot>/thumb.png             256×144 screenshot
  _pre_load/                         safety copy
```

Saving: hash each file with xxHash64; skip rehashing when size and mtime are unchanged since
the last save; copy new objects; write the manifest. Restoring: delete the world's snapshot-able
contents, rebuild from the manifest with real copies. Garbage-collect objects no manifest uses
when a slot rotates out. `maxStorageMB` cap prunes the oldest autosaves first.

### Config (`config/quicksave.json`, M2+; screen via Mod Menu + Cloth Config in M4)

| Key | Default | Meaning |
| --- | --- | --- |
| `quicksaveSlots` | 3 | rotating quicksaves kept |
| `autosaveSlots` | 3 | rotating autosaves kept |
| `autosaveIntervalMinutes` | 10 | 0 disables timed autosaves |
| `autosaveOnDimensionChange` / `autosaveOnSleep` / `autosaveOnQuit` | true | event autosaves |
| `deathCamSeconds` | 2 | 0 disables the death cam |
| `deathCamStyle` | `"pullback"` | `"pullback"`, `"orbit"`, `"fixed"` |
| `deathCountdownSeconds` | 3 | 0 waits for a click |
| `confirmQuickload` | true | prompt before F9 |
| `allowSaveInCombat` | false | false: refuse saves in combat ("Can't save during combat" toast) |
| `combatRadius` | 16 | a hostile mob targeting you within this many blocks = combat |
| `combatCooldownSeconds` | 5 | after taking or dealing damage |
| `blockSaveWhen` | `["falling", "in_lava"]` | other refusal situations; empty list = none |
| `allowInHardcore` | false | when true, hardcore death reloads instead of spectator lock |
| `maxStorageMB` | 4096 | prune oldest autosaves first |

### Edge cases

| Case | Handling |
| --- | --- |
| Dedicated server | inactive; keys/buttons hidden; server logs it (done in M1). `/qs` is registered only for the integrated environment. |
| LAN open | saving and loading disabled (M1: command refuses and the death screen isn't swapped; M2: toast). |
| Hardcore | `allowInHardcore` false by default → vanilla behaviour. |
| Death with no save | vanilla respawn; the "Start" autosave on first join makes this rare. |
| Crash mid-restore | `restore.inprogress` marker → launch prompt to finish or roll back (M2). |
| Mods with data outside the world folder | map mods, LOD caches don't roll back (cosmetic). |
| In-memory mod state | a full reload clears most; keep a known-issues list (M5). |
| Large worlds | first save is a full copy → progress toast; later saves incremental (M3). |
| Disk space | `maxStorageMB` + pruning, show total in the load menu. |

### Classes

| Class | Source set | Role |
| --- | --- | --- |
| `QuicksaveMod` | main | common entrypoint: death log, `/qs save` (M1 dev command), world-path helpers |
| `QuicksaveClient` | client | client entrypoint (keys/ticks/pause buttons from M2) |
| `snapshot.SnapshotStore` | main | `save`, `restore`, (M3: `list`, `delete`, `prune`); pre-load backup + marker; lock probe |
| `snapshot.WorldFlusher` | main | server thread: save-off, `saveAll(false, true, true)`, run copy, restore save flags |
| `snapshot.SaveMeta` | main | record for meta.json (M3) |
| `flow.ReloadController` | client | state machine IDLE → CLOSING → RESTORING → REOPENING |
| `flow.AutosaveScheduler` | client/main | timer + dimension/sleep/quit triggers (M3) |
| `ui.QuicksaveDeathScreen` | client | death screen (M1 simple, M2 countdown) |
| `ui.LoadMenuScreen` | client | thumbnail save list (M4) |
| `ui.SaveToast` | client | "Quicksaving…" / "Saving refused: falling" (M2) |
| `mixin.MinecraftClientMixin` | client | swaps DeathScreen in setScreen |
| `mixin.DeathScreenAccessor` | client | reads the vanilla death message |
| `mixin.GameMenuScreenMixin` | client | Save/Load pause-menu buttons (M2) |

## Milestones

- **M1 Proof of loop.** Dev command `/qs save` does a full world-folder copy to one slot. On death, a simple screen with a "Load" button runs close → pre-load backup → restore → reopen. No incremental storage, keys or death cam yet.
- **M2 Real UX.** F6/F9, countdown death screen, death cam, toasts, pause-menu buttons, combat/situation save blocking, crash marker recovery, config file.
- **M3** Content-addressed storage, rotating slots, autosaves, pruning.
- **M4** Load menu with thumbnails, rename/delete, Mod Menu + Cloth Config screen.
- **M5** Pack hardening and a reskin to a Skyrim-style look (dark vignette, serif type, parchment panel).
- **v2 stretch** In-place hot restore with no load screen.

Work one milestone per session and stop for the user to test locally before starting the next.

## Conventions

- Tabs for indentation, Yarn names, `quicksave$` prefix on mixin members.
- Verify every new vanilla name against the decompiled sources (`./gradlew genSources`, then
  read the `-sources.jar` under `.gradle/loom-cache/minecraftMaven/`) before using it.
- `./gradlew build` must pass before committing; run `runSelfTest` for anything touching the loop.
