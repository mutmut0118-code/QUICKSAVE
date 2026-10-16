package com.muto118.quicksave.selftest;

import com.muto118.quicksave.QuicksaveMod;
import com.muto118.quicksave.flow.ReloadController;
import com.muto118.quicksave.snapshot.SnapshotStore;
import com.muto118.quicksave.ui.QuicksaveDeathScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.gen.WorldPresets;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Drives the M1 loop end to end inside a real client:
 * create world -> place gold + spawn named pig -> /qs save -> remove gold,
 * place diamond block, give diamonds, kill pig -> kill player ->
 * QuicksaveDeathScreen -> press "Load Last Save" -> world reopens -> verify;
 * then die again -> "Quit to Title" -> rejoin -> the swapped screen is back -> Load -> verify.
 *
 * <p>Writes PASS/FAIL to run/selftest/selftest-result.txt and stops the client.
 */
public class SelfTestClient implements ClientModInitializer {
	private static final String WORLD = "QSTest";
	private static final String PIG_NAME = "QuicksavePig";
	private static final int TIMEOUT_TICKS = 20 * 60 * 5;

	private enum Step {
		TITLE, CREATE, JOIN, SAVE_AND_MUTATE, DEATH_SCREEN, RELOAD, VERIFY,
		// Phase 2: die, quit to title while dead, rejoin -> the swapped death screen must come back.
		KILL_AGAIN, DEATH_QUIT, REJOIN, REJOIN_DEATH_SCREEN, RELOAD_2, VERIFY_2,
		DONE
	}

	private Step step = Step.TITLE;
	private int totalTicks;
	private int stepTicks;
	private BlockPos goldPos;
	private BlockPos diamondPos;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void advance(Step next) {
		log("step " + this.step + " -> " + next);
		this.step = next;
		this.stepTicks = 0;
	}

	private void tick(MinecraftClient client) {
		if (this.step == Step.DONE) {
			return;
		}
		this.totalTicks++;
		this.stepTicks++;
		if (this.totalTicks > TIMEOUT_TICKS) {
			finish(client, false, "timed out in step " + this.step + " (screen=" + client.currentScreen + ")");
			return;
		}
		try {
			switch (this.step) {
				case TITLE -> {
					if (client.currentScreen instanceof TitleScreen && this.stepTicks > 20) {
						CreateWorldScreen.create(client, client.currentScreen);
						advance(Step.CREATE);
					}
				}
				case CREATE -> {
					if (client.currentScreen instanceof CreateWorldScreen screen && this.stepTicks > 10) {
						WorldCreator creator = screen.getWorldCreator();
						creator.setWorldName(WORLD);
						creator.setGameMode(WorldCreator.Mode.SURVIVAL);
						creator.getNormalWorldTypes().stream()
								.filter(t -> t.preset() != null && t.preset().matchesKey(WorldPresets.FLAT))
								.findFirst().ifPresent(creator::setWorldType);
						pressButton(screen.children(), "selectWorld.create");
						advance(Step.JOIN);
					}
				}
				case JOIN -> {
					if (client.player != null && client.world != null && client.currentScreen == null) {
						if (this.stepTicks > 100) {
							advance(Step.SAVE_AND_MUTATE);
						}
					} else {
						this.stepTicks = 0;
					}
				}
				case SAVE_AND_MUTATE -> {
					saveAndMutate(client);
					advance(Step.DEATH_SCREEN);
				}
				case DEATH_SCREEN -> {
					if (client.currentScreen instanceof DeathScreen) {
						finish(client, false, "vanilla DeathScreen shown although a save exists");
						return;
					}
					if (client.currentScreen instanceof QuicksaveDeathScreen screen && this.stepTicks > 40) {
						screenshot(client);
						pressButton(screen.children(), "quicksave.death.load");
						advance(Step.RELOAD);
					}
				}
				case RELOAD -> {
					if (client.player != null && client.world != null && client.currentScreen == null
							&& client.getServer() != null) {
						if (this.stepTicks > 100) {
							advance(Step.VERIFY);
						}
					}
				}
				case VERIFY -> {
					List<String> failures = verify(client);
					if (!failures.isEmpty()) {
						finish(client, false, "phase 1: " + String.join("; ", failures));
						return;
					}
					screenshot(client);
					advance(Step.KILL_AGAIN);
				}
				case KILL_AGAIN -> {
					IntegratedServer server = client.getServer();
					server.submit(() -> server.getPlayerManager().getPlayerList().get(0).kill()).get(30, TimeUnit.SECONDS);
					advance(Step.DEATH_QUIT);
				}
				case DEATH_QUIT -> {
					if (client.currentScreen instanceof QuicksaveDeathScreen screen && this.stepTicks > 40) {
						pressButton(screen.children(), "quicksave.death.quit");
						advance(Step.REJOIN);
					}
				}
				case REJOIN -> {
					if (client.currentScreen instanceof TitleScreen && client.world == null && this.stepTicks > 20) {
						// Phase 2 must recreate the pre-load backup itself.
						deleteRecursively(SnapshotStore.preLoadDir(WORLD));
						client.createIntegratedServerLoader().start(WORLD, () -> client.setScreen(new TitleScreen()));
						advance(Step.REJOIN_DEATH_SCREEN);
					}
				}
				case REJOIN_DEATH_SCREEN -> {
					if (client.currentScreen instanceof DeathScreen) {
						finish(client, false, "rejoining while dead showed the vanilla DeathScreen");
						return;
					}
					if (client.player != null && client.currentScreen == null && this.stepTicks > 200) {
						finish(client, false, "rejoining while dead did not show a death screen");
						return;
					}
					if (client.currentScreen instanceof QuicksaveDeathScreen screen && this.stepTicks > 40) {
						screenshot(client);
						pressButton(screen.children(), "quicksave.death.load");
						advance(Step.RELOAD_2);
					}
				}
				case RELOAD_2 -> {
					if (client.player != null && client.world != null && client.currentScreen == null
							&& client.getServer() != null && this.stepTicks > 100) {
						advance(Step.VERIFY_2);
					}
				}
				case VERIFY_2 -> {
					List<String> failures = verify(client);
					screenshot(client);
					finish(client, failures.isEmpty(), failures.isEmpty()
							? "world, inventory and mobs rewound; _pre_load present; quit-while-dead rejoin reloads too"
							: "phase 2: " + String.join("; ", failures));
				}
				default -> {
				}
			}
		} catch (Throwable t) {
			QuicksaveMod.LOGGER.error("[selftest] crashed", t);
			finish(client, false, "exception in step " + this.step + ": " + t);
		}
	}

