package com.muto118.quicksave.selftest;

import com.muto118.quicksave.QuicksaveMod;
import com.muto118.quicksave.config.QuicksaveConfig;
import com.muto118.quicksave.flow.AutosaveScheduler;
import com.muto118.quicksave.flow.RecoveryPrompt;
import com.muto118.quicksave.flow.ReloadController;
import com.muto118.quicksave.flow.SaveRequests;
import com.muto118.quicksave.guard.SaveGuard;
import com.muto118.quicksave.snapshot.SaveService;
import com.muto118.quicksave.snapshot.SlotType;
import com.muto118.quicksave.snapshot.SnapshotStore;
import com.muto118.quicksave.ui.QuicksaveDeathScreen;
import com.muto118.quicksave.ui.SaveNameScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BedPart;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.NoticeScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.minecraft.world.gen.WorldPresets;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Drives Quicksave end to end inside a real client, one step per poll:
 *
 * <ol>
 * <li>create a flat survival world, place a gold block, spawn a named pig</li>
 * <li>save guards: falling, in lava, combat are each refused with a toast</li>
 * <li>F6 (through the real key binding) makes a quicksave</li>
 * <li>pause menu has Save/Load above Save and Quit; Save with a name makes a manual save</li>
 * <li>remove gold, place diamond block, give diamonds, kill pig, kill player</li>
 * <li>death cam runs (UI hidden), then the countdown loads the last save by itself</li>
 * <li>world, inventory and pig are rewound; _pre_load exists</li>
 * <li>F9 + confirm loads the quicksave again</li>
 * <li>die, Quit to Title, rejoin: the Quicksave death screen is back; Load</li>
 * <li>fake a crash mid-restore; the title-screen prompt rolls the world back</li>
 * </ol>
 * Writes PASS/FAIL to run/selftest/selftest-result.txt and stops the client.
 */
public class SelfTestClient implements ClientModInitializer {
	private static final String WORLD = "QSTest";
	private static final String PIG_NAME = "QuicksavePig";
	private static final String MANUAL_NAME = "Before the fight";
	private static final int STEP_TIMEOUT_TICKS = 20 * 60;

	/** Polled every client tick until it returns true. Throw to fail. */
	private interface StepBody {
		boolean poll(MinecraftClient client, int ticks) throws Exception;
	}

	private record Step(String name, StepBody body) {
	}

	private final List<Step> steps = new ArrayList<>();
	private int index;
	private int stepTicks;
	private boolean done;

	private BlockPos goldPos;
	private BlockPos diamondPos;
	private BlockPos groundPos;
	private SaveService.Result resultBefore;
	private long deathScreenSeenAt = -1L;
	private boolean sawCamPhase;
	private boolean countdownShot;
	private BlockPos bedPos;
	private Set<String> slotsBefore = Set.of();
	private boolean earlyCamShot;

