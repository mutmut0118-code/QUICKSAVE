# Quicksave Mod — Design Doc (Fabric 1.21.1)

Oct 14, 2026 · @Gerry W

## Overview

With this mod, death no longer respawns you. It rewinds the whole world to your last save, the way it works in Skyrim and Fallout. Working name: **Quicksave**, mod id `quicksave`, for Fabric on Minecraft 1.21.1, singleplayer only.

The Bethesda loop it recreates:

1. You quicksave before something risky (a dungeon, a boss, a jump).
2. You die.
3. A short death beat plays, then the game loads your last save. The world, your inventory, your XP, mobs, block changes, advancements and time of day all go back to that moment.

**Goals**

- Rewind the whole world on death, not just the player. Broken blocks come back, killed mobs return, and loot is un-looted.
- Quicksave / quickload hotkeys, rotating autosaves and named manual saves, with a load menu that shows thumbnails.
- Saving should feel instant after the first one (incremental snapshots, see Storage).
- Works alongside a large modpack without needing per-mod compatibility code.

**Non-goals (v1)**

- Multiplayer or dedicated servers. You can't rewind a shared world for one player.
- Reloading in place with no loading screen. Bethesda games show a load screen too, so a quick world reload is faithful. A hot reload is listed as a v2 stretch goal.

## Player experience

The player sees three new things: two hotkeys, a death screen with no Respawn button, and a load menu.

**Controls** (all can be rebound under Controls › Quicksave)

| Action | Default key | What happens |
| --- | --- | --- |
| Quicksave | F6 | Saves to the rotating quicksave slots. The screen hitches briefly, then shows a "Quicksaving…" toast in the corner. |
| Quickload | F9 | Loads the newest quicksave or autosave. A confirm prompt appears unless that's turned off in config. |
| Open load menu | (unbound) | Opens the Load menu. It's also on the pause menu. |

F5 is already vanilla's perspective toggle, so the default quicksave key is F6 instead of Bethesda's F5. Players can rebind it to F5 if they want.

**Death**

