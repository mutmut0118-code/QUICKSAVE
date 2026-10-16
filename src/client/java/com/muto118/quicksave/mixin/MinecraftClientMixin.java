package com.muto118.quicksave.mixin;

import com.muto118.quicksave.QuicksaveClient;
import com.muto118.quicksave.snapshot.SnapshotStore;
import com.muto118.quicksave.ui.QuicksaveDeathScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Swaps the vanilla death screen for {@link QuicksaveDeathScreen} when the open
 * singleplayer world has a save. Also covers the case where vanilla builds the
 * death screen itself (setScreen(null) while dead, e.g. on rejoin).
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
	@Shadow
	@Nullable
	public ClientWorld world;

	@Shadow
	@Nullable
	public ClientPlayerEntity player;

	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen quicksave$replaceDeathScreen(@Nullable Screen screen) {
		boolean vanillaDeath = screen instanceof DeathScreen
				|| (screen == null && this.world != null && this.player != null
						&& this.player.isDead() && this.player.showsDeathScreen());
		if (!vanillaDeath) {
			return screen;
		}
		// Hardcore keeps vanilla behaviour until allowInHardcore exists (M2 config).
		if (this.world != null && this.world.getLevelProperties().isHardcore()) {
			return screen;
		}
		String worldName = QuicksaveClient.activeWorldFolderName((MinecraftClient) (Object) this);
		if (worldName == null || !SnapshotStore.hasSave(worldName)) {
			return screen;
		}
		return new QuicksaveDeathScreen(screen instanceof DeathScreen deathScreen
				? ((DeathScreenAccessor) deathScreen).quicksave$getMessage()
				: null);
	}
}
