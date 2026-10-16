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

**Current milestone: M3 done; PR open for the user's local test. Next: M4, only after the user confirms M3 works locally.**

| Milestone | State |
| --- | --- |
| M1 Proof of loop | Done, confirmed working by the user locally (2026-10-16). |
| M2 Real UX | Done, confirmed working by the user locally (2026-10-16). |
| M3 Content-addressed storage | Done. 18 JUnit tests + `runSelfTest` 34/34 headless. Awaiting the user's local test. |
| M4 Load menu + config screen | Not started |
| M5 Pack hardening + Skyrim reskin | Not started |
| v2 Hot restore | Not started |

### M3: storage, rotation, autosaves

Storage (`snapshot.WorldStore`, one instance per world; `snapshot.SnapshotStore` is the static facade + slot policy):

```
.minecraft/quicksaves/<world folder>/
  objects/ab/ab34f9...           one file per unique content, named by xxHash64 (16 hex)
  objects/tmp/                   in-flight copies (gc deletes leftovers)
  saves/<slot>/manifest.json     {version, files: [{path, size, mtime, hash}]}  ('/' separators)
  saves/<slot>/meta.json         SaveMeta
  saves/<slot>/complete          written last; a slot without it is invisible
  saves/<slot>.tmp/              slot being written, moved into place atomically
  _pre_load/manifest.json        the world right before the last load (also in objects/)
  _pre_load/restore.inprogress   properties (world=, slot=, started=) while a restore runs
  hashcache.json                 path -> {size, mtime, hash, checkedAt}
```