1. Vanilla death plays as normal: the red tint and the fall-over animation. Item drops don't matter because the world is about to be restored. Then a third-person death cam slowly pulls back from your body for 2 seconds, like Skyrim's. You can change the length or turn it off (\`deathCamSeconds\`).
2. A custom screen replaces the vanilla death screen. It shows the death message and "Loading last save…" with a 3-second countdown that can be configured.
3. Buttons: **Load Last Save** (skips the countdown), **Load…** (opens the load menu), **Quit to Title**. There is no Respawn button.
4. If the world has no saves yet, the vanilla death screen appears instead, so the player never gets stuck.

**Pause menu**

The pause menu gets **Save** and **Load** buttons above "Save and Quit to Title". **Save** opens a dialog to name a manual save.

**Load menu**

The load menu works like Skyrim's. It lists saves newest first. Each row shows a thumbnail (a screenshot taken when the save was made), the save name or type, the in-game day and time, the real date, the dimension and coordinates, and health. You can load, delete or rename a save from here.

**Theming for the modpack**

The death and load screens use a dark vignette, serif type and a parchment-toned panel, to match the Skyrim tone of the pack. All the textures live in a resource pack folder so they can be reskinned.

## Core technical approach

A save is a snapshot of the world folder on disk. A load closes the world, copies the snapshot back over the folder, and reopens the world. This is the same technique that backup mods use. It's the most reliable choice for a modded pack, because every mod's data in the world folder rolls back automatically, with no compatibility code.

**Options considered**

| Approach | How it works | Verdict |
| --- | --- | --- |
| Folder snapshot + world reload | Flush everything to disk, copy the world folder into a save slot. On load, close the world, copy it back, reopen it. | **v1.** Simple, robust and mod-agnostic. Costs a 5–15 s load screen, like a Bethesda load. |
| In-place hot restore | Track chunks as they change after a save. On load, swap the old chunk NBT, entities and player back in without leaving the world. | **v2 stretch.** No load screen, but it fights every mod that caches state in memory, and it's easy to corrupt. |
| Player-only rewind | Restore just the player's NBT (inventory, position, health). | Rejected. The world doesn't rewind, so it doesn't play like Bethesda. |

**What a snapshot contains**

Everything inside `saves/<world>/`: `level.dat`, `region/`, `entities/`, `poi/`, `DIM-1/`, `DIM1/`, `dimensions/` (modded dimensions), `data/` (raids, map items, a lot of mod data), `playerdata/`, `advancements/` and `stats/`. It skips `session.lock` and the mod's own folders.

**Where snapshots live**

`.minecraft/quicksaves/<world folder name>/` sits outside the world folder. That way snapshots never copy themselves, and the vanilla world list and world backups stay clean.

**Making the snapshot consistent (server thread)**

1. Turn saving off on every `ServerWorld` (`savingDisabled = true`, the same switch that `/save-off` uses).
2. Call `server.saveAll(false, true, true)`, which saves everything with flush and force on.
3. Copy the files off the server thread (see Storage), then turn saving back on.

The game keeps ticking during the copy, but nothing writes to disk until it's done. Step 1 needs testing: confirm that chunk *unloads* also stay off disk while saving is off. If they don't, fall back to copying on the server thread, which turns the save into a short hitch.

## Death interception and reload pipeline

The mod lets vanilla death happen, swaps out the death screen, and then runs a scripted close → restore → reopen. Quickload (F9) and the load menu enter the same pipeline at **Close world**.

&#91;embedded content: death-to-reload pipeline · 8 steps, 1 decision\]

The only branch is whether the world has a save yet. Without one, the player gets normal vanilla respawn.

**Hooks**

Names follow Yarn mappings for 1.21.1. Check each one against the mappings before coding.

| Step | Hook | Side |
| --- | --- | --- |
| Detect death | `ServerLivingEntityEvents.AFTER_DEATH` (Fabric API). Logs the death and doesn't save the world. | Server |
| Replace death screen | Mixin on `MinecraftClient#setScreen`. When it receives a `DeathScreen` and the world has a save, it opens `QuicksaveDeathScreen` instead. | Client |
| Close world | `client.world.disconnect()`, then `client.disconnect(new MessageScreen("Loading…"))`. This blocks until the integrated server has stopped and released `session.lock`. | Client |
| Restore files | `SnapshotStore.restore(worldDir, slot)` on a worker thread, with a progress screen | Client |
| Reopen world | `client.createIntegratedServerLoader().start(levelName, onCancel)` | Client |
| Quicksave key | `KeyBindingHelper` + `ClientTickEvents.END_CLIENT_TICK`. Sends the request to the integrated server with `server.execute(...)`. | Client → server |

**Safety rules**

- Never restore while the server is running. Restore only after `disconnect` returns and `session.lock` is free.
- Before restoring, copy the current world folder to `quicksaves/<world>/_pre_load/`. If a restore fails partway, roll back from that copy so a crash can't destroy the world.
- If the player quits while dead, vanilla shows the death screen again on rejoin. The same mixin catches it, so you can't dodge the reload by relogging.

## Save slots, storage and config

Saves are stored by content. Each world file is stored once per unique version and shared by every save that has it. After the first save, a quicksave only writes the region files that changed, which is usually a few MB even in a big world.

**Slot types**

| Type | Trigger | Kept |
| --- | --- | --- |
| Quicksave | F6 | Rotating, 3 slots |
| Autosave | Every 10 min of play, on a dimension change, after sleeping in a bed, and on Save and Quit | Rotating, 3 slots |
| Manual | "Save" on the pause menu, with a name | Until deleted |
| Pre-load backup | Made automatically right before any load | 1, overwritten each time |

The autosave triggers are the Minecraft versions of Skyrim's moments: going through a load door becomes a dimension change, and waiting becomes sleeping.

**On-disk layout**

```
.minecraft/quicksaves/<world>/
  objects/ab/ab34f9…        one file per unique content hash
  saves/quick-2026-10-14T21-03-11/
    manifest.json            path → hash for every world file
    meta.json                name, type, real time, game day/time, dimension, xyz, health, playtime
    thumb.png                256×144 screenshot
  _pre_load/                 safety copy
```

**Saving**

1. Hash each world file with xxHash64.
2. If the cached size and mtime are unchanged since the last save, reuse the old hash and skip hashing.
3. Copy new hashes into `objects/` and write the manifest.
4. When a slot rotates out, delete any objects that no manifest uses anymore.

**Restoring**

1. Delete the world's snapshot-able folders.
2. Rebuild them from the manifest. Always use real copies, never hardlinks. Minecraft rewrites region files in place, so a hardlink would quietly corrupt the stored snapshot.

**Config** (`config/quicksave.json`, with an optional Mod Menu + Cloth Config screen)

| Key | Default | Meaning |
| --- | --- | --- |
| `quicksaveSlots` | 3 | Rotating quicksaves kept |
| `autosaveSlots` | 3 | Rotating autosaves kept |
| `autosaveIntervalMinutes` | 10 | 0 turns timed autosaves off |
| `autosaveOnDimensionChange` / `OnSleep` / `OnQuit` | true | Event autosaves |
| `deathCamSeconds` | 2 | Third-person death cam before the countdown. 0 turns it off. |
| `deathCamStyle` | `"pullback"` | `"pullback"` (camera pulls away from the body), `"orbit"` (camera circles the body) or `"fixed"` (camera stays put) |
| `deathCountdownSeconds` | 3 | 0 waits for a click |
| `confirmQuickload` | true | Prompt before F9 |
| `allowSaveInCombat` | false | When false, saving is refused while you're in combat, with a "Can't save during combat" toast. When true, you can save anywhere. |
| `combatRadius` | 16 | Distance in blocks within which a hostile mob targeting you counts as combat |
| `combatCooldownSeconds` | 5 | Taking or dealing damage keeps you in combat for this long |
| `blockSaveWhen` | `["falling", "in_lava"]` | Other situations where saving is refused. An empty list removes these blocks. |
| `allowInHardcore` | false | See Edge cases |
| `maxStorageMB` | 4096 | When storage goes over this, the oldest autosaves are pruned first |

## Edge cases and risks

The biggest risk is data loss during a restore. The pre-load backup and the server-stopped check exist to cover it. Everything else is behavior to decide or test.

| Case | Handling |
| --- | --- |
| Multiplayer / dedicated server | The mod turns itself off. Keys and buttons are hidden, and the server logs that it's inactive. |
| LAN-opened world | Saving and loading are disabled while LAN is open, with a toast that says so. |
| Hardcore | Off by default (`allowInHardcore`). When it's on, death reloads instead of locking the world into spectator mode. |
| Death with no save yet | Vanilla respawn. On a new world's first join, take an automatic "Start" autosave so this rarely happens. |
| Crash or power loss mid-restore | On next launch, if `_pre_load/` contains a `restore.inprogress` marker, offer to finish the restore or roll back. |
| Mods with data outside the world folder | Map mods (Xaero's, JourneyMap) and LOD caches (Distant Horizons) don't roll back. The result is cosmetic: a map might briefly show a block you placed after the save. |
| Mods with in-memory global state | A full world reload clears most of it. Test the actual pack and keep a known-issues list. |
| Large worlds | The first save is a full copy (can take tens of seconds for multi-GB worlds), so show a progress toast. Later saves are incremental. |
| Disk space | `maxStorageMB` cap with pruning. Show the total storage used in the load menu. |
| Save-scumming loot | Intended, like Bethesda games. A config option could limit saves to beds or a craftable item if the pack wants harder survival. |

## Project structure and milestones

The mod is one jar with split client and server source sets. Its only hard dependency is Fabric API. Mod Menu and Cloth Config are optional.

**Classes**

| Package / class | Role |
| --- | --- |
| `QuicksaveMod` | Common entrypoint. Registers config and the death event. |
| `QuicksaveClient` | Client entrypoint. Registers keybinds, the tick handler and the pause-menu buttons. |
| `snapshot.SnapshotStore` | Content-addressed store: `save`, `restore`, `list`, `delete`, `prune` |
| `snapshot.WorldFlusher` | Server thread: save-off, `saveAll` with flush, save-on |
| `snapshot.SaveMeta` | Record for `meta.json` |
| `flow.ReloadController` | State machine for close → restore → reopen, with the pre-load backup and crash marker |
| `flow.AutosaveScheduler` | Timer plus the dimension-change, sleep and quit triggers |
| `ui.QuicksaveDeathScreen` | Countdown death screen |
| `ui.LoadMenuScreen` | Thumbnail save list |
| `ui.SaveToast` | "Quicksaving…" / "Saving refused: falling" toasts |
| `mixin.MinecraftClientMixin` | Replaces `DeathScreen` in `setScreen` |
| `mixin.GameMenuScreenMixin` | Adds the Save and Load buttons to the pause menu |

**Milestones**

1. **M1 — Proof of loop.** Dev command `/qs save` copies the whole world folder. The death screen offers "Load", which closes the world, copies the folder back and reopens it. No incremental storage yet.
2. **M2 — Real UX.** F6/F9 keys, the countdown death screen, toasts, pause-menu buttons, and the pre-load backup with its crash marker.
3. **M3 — Storage.** Content-addressed objects, manifests, rotating quicksave and autosave slots, pruning.
4. **M4 — Load menu.** Thumbnails, metadata, rename and delete, plus the config screen.
5. **M5 — Pack hardening.** Run it inside the Skyrim-tone modpack, write the known-issues list, add the save-blocking rules, and reskin the screens.
6. **v2 stretch — Hot restore.** Rewind without a load screen by tracking dirty chunks and reloading them in place.

**Decisions**

- Death cam: yes. It plays before the countdown and can be configured (`deathCamSeconds`, `deathCamStyle`). It's built in M2.
- Saving in combat: configurable. By default it's blocked while a hostile mob within 16 blocks is targeting you, or for 5 s after you take or deal damage. Setting `allowSaveInCombat: true` lets you save anywhere.