	private void saveAndMutate(MinecraftClient client) throws Exception {
		IntegratedServer server = client.getServer();
		CompletableFuture<Void> done = server.submit(() -> {
			ServerPlayerEntity player = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld world = player.getServerWorld();
			BlockPos base = player.getBlockPos();
			this.goldPos = base.add(3, 0, 0);
			this.diamondPos = base.add(-3, 0, 0);
			world.setBlockState(this.goldPos, Blocks.GOLD_BLOCK.getDefaultState());
			PigEntity pig = EntityType.PIG.create(world);
			pig.refreshPositionAndAngles(base.add(0, 0, 3), 0, 0);
			pig.setCustomName(Text.literal(PIG_NAME));
			pig.setAiDisabled(true);
			world.spawnEntity(pig);

			int result;
			try {
				result = server.getCommandManager().getDispatcher().execute("qs save", player.getCommandSource());
			} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
				throw new IllegalStateException(e);
			}
			if (result != 1 || !SnapshotStore.hasSave(QuicksaveMod.worldFolderName(server))) {
				throw new IllegalStateException("/qs save did not produce a save (result " + result + ")");
			}

			world.setBlockState(this.goldPos, Blocks.AIR.getDefaultState());
			world.setBlockState(this.diamondPos, Blocks.DIAMOND_BLOCK.getDefaultState());
			player.getInventory().insertStack(new ItemStack(Items.DIAMOND, 64));
			pig.kill();
			player.kill();
		});
		done.get(30, TimeUnit.SECONDS);
		log("saved, mutated world, killed player");
	}

	private List<String> verify(MinecraftClient client) throws Exception {
		IntegratedServer server = client.getServer();
		String worldName = QuicksaveMod.worldFolderName(server);
		List<String> failures = server.submit(() -> {
			List<String> f = new ArrayList<>();
			ServerPlayerEntity player = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld world = player.getServerWorld();
			if (!world.getBlockState(this.goldPos).isOf(Blocks.GOLD_BLOCK)) {
				f.add("gold block at " + this.goldPos + " not restored (" + world.getBlockState(this.goldPos) + ")");
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
			long pigs = world.getEntitiesByClass(PigEntity.class, new Box(this.goldPos).expand(16),
					p -> p.getCustomName() != null && PIG_NAME.equals(p.getCustomName().getString())).size();
			if (pigs != 1) {
				f.add("expected the killed pig to be back once, found " + pigs);
			}
			return f;
		}).get(30, TimeUnit.SECONDS);

		Path preLoad = SnapshotStore.preLoadDir(worldName);
		if (!Files.isRegularFile(preLoad.resolve("world").resolve("level.dat"))) {
			failures.add("_pre_load/world/level.dat missing");
		}
		if (Files.exists(SnapshotStore.restoreMarker(worldName))) {
			failures.add("restore.inprogress marker left behind");
		}
		if (ReloadController.state() != ReloadController.State.IDLE) {
			failures.add("ReloadController stuck in " + ReloadController.state());
		}
		if (client.currentScreen != null) {
			failures.add("unexpected screen after reload: " + client.currentScreen);
		}
		return failures;
	}

	private static void deleteRecursively(Path dir) throws java.io.IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (var paths = Files.walk(dir)) {
			for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.delete(p);
			}
		}
	}

	private static void pressButton(List<? extends Element> children, String translationKey) {
		for (Element element : children) {
			if (element instanceof ButtonWidget button
					&& button.getMessage().getContent() instanceof TranslatableTextContent t
					&& t.getKey().equals(translationKey)) {
				if (!button.active) {
					throw new IllegalStateException("button " + translationKey + " is inactive");
				}
				button.onPress();
				return;
			}
		}
		throw new IllegalStateException("no button " + translationKey);
	}

	private static void screenshot(MinecraftClient client) {
		ScreenshotRecorder.saveScreenshot(client.runDirectory, client.getFramebuffer(), text -> log(text.getString()));
	}

	private void finish(MinecraftClient client, boolean pass, String detail) {
		this.step = Step.DONE;
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