- Slot ids: `quick-yyyyMMdd-HHmmss-SSS`, `auto-...`, `manual-...` (`-2`, `-3` suffix on a clash).
- `snapshot.Xxh64`: own streaming XXH64 port (no extra dependency), checked against the reference implementation in `Xxh64Test`.
- Save: walk the world (skip top-level session.lock); a file whose size+mtime match `hashcache.json` is not read at all, unless "racy" (mtime within 2 s of when it was hashed: a same-size rewrite inside the timestamp granularity would otherwise be missed). Other files are copied into `objects/tmp` while hashing in one pass, then moved to their hash (or dropped if that object exists). The file is re-stat'ed after reading; a change mid-read fails the save.
- Restore: snapshot the current world as `_pre_load` (cheap, it's incremental), write the marker, then **verify every object of the slot (size + hash) before touching the world**. A corrupt/missing object fails the load with the world untouched and the marker removed. Then wipe (keep session.lock) and rebuild with real byte copies (verified again while copying), restoring each file's saved mtime so the next save sees them as unchanged. A failure after the wipe rolls back from `_pre_load`. GC runs at the end.
- Why verify first: `_pre_load` now shares objects with the slots (dedup), so it is no longer an independent copy; rolling back over a corrupt shared object would fail too.
- After each save (`SnapshotStore.afterSave`, still on the server thread but after saving is re-enabled): rotate (`quicksaveSlots` / `autosaveSlots`, newest kept), GC unreferenced objects, then while the store is over `maxStorageMB` (0 = no cap) delete the oldest autosave, then the oldest quicksave, never manual saves or the save just made.
- M1/M2 full-copy slots (`saves/<slot>/world/`) are listed and loadable as-is and converted to manifests on the next save; a legacy `_pre_load/world/` can still be rolled back.
- `WorldStore.checkConsistency()` reports missing/wrong-size/unreferenced objects (used by tests).

Autosaves (`flow.AutosaveScheduler`, main source set, server thread):
- Timer: `autosaveIntervalMinutes` of *unpaused* server ticks since the last successful save of any kind.
- Dimension change (Fabric `AFTER_PLAYER_CHANGE_WORLD`): 10 s after arriving. A 3 s delay hitched for 6 s in the Nether (the flush waits for in-flight chunk generation); 10 s took 1.6 s on the headless software-GL box.
- Sleep (Fabric `STOP_SLEEPING`, only if the night was skipped: time of day < 1000).
- Quit: `mixin.server.ServerPlayNetworkHandlerMixin` at `onDisconnected` HEAD (player still in the world, server thread). `ReloadController` calls `AutosaveScheduler.suppressNextQuitAutosave()` before closing the world for a load, and a dead player is refused by the guard, so loads never autosave.
- "Start": on JOIN when the world has no saves (new world, or an old world the mod is new to), 5 s later.
- Refused for combat/falling/lava/no player: retried every 2 s. Labels: "Start", "Autosave", "Autosave (new dimension)", "Autosave (slept)", "Autosave (quit)".
- `SaveService.addListener`: the client toasts "Autosaved"; the scheduler resets its timer.

Not in M3: thumbnails (`thumb.png`, needs the render thread; comes with the M4 load menu), load menu, delete/rename UI, config screen.

### M2 summary (still current)

- `config/quicksave.json` (`config.QuicksaveConfig`, Gson, every key from the design; rewritten on load so new keys appear).
- Keys under Controls › Quicksave: Quicksave F6, Quickload F9 (`confirmQuickload` → ConfirmScreen). F9 loads the newest QUICK/AUTO slot.
- `flow.SaveRequests`: guard check on the server → "Quicksaving..." toast → 4 client ticks so the toast is on screen → save on the server thread → result toast. `/qs save` uses the same `snapshot.SaveService`; `/qs save <name>` makes a manual save.
- `guard.SaveGuard`: refuses when LAN-open, hardcore (unless `allowInHardcore`), dead, in lava, falling, or in combat (mob within `combatRadius` targeting the player, or `combatCooldownSeconds` after taking/dealing damage via Fabric `AFTER_DAMAGE`). "falling" = not on ground, `fallDistance > 1`, not flying/gliding/swimming/climbing/riding. Server-side player velocity is NOT updated from movement packets, so it can't be used.
- `ui.SaveToast`: one `SystemToast.Type`, so "Quicksaving..." is replaced in place by the result. M5 reskins it.
- Death screen: death cam phase (UI hidden; click/key skips it), then fade-in, "Loading last save in N..." auto-loads the newest save of any type at 0 (`deathCountdownSeconds = 0` waits for a click). `shouldPause()` is false so the world keeps running. The "Load..." button waits for the M4 load menu.
- Death cam (`ui.DeathCam` + `CameraMixin` at `Camera#update` TAIL): pullback / orbit / fixed. While it is active: `GameRendererMixin` cancels `renderHand`, `InGameHudMixin` cancels `renderCrosshair` (both are gated on the perspective *option*, not the camera), `ClientPlayerEntityMixin` stops `ClientPlayerEntity#updatePostDeath` removing your own body at deathTime 20, and `LivingEntityMixin` cancels the "poof" (`addDeathParticles`) for the local player.
- Pause menu: Save (→ `ui.SaveNameScreen`, named manual save) and Load (confirm → newest save) above "Save and Quit to Title", via Fabric `ScreenEvents.AFTER_INIT` + `Screens.getButtons` in `ui.PauseMenuButtons` (not a GameMenuScreen mixin). Inactive with a tooltip while LAN-open; Load inactive with no save.
- Crash recovery (`flow.RecoveryPrompt`): on the first title screen after launch, any `_pre_load/restore.inprogress` → ConfirmScreen Finish Load / Roll Back → NoticeScreen. A world whose recovery fails isn't asked about again until restart.

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
| `NoticeScreen(Runnable, Text, Text)`, `MessageScreen(Text)` | exist. The 3-arg NoticeScreen button says "Back"; pass `ScreenTexts.OK` with the 5-arg ctor. |
| `Camera#update(BlockView, Entity, boolean thirdPerson, boolean inverseView, float tickDelta)` | private `thirdPerson`, protected `setRotation(float, float)`, `setPos(double, double, double)`, `moveBy(float, float, float)`, private `clipToSpace(float)` (raycasts from the current pos along `horizontalPlane`, so set pos + rotation first). |
| `GameRenderer#renderHand(Camera, float, Matrix4f)`, `InGameHud#renderCrosshair(DrawContext, RenderTickCounter)` | private; both decide from `options.getPerspective()`. |
| `ClientPlayerEntity#updatePostDeath` | calls `this.remove(KILLED)` when `deathTime == 20` (client-side body removal). `LivingEntity#addDeathParticles` is private, called from `handleStatus(60)`. |
| `GameMenuScreen` | exit button text `menu.returnToMenu` (singleplayer), width 204, grid at 25% height. `GameMenuScreen(false)` (F3+Esc) has no buttons. |
| `SystemToast.Type(long)`, `SystemToast.show(ToastManager, Type, Text, Text)` | `show` replaces an existing toast of the same type. |
| `ServerLivingEntityEvents.AFTER_DAMAGE` | `(entity, source, baseDamageTaken, damageTaken, blocked)`. |
| `KeyBinding.onKeyPressed(InputUtil.Key)` | used by the self-test to press F6/F9 through the real bindings. |
| `MinecraftClient#getLevelStorage().getSavesDirectory()` | saves/ dir, used by crash recovery (no world open then). |
| `ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD` | `(player, origin, destination)`. |
| `EntitySleepEvents.STOP_SLEEPING` | `(entity, sleepingPos)`. A night skip sets time to `l - l % 24000` (next morning) and then wakes players, so time of day is ~0 in the callback. `World#isDay()` uses `ambientDarkness`, which lags a tick: don't use it there. |
| `ServerPlayConnectionEvents.JOIN` | `(handler, sender, server)`. |
| `ServerLifecycleEvents.SERVER_STOPPING` | fires at `MinecraftServer#shutdown` HEAD; by then the SP player has usually disconnected, so the quit autosave hooks `ServerPlayNetworkHandler#onDisconnected(DisconnectionInfo)` HEAD instead (`public ServerPlayerEntity player`). |
| `ServerPlayerEntity#teleport(ServerWorld, x, y, z, Set<PositionFlag>, yaw, pitch)`, `#trySleep(BlockPos)` | used by the self-test for the dimension and sleep autosaves. |

**savingDisabled and chunk unloads (design question from the spec):** answered from source.
In 1.21.1, `ServerChunkLoadingManager#tick` skips `unloadChunks()` entirely while
`world.isSavingDisabled()`, so region chunk unloads and the 20-chunks-per-tick background
saves stop. But these still write while savingDisabled is set:
- `PointOfInterestStorage#tick` (→ `SerializingRegionBasedStorage#tick`) saves dirty POI sections every tick (`poi/`).
- `ServerEntityManager#tick` → `unloadChunks()` writes entity sections when their tracking status becomes HIDDEN (`entities/`).
- The periodic autosave (`saveAll(true, false, false)`, every 6000 ticks) still writes player data, advancements, stats and level.dat (only per-world chunk saves honour savingDisabled).

**Decision:** an off-thread copy is NOT safe with savingDisabled alone. The copy runs on the
server thread inside `WorldFlusher.runFlushed`. Since M3 it is incremental (only changed
files are read/copied), so the hitch scales with what changed, not world size. Moving it off-thread would need mixins gating the POI tick, entity unloads and the
autosave; revisit only if hitches are a problem.

**Measured in the user's local game (M1, new world):** `/qs save` took 200–300 ms. The user
saw a brief, abrupt camera freeze during the save; the world otherwise looked fine. Load
time felt "normal" (like opening the world from the menu). M2 shows the "Quicksaving..."
toast 4 client ticks before the copy starts (`SaveRequests.TOAST_LEAD_TICKS`) so the hitch
reads as intentional. **M2 local test: the user saw no freeze at all with F6.** The M1 freeze
happened with `/qs save` typed in chat; the cause was never pinned down (the render thread
shouldn't wait on the server). If it comes back on bigger worlds, profile it in M3.
M3 headless numbers (flat test world): 28–190 ms per save. Re-measure on the user's real,
bigger world.

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
  fine) drives the mod in a real client and writes `run/selftest/selftest-result.txt`
  (PASS/FAIL) plus screenshots in `run/selftest/screenshots/` (look at them: the death cam
  hand/crosshair/body issues were only visible there). The harness lives in the `selftest`
  source set (`src/selftest`), never packaged into the mod jar. It is a list of polled steps
  (`step(name, (client, ticks) -> done?)`); add steps for each milestone. Coverage (34 steps):
  world setup, Start autosave, guard refusals (falling, lava, combat), F6 via the key binding,
  quicksave rotation to 3 + incremental stats, timer / Nether / back-home / sleep autosaves and
  autosave rotation, pause-menu buttons + named manual save, `maxStorageMB` pruning order,
  store consistency, death cam (UI hidden, screenshots) + countdown auto-load timing, rewind
  checks, F9 + confirm, quit-while-dead → rejoin → Load, loads never autosave on quit, Save
  and Quit autosave, faked crash marker → Roll Back. Hooks: `AutosaveScheduler.testIntervalTicks`.
- `./gradlew test` (part of `build`): JUnit 5 tests for `Xxh64` and `WorldStore` in
  `src/test` (round trip, real copies not links, incremental skip, racy rewrite, mtime kept
  on restore, rotation + GC, pre-load GC, prune order, legacy conversion and rollback,
  corrupt object leaves the world untouched, unfinished-restore refusal, finish-interrupted,
  path escape, incomplete slots). `WorldStore` takes its directory in the constructor so it
  needs no Fabric runtime.

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
| Large worlds | first save is a full copy (later saves incremental since M3). A progress toast for the first save is still TODO. |
| Disk space | `maxStorageMB` + pruning, show total in the load menu. |

### Classes

| Class | Source set | Role |
| --- | --- | --- |
| `QuicksaveMod` | main | common entrypoint: config, guard, death log, `/qs save [name]`, world-path helpers |
| `QuicksaveClient` | client | client entrypoint: F6/F9 keys, tick handler, screen hooks |
| `config.QuicksaveConfig` | main | config/quicksave.json |
| `guard.SaveGuard` | main | combat / situation / LAN / hardcore save refusals |
| `snapshot.SaveService` | main | server-thread save entry: guard → flush → copy → meta |
| `snapshot.SlotType` | main | QUICK / AUTO / MANUAL |
| `snapshot.SnapshotStore` | main | static facade: `list`, `latest`, `save` + `afterSave` (rotate, GC, prune), `restore`, crash recovery, lock probe |
| `snapshot.WorldStore` | main | content-addressed store for one world: save, restore (verify first), pre-load, rotate, gc, prune, legacy conversion, consistency check |
| `snapshot.Xxh64` | main | streaming xxHash64 |
| `snapshot.WorldFlusher` | main | server thread: save-off, `saveAll(false, true, true)`, run copy, restore save flags |
| `snapshot.SaveMeta` | main | record for meta.json |
| `flow.ReloadController` | client | state machine IDLE → CLOSING → RESTORING → REOPENING; `loadLatest(types)` |
| `flow.SaveRequests` | client | F6 / pause-menu save flow with the toast lead-in |
| `flow.RecoveryPrompt` | client | launch prompt for an unfinished restore |
| `flow.AutosaveScheduler` | main | Start / timer / dimension / sleep / quit autosaves |
| `mixin.server.ServerPlayNetworkHandlerMixin` | main | quit autosave hook (`quicksave.mixins.json`) |
| `ui.QuicksaveDeathScreen` | client | death cam phase + countdown death screen |
| `ui.DeathCam` | client | death cam pose (pullback / orbit / fixed) |
| `ui.SaveNameScreen` | client | name dialog for pause-menu Save |
| `ui.PauseMenuButtons` | client | Save/Load on the pause menu (Fabric ScreenEvents; replaces the planned GameMenuScreenMixin) |
| `ui.LoadMenuScreen` | client | thumbnail save list (M4) |
| `ui.SaveToast` | client | "Quicksaving…" / "Can't save during combat" toasts |
| `mixin.MinecraftClientMixin` | client | swaps DeathScreen in setScreen |
| `mixin.DeathScreenAccessor` | client | reads the vanilla death message |
| `mixin.CameraMixin` | client | applies the death cam pose |
| `mixin.GameRendererMixin`, `mixin.InGameHudMixin` | client | hide hand / crosshair during the death cam |
| `mixin.ClientPlayerEntityMixin`, `mixin.LivingEntityMixin` | client | keep your body (no removal, no poof) during the death cam |

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