	@Override
	public void onInitializeClient() {
		this.buildSteps();
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void buildSteps() {
		step("open create-world screen", (c, t) -> {
			if (c.currentScreen instanceof TitleScreen && t > 20) {
				CreateWorldScreen.create(c, c.currentScreen);
				return true;
			}
			return false;
		});
		step("create flat world", (c, t) -> {
			if (c.currentScreen instanceof CreateWorldScreen screen && t > 10) {
				WorldCreator creator = screen.getWorldCreator();
				creator.setWorldName(WORLD);
				creator.setGameMode(WorldCreator.Mode.SURVIVAL);
				creator.getNormalWorldTypes().stream()
						.filter(type -> type.preset() != null && type.preset().matchesKey(WorldPresets.FLAT))
						.findFirst().ifPresent(creator::setWorldType);
				press(screen.children(), "selectWorld.create");
				return true;
			}
			return false;
		});
		step("join world", (c, t) -> inWorld(c) && t > 20);
		step("Start autosave on first join", (c, t) -> {
			Optional<SnapshotStore.Slot> start = SnapshotStore.latest(worldName(c), List.of(SlotType.AUTO));
			if (start.isPresent()) {
				if (!"Start".equals(start.get().meta().name())) {
					throw new IllegalStateException("first autosave isn't the Start save: " + start.get());
				}
				return true;
			}
			return false;
		});
		step("set up gold block and pig", (c, t) -> {
			onServer(c, player -> {
				ServerWorld world = player.getServerWorld();
				BlockPos base = player.getBlockPos();
				this.groundPos = base;
				this.goldPos = base.add(3, 0, 0);
				this.diamondPos = base.add(-3, 0, 0);
				world.setBlockState(this.goldPos, Blocks.GOLD_BLOCK.getDefaultState());
				PigEntity pig = EntityType.PIG.create(world);
				pig.refreshPositionAndAngles(base.add(0, 0, 3), 0, 0);
				pig.setCustomName(Text.literal(PIG_NAME));
				pig.setAiDisabled(true);
				world.spawnEntity(pig);
				// Creative for the guard checks so lava and falling don't hurt (damage would start the combat cooldown).
				player.changeGameMode(GameMode.CREATIVE);
				return null;
			});
			return true;
		});

		// --- Save guards ---
		step("guard: falling", (c, t) -> {
			if (t == 1) {
				onServer(c, player -> {
					player.requestTeleport(this.groundPos.getX() + 0.5, this.groundPos.getY() + 40, this.groundPos.getZ() + 0.5);
					return null;
				});
			}
			if (t == 12) {
				requestSave(c, SlotType.QUICK, null);
			}
			return t > 12 && expectRefusal(SaveGuard.Refusal.FALLING);
		});
		step("land", (c, t) -> {
			if (t == 1) {
				onServer(c, player -> {
					player.requestTeleport(this.groundPos.getX() + 0.5, this.groundPos.getY(), this.groundPos.getZ() + 0.5);
					player.fallDistance = 0;
					player.setVelocity(Vec3d.ZERO);
					return null;
				});
			}
			return t > 30;
		});
		step("guard: in lava", (c, t) -> {
			if (t == 1) {
				onServer(c, player -> {
					player.getServerWorld().setBlockState(player.getBlockPos(), Blocks.LAVA.getDefaultState());
					return null;
				});
			}
			if (t == 10) {
				requestSave(c, SlotType.QUICK, null);
			}
			if (t > 10 && expectRefusal(SaveGuard.Refusal.IN_LAVA)) {
				onServer(c, player -> {
					player.getServerWorld().setBlockState(player.getBlockPos(), Blocks.AIR.getDefaultState());
					player.extinguish();
					return null;
				});
				return true;
			}
			return false;
		});
		step("guard: combat", (c, t) -> {
			if (t == 10) {
				onServer(c, player -> {
					ZombieEntity zombie = EntityType.ZOMBIE.create(player.getServerWorld());
					zombie.refreshPositionAndAngles(player.getBlockPos().add(5, 0, 5), 0, 0);
					zombie.setAiDisabled(true);
					zombie.setTarget(player);
					zombie.addCommandTag("quicksave_selftest");
					player.getServerWorld().spawnEntity(zombie);
					return null;
				});
				requestSave(c, SlotType.QUICK, null);
			}
			if (t > 10 && expectRefusal(SaveGuard.Refusal.COMBAT)) {
				onServer(c, player -> {
					player.getServerWorld().getEntitiesByClass(ZombieEntity.class, player.getBoundingBox().expand(32),
							z -> z.getCommandTags().contains("quicksave_selftest")).forEach(z -> z.discard());
					player.changeGameMode(GameMode.SURVIVAL);
					return null;
				});
				return true;
			}
			return false;
		});

		// --- Saving ---
		step("F6 quicksave", (c, t) -> {
			if (t == 20) {
				this.resultBefore = SaveRequests.lastResult();
				pressKey(GLFW.GLFW_KEY_F6);
			}
			SaveService.Result result = SaveRequests.lastResult();
			if (t > 20 && result != this.resultBefore && result != null) {
				if (!result.success()) {
					throw new IllegalStateException("F6 save failed: " + result);
				}
				SnapshotStore.Slot slot = SnapshotStore.latest(worldName(c), List.of(SlotType.QUICK)).orElseThrow();
				log("quicksave slot " + slot.id() + " meta " + slot.meta());
				return true;
			}
			return false;
		});
		step("F6 three more times: rotation keeps 3 quicksaves", (c, t) -> {
			// t 20/60/100: change the world a little, press F6; each save must finish before the next.
			if (t == 20 || t == 60 || t == 100) {
				int n = t / 40;
				onServer(c, player -> {
					player.getServerWorld().setBlockState(this.groundPos.add(-5, 0, n), Blocks.STONE.getDefaultState());
					return null;
				});
			}
			if (t == 21 || t == 61 || t == 101) {
				this.resultBefore = SaveRequests.lastResult();
				pressKey(GLFW.GLFW_KEY_F6);
			}
			if (t == 59 || t == 99 || t == 139) {
				SaveService.Result result = SaveRequests.lastResult();
				if (result == this.resultBefore || result == null || !result.success()) {
					throw new IllegalStateException("F6 save did not finish: " + result);
				}
				log("quicksave " + result.slot() + ": " + result.outcome().stats() + " in " + result.millis()
						+ " ms, rotated out " + result.outcome().rotatedOut());
				if (result.outcome().stats().hashed() >= result.outcome().stats().files()) {
					throw new IllegalStateException("incremental save re-read every file: " + result.outcome().stats());
				}
			}
			if (t == 140) {
				long quick = countSlots(c, SlotType.QUICK);
				if (quick != 3) {
					throw new IllegalStateException("expected 3 rotating quicksaves, found " + quick);
				}
				return true;
			}
			return false;
		});
		step("timer autosave", (c, t) -> {
			if (t == 1) {
				this.slotsBefore = slotIds(c);
				AutosaveScheduler.testIntervalTicks = 60;
			}
			SnapshotStore.Slot made = newSlot(c);
			if (made != null) {
				AutosaveScheduler.testIntervalTicks = 0;
				expectAuto(made, "Autosave", "minecraft:overworld");
				return true;
			}
			return false;
		});
		step("dimension change autosave (Nether)", (c, t) -> {
			if (t == 1) {
				this.slotsBefore = slotIds(c);
				onServer(c, player -> {
					player.changeGameMode(GameMode.CREATIVE);
					ServerWorld nether = player.getServer().getWorld(World.NETHER);
					BlockPos pad = new BlockPos(0, 70, 0);
					for (int dx = -1; dx <= 1; dx++) {
						for (int dz = -1; dz <= 1; dz++) {
							nether.setBlockState(pad.add(dx, -1, dz), Blocks.OBSIDIAN.getDefaultState());
							for (int dy = 0; dy < 3; dy++) {
								nether.setBlockState(pad.add(dx, dy, dz), Blocks.AIR.getDefaultState());
							}
						}
					}
					player.teleport(nether, 0.5, 70, 0.5, Set.of(), 0, 0);
					return null;
				});
			}
			SnapshotStore.Slot made = newSlot(c);
			if (made != null) {
				expectAuto(made, "Autosave (new dimension)", "minecraft:the_nether");
				return true;
			}
			return false;
		});
		step("dimension change autosave (back home)", (c, t) -> {
			if (t == 1) {
				this.slotsBefore = slotIds(c);
				onServer(c, player -> {
					player.teleport(player.getServer().getOverworld(), this.groundPos.getX() + 0.5, this.groundPos.getY(),
							this.groundPos.getZ() + 0.5, Set.of(), 0, 0);
					return null;
				});
			}
			SnapshotStore.Slot made = newSlot(c);
			if (made != null) {
				expectAuto(made, "Autosave (new dimension)", "minecraft:overworld");
				long autos = countSlots(c, SlotType.AUTO);
				if (autos != 3) {
					throw new IllegalStateException("expected 3 rotating autosaves, found " + autos);
				}
				return true;
			}
			return false;
		});
		step("sleep autosave", (c, t) -> {
			if (t == 1) {
				onServer(c, player -> {
					player.changeGameMode(GameMode.SURVIVAL);
					player.getServer().setDifficulty(Difficulty.PEACEFUL, true);
					ServerWorld world = player.getServerWorld();
					world.setTimeOfDay(14000);
					this.bedPos = this.groundPos.add(0, 0, -3);
					world.setBlockState(this.bedPos, Blocks.RED_BED.getDefaultState()
							.with(BedBlock.FACING, Direction.NORTH).with(BedBlock.PART, BedPart.FOOT));
					world.setBlockState(this.bedPos.north(), Blocks.RED_BED.getDefaultState()
							.with(BedBlock.FACING, Direction.NORTH).with(BedBlock.PART, BedPart.HEAD));
					player.requestTeleport(this.bedPos.getX() + 0.5, this.bedPos.getY(), this.bedPos.getZ() + 1.5);
					return null;
				});
			}
			if (t == 40) {
				this.slotsBefore = slotIds(c);
				String failure = onServer(c, player -> player.trySleep(this.bedPos).left().map(Object::toString).orElse(null));
				if (failure != null) {
					throw new IllegalStateException("could not sleep: " + failure);
				}
			}
			SnapshotStore.Slot made = t > 40 ? newSlot(c) : null;
			if (made != null) {
				expectAuto(made, "Autosave (slept)", "minecraft:overworld");
				onServer(c, player -> {
					player.getServer().setDifficulty(Difficulty.NORMAL, true);
					player.requestTeleport(this.groundPos.getX() + 0.5, this.groundPos.getY(), this.groundPos.getZ() + 0.5);
					return null;
				});
				return true;
			}
			return false;
		});
		step("pause menu Save button", (c, t) -> {
			if (t == 1) {
				c.setScreen(new GameMenuScreen(true));
			}
			if (t == 10) {
				GameMenuScreen menu = (GameMenuScreen) c.currentScreen;
				ClickableWidget save = find(menu.children(), "quicksave.menu.save");
				ClickableWidget load = find(menu.children(), "quicksave.menu.load");
				ClickableWidget exit = find(menu.children(), "menu.returnToMenu");
				if (!(save.getY() < exit.getY() && load.getY() == save.getY() && load.active)) {
					throw new IllegalStateException("pause buttons misplaced: save y=" + save.getY() + " load y="
							+ load.getY() + " exit y=" + exit.getY() + " load active=" + load.active);
				}
				screenshot(c);
				((ButtonWidget) save).onPress();
			}
			if (t == 15) {
				SaveNameScreen nameScreen = (SaveNameScreen) c.currentScreen;
				nameScreen.setName(MANUAL_NAME);
				this.resultBefore = SaveRequests.lastResult();
				nameScreen.save();
			}
			SaveService.Result result = SaveRequests.lastResult();
			if (t > 15 && result != this.resultBefore && result != null) {
				if (!result.success()) {
					throw new IllegalStateException("manual save failed: " + result);
				}
				SnapshotStore.Slot latest = SnapshotStore.latest(worldName(c), null).orElseThrow();
				if (latest.meta().type() != SlotType.MANUAL || !MANUAL_NAME.equals(latest.meta().name())) {
					throw new IllegalStateException("latest save is not the named manual save: " + latest);
				}
				return true;
			}
			return false;
		});

		step("storage cap prunes autosaves, then old quicksaves", (c, t) -> {
			if (t == 20) {
				log("store size before pruning: " + (SnapshotStore.store(worldName(c)).totalBytes() >> 10) + " KB");
				QuicksaveConfig.get().maxStorageMB = 1;
				this.resultBefore = SaveRequests.lastResult();
				pressKey(GLFW.GLFW_KEY_F6);
			}
			SaveService.Result result = SaveRequests.lastResult();
			if (t > 20 && result != this.resultBefore && result != null) {
				QuicksaveConfig.get().maxStorageMB = 4096;
				if (!result.success()) {
					throw new IllegalStateException("save failed: " + result);
				}
				log("pruned " + result.outcome().pruned() + ", store now "
						+ (SnapshotStore.store(worldName(c)).totalBytes() >> 10) + " KB");
				long autos = countSlots(c, SlotType.AUTO);
				long quicks = countSlots(c, SlotType.QUICK);
				long manuals = countSlots(c, SlotType.MANUAL);
				if (autos != 0 || quicks != 1 || manuals != 1) {
					throw new IllegalStateException("after pruning: " + autos + " auto, " + quicks + " quick, " + manuals
							+ " manual; expected 0/1/1");
				}
				return true;
			}
			return false;
		});
		step("object store is consistent", (c, t) -> {
			assertConsistent(c);
			return true;
		});

		// --- Die, death cam, countdown loads by itself ---
		step("change the world and die", (c, t) -> {
			if (t < 20) {
				return false;
			}
			mutateAndKill(c);
			return true;
		});
		step("death cam, then countdown auto-load", (c, t) -> {
			if (c.currentScreen instanceof DeathScreen) {
				throw new IllegalStateException("vanilla DeathScreen shown although a save exists");
			}
			if (c.currentScreen instanceof QuicksaveDeathScreen screen) {
				if (this.deathScreenSeenAt < 0) {
					this.deathScreenSeenAt = Util.getMeasuringTimeMs();
				}
				if (screen.inCamPhase()) {
					if (!this.earlyCamShot && Util.getMeasuringTimeMs() - this.deathScreenSeenAt > 400) {
						screenshot(c);
						this.earlyCamShot = true;
					}
					if (!this.sawCamPhase && Util.getMeasuringTimeMs() - this.deathScreenSeenAt > 1000) {
						ClickableWidget loadButton = find(screen.children(), "quicksave.death.load");
						if (loadButton.visible) {
							throw new IllegalStateException("buttons visible during the death cam");
						}
						screenshot(c);
						this.sawCamPhase = true;
					}
				} else if (Util.getMeasuringTimeMs() - this.deathScreenSeenAt > 3500 && !this.countdownShot) {
					screenshot(c);
					this.countdownShot = true;
				}
				return false;
			}
			if (this.deathScreenSeenAt >= 0 && !(c.currentScreen instanceof QuicksaveDeathScreen)) {
				long took = Util.getMeasuringTimeMs() - this.deathScreenSeenAt;
				log("death screen closed itself after " + took + " ms");
				if (!this.sawCamPhase) {
					throw new IllegalStateException("never saw the death cam phase");
				}
				if (took < 4000 || took > 9000) {
					throw new IllegalStateException("countdown auto-load took " + took + " ms, expected ~5000");
				}
				return true;
			}
			return false;
		});
		step("world reopens after auto-load", (c, t) -> inWorld(c) && waitInWorld(t));
		step("verify rewind (auto-load)", (c, t) -> {
			verifyRewound(c);
			screenshot(c);
			return true;
		});

		// --- F9 quickload ---
		step("F9 quickload with confirm", (c, t) -> {
			if (t == 20) {
				onServer(c, player -> {
					player.getServerWorld().setBlockState(this.diamondPos, Blocks.DIAMOND_BLOCK.getDefaultState());
					return null;
				});
				pressKey(GLFW.GLFW_KEY_F9);
			}
			if (t > 20 && c.currentScreen instanceof ConfirmScreen confirm) {
				press(confirm.children(), "gui.yes");
				return true;
			}
			return false;
		});
		step("world reopens after quickload", (c, t) -> inWorld(c) && waitInWorld(t));
		step("verify rewind (quickload)", (c, t) -> {
			verifyRewound(c);
			return true;
		});

		// --- Quit while dead, rejoin ---
		step("die again", (c, t) -> {
			if (t < 20) {
				return false;
			}
			onServer(c, player -> {
				player.kill();
				return null;
			});
			return true;
		});
		step("skip death cam, Quit to Title", (c, t) -> {
			if (c.currentScreen instanceof QuicksaveDeathScreen screen) {
				if (screen.inCamPhase()) {
					screen.mouseClicked(0, 0, 0); // a click skips the death cam
				} else if (t > 30) {
					press(screen.children(), "quicksave.death.quit");
					return true;
				}
			}
			return false;
		});
		step("rejoin while dead", (c, t) -> {
			if (c.currentScreen instanceof TitleScreen && c.world == null && t > 20) {
				deleteRecursively(SnapshotStore.preLoadDir(WORLD)); // must be recreated by this load
				c.createIntegratedServerLoader().start(WORLD, () -> c.setScreen(new TitleScreen()));
				return true;
			}
			return false;
		});
		step("Quicksave death screen is back; Load", (c, t) -> {
			if (c.currentScreen instanceof DeathScreen) {
				throw new IllegalStateException("rejoining while dead showed the vanilla DeathScreen");
			}
			if (c.player != null && c.currentScreen == null && t > 300) {
				throw new IllegalStateException("rejoining while dead did not show a death screen");
			}
			if (c.currentScreen instanceof QuicksaveDeathScreen screen) {
				if (screen.inCamPhase()) {
					screen.mouseClicked(0, 0, 0);
				} else if (find(screen.children(), "quicksave.death.load").active) {
					press(screen.children(), "quicksave.death.load");
					return true;
				}
			}
			return false;
		});
		step("world reopens after rejoin load", (c, t) -> inWorld(c) && waitInWorld(t));
		step("verify rewind (rejoin)", (c, t) -> {
			verifyRewound(c);
			return true;
		});

		// --- Crash marker recovery ---
		step("Save and Quit to Title autosaves", (c, t) -> {
			if (t == 20) {
				this.slotsBefore = slotIds(c);
				c.setScreen(new GameMenuScreen(true));
			}
			if (t == 25) {
				press(c.currentScreen.children(), "menu.returnToMenu");
			}
			if (t > 25 && c.currentScreen instanceof TitleScreen && c.world == null) {
				List<SnapshotStore.Slot> made = SnapshotStore.list(WORLD).stream()
						.filter(slot -> !this.slotsBefore.contains(slot.id())).toList();
				if (made.size() != 1) {
					throw new IllegalStateException("expected one quit autosave, got " + made);
				}
				expectAuto(made.get(0), "Autosave (quit)", "minecraft:overworld");
				return true;
			}
			return false;
		});
		step("fake a crash mid-restore, roll back", (c, t) -> {
			if (t == 20) {
				Path worldDir = c.getLevelStorage().getSavesDirectory().resolve(WORLD);
				SnapshotStore.store(WORLD).capturePreLoad(worldDir);
				Files.writeString(SnapshotStore.restoreMarker(WORLD), "world=" + WORLD + "\nslot=quick\n", StandardCharsets.UTF_8);
				// A half-finished restore: junk in the world and level.dat gone.
				Files.writeString(worldDir.resolve("junk.txt"), "half restored", StandardCharsets.UTF_8);
				Files.delete(worldDir.resolve("level.dat"));
				if (!RecoveryPrompt.showIfNeeded(c, c.currentScreen)) {
					throw new IllegalStateException("recovery prompt did not show for a restore.inprogress marker");
				}
			}
			if (t == 30) {
				screenshot(c);
				press(((ConfirmScreen) c.currentScreen).children(), "quicksave.recovery.rollback");
			}
			if (t > 30 && c.currentScreen instanceof NoticeScreen notice) {
				Path worldDir = c.getLevelStorage().getSavesDirectory().resolve(WORLD);
				List<String> f = new ArrayList<>();
				if (Files.exists(worldDir.resolve("junk.txt"))) {
					f.add("junk.txt survived the rollback");
				}
				if (!Files.isRegularFile(worldDir.resolve("level.dat"))) {
					f.add("level.dat not restored by the rollback");
				}
				if (Files.exists(SnapshotStore.restoreMarker(WORLD))) {
					f.add("marker left after rollback");
				}
				if (!f.isEmpty()) {
					throw new IllegalStateException(String.join("; ", f));
				}
				press(notice.children(), "gui.ok");
				return true;
			}
			return false;
		});
		step("object store is consistent at the end", (c, t) -> {
			List<String> problems = SnapshotStore.store(WORLD).checkConsistency();
			if (!problems.isEmpty()) {
				throw new IllegalStateException(String.join("; ", problems));
			}
			return true;
		});
	}

	private void step(String name, StepBody body) {
		this.steps.add(new Step(name, body));
	}

	private void tick(MinecraftClient client) {
		if (this.done) {
			return;
		}
		if (this.index >= this.steps.size()) {
			finish(client, true, "all " + this.steps.size() + " steps passed");
			return;
		}
		Step step = this.steps.get(this.index);
		this.stepTicks++;
		if (this.stepTicks > STEP_TIMEOUT_TICKS) {
			finish(client, false, "timed out in step '" + step.name() + "' (screen=" + client.currentScreen + ")");
			return;
		}
		try {
			if (step.body().poll(client, this.stepTicks)) {
				log("ok: " + step.name());
				this.index++;
				this.stepTicks = 0;
			}
		} catch (Throwable e) {
			QuicksaveMod.LOGGER.error("[selftest] step '{}' failed", step.name(), e);
			finish(client, false, "step '" + step.name() + "': " + e.getMessage());
		}
	}

	// --- helpers ---

	private static boolean inWorld(MinecraftClient c) {
		return c.player != null && c.world != null && c.currentScreen == null && c.getServer() != null;
	}

	private static boolean waitInWorld(int ticks) {
		return ticks > 100;
	}

	private static String worldName(MinecraftClient c) {
		return QuicksaveMod.worldFolderName(c.getServer());
	}

	private static <T> T onServer(MinecraftClient c, Function<ServerPlayerEntity, T> action) throws Exception {
		IntegratedServer server = c.getServer();
		Callable<T> task = () -> action.apply(server.getPlayerManager().getPlayerList().get(0));
		return server.submit(() -> {
			try {
				return task.call();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}).get(30, TimeUnit.SECONDS);
	}

	private void requestSave(MinecraftClient c, SlotType type, String name) {
		this.resultBefore = SaveRequests.lastResult();
		SaveRequests.request(c, type, name);
	}

	/** True once a new result arrived; throws if it isn't the expected refusal. */
	private boolean expectRefusal(SaveGuard.Refusal expected) {
		SaveService.Result result = SaveRequests.lastResult();
		if (result == null || result == this.resultBefore) {
			return false;
		}
		if (result.refusal() != expected) {
			throw new IllegalStateException("expected refusal " + expected + ", got " + result);
		}
		return true;
	}

	private void mutateAndKill(MinecraftClient c) throws Exception {
		onServer(c, player -> {
			ServerWorld world = player.getServerWorld();
			world.setBlockState(this.goldPos, Blocks.AIR.getDefaultState());
			world.setBlockState(this.diamondPos, Blocks.DIAMOND_BLOCK.getDefaultState());
			player.getInventory().insertStack(new ItemStack(Items.DIAMOND, 64));
			world.getEntitiesByClass(PigEntity.class, new Box(this.goldPos).expand(16), this::isTestPig).forEach(PigEntity::kill);
			player.kill();
			return null;
		});
	}

	private boolean isTestPig(PigEntity pig) {
		return pig.getCustomName() != null && PIG_NAME.equals(pig.getCustomName().getString());
	}

	private void verifyRewound(MinecraftClient c) throws Exception {
		List<String> failures = onServer(c, player -> {
			List<String> f = new ArrayList<>();
			ServerWorld world = player.getServerWorld();
			if (!world.getBlockState(this.goldPos).isOf(Blocks.GOLD_BLOCK)) {
				f.add("gold block not restored (" + world.getBlockState(this.goldPos) + ")");
			}
			if (world.getBlockState(this.diamondPos).isOf(Blocks.DIAMOND_BLOCK)) {
				f.add("diamond block placed after the save still exists");
			}
			if (player.getInventory().count(Items.DIAMOND) != 0) {
				f.add("player still has " + player.getInventory().count(Items.DIAMOND) + " diamonds");
			}
			if (player.isDead() || player.getHealth() <= 0) {
				f.add("player is dead after reload (health " + player.getHealth() + ")");
			}
			int pigs = world.getEntitiesByClass(PigEntity.class, new Box(this.goldPos).expand(16), this::isTestPig).size();
			if (pigs != 1) {
				f.add("expected the pig back once, found " + pigs);
			}
			return f;
		});
		String world = worldName(c);
		if (!Files.isRegularFile(SnapshotStore.preLoadDir(world).resolve("manifest.json"))) {
			failures.add("_pre_load/manifest.json missing");
		}
		if (SnapshotStore.list(world).stream().anyMatch(slot -> "Autosave (quit)".equals(slot.meta().name()))) {
			failures.add("closing the world for a load made an 'on quit' autosave");
		}
		if (Files.exists(SnapshotStore.restoreMarker(world))) {
			failures.add("restore.inprogress marker left behind");
		}
		if (ReloadController.state() != ReloadController.State.IDLE) {
			failures.add("ReloadController stuck in " + ReloadController.state());
		}
		if (!failures.isEmpty()) {
			throw new IllegalStateException(String.join("; ", failures));
		}
	}

	private static Set<String> slotIds(MinecraftClient c) {
		return SnapshotStore.list(worldName(c)).stream().map(SnapshotStore.Slot::id).collect(Collectors.toSet());
	}

	/** The newest slot that wasn't there when {@link #slotsBefore} was taken, or null. */
	private SnapshotStore.Slot newSlot(MinecraftClient c) {
		return SnapshotStore.list(worldName(c)).stream()
				.filter(slot -> !this.slotsBefore.contains(slot.id()))
				.findFirst().orElse(null);
	}

	private static long countSlots(MinecraftClient c, SlotType type) {
		return SnapshotStore.list(worldName(c)).stream().filter(slot -> slot.meta().type() == type).count();
	}

	private static void expectAuto(SnapshotStore.Slot slot, String name, String dimension) {
		log("autosave " + slot.id() + ": " + slot.meta().name() + " in " + slot.meta().dimension());
		if (slot.meta().type() != SlotType.AUTO || !name.equals(slot.meta().name())
				|| !dimension.equals(slot.meta().dimension())) {
			throw new IllegalStateException("expected autosave '" + name + "' in " + dimension + ", got " + slot.meta());
		}
	}

	private static void assertConsistent(MinecraftClient c) throws Exception {
		List<String> problems = SnapshotStore.store(worldName(c)).checkConsistency();
		if (!problems.isEmpty()) {
			throw new IllegalStateException(String.join("; ", problems));
		}
	}

	private static void pressKey(int glfwKey) {
		KeyBinding.onKeyPressed(InputUtil.Type.KEYSYM.createFromCode(glfwKey));
	}

	private static ClickableWidget find(List<? extends Element> children, String translationKey) {
		Optional<ClickableWidget> found = children.stream()
				.filter(e -> e instanceof ClickableWidget w
						&& w.getMessage().getContent() instanceof TranslatableTextContent t
						&& t.getKey().equals(translationKey))
				.map(e -> (ClickableWidget) e)
				.findFirst();
		return found.orElseThrow(() -> new IllegalStateException("no widget " + translationKey));
	}

	private static void press(List<? extends Element> children, String translationKey) {
		ClickableWidget widget = find(children, translationKey);
		if (!widget.active || !widget.visible) {
			throw new IllegalStateException("button " + translationKey + " is not clickable");
		}
		((ButtonWidget) widget).onPress();
	}

	private static void copyTree(Path from, Path to) throws java.io.IOException {
		try (var paths = Files.walk(from)) {
			for (Path p : paths.toList()) {
				if (p.getFileName().toString().equals(SnapshotStore.SESSION_LOCK)) {
					continue;
				}
				Path target = to.resolve(from.relativize(p).toString());
				if (Files.isDirectory(p)) {
					Files.createDirectories(target);
				} else {
					Files.copy(p, target);
				}
			}
		}
	}

	private static void deleteRecursively(Path dir) throws java.io.IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (var paths = Files.walk(dir)) {
			for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(p);
			}
		}
	}

	private static void screenshot(MinecraftClient client) {
		ScreenshotRecorder.saveScreenshot(client.runDirectory, client.getFramebuffer(), text -> log(text.getString()));
	}

	private void finish(MinecraftClient client, boolean pass, String detail) {
		this.done = true;
		String line = (pass ? "PASS: " : "FAIL: ") + detail;
		log(line);
		try {
			Files.writeString(client.runDirectory.toPath().resolve("selftest-result.txt"), line + "\n", StandardCharsets.UTF_8);
		} catch (Exception e) {
			QuicksaveMod.LOGGER.error("[selftest] could not write result", e);
		}
		client.scheduleStop();
	}

	private static void log(String message) {
		QuicksaveMod.LOGGER.info("[selftest] {}", message);
	}
}
