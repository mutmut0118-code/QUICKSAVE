package com.muto118.quicksave.guard;

import com.muto118.quicksave.QuicksaveMod;
import com.muto118.quicksave.config.QuicksaveConfig;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether the player may save right now (server thread only).
 *
 * <p>Combat: a mob within {@code combatRadius} blocks whose target is the
 * player, or less than {@code combatCooldownSeconds} since the player took
 * damage or damaged another living entity. Situations: {@code blockSaveWhen}.
 */
public final class SaveGuard {
	/** Why a save was refused. Translation key: quicksave.refused.&lt;key&gt;. */
	public enum Refusal {
		INACTIVE("inactive"),
		HARDCORE("hardcore"),
		NO_PLAYER("no_player"),
		DEAD("dead"),
		IN_LAVA("in_lava"),
		FALLING("falling"),
		COMBAT("combat"),
		BUSY("busy");

		public final String key;

		Refusal(String key) {
			this.key = key;
		}
	}

	/** Server tick of the last damage taken or dealt, per player. */
	private static final Map<UUID, Integer> LAST_COMBAT_TICK = new ConcurrentHashMap<>();

	private SaveGuard() {
	}

	public static void register() {
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
			if (damageTaken <= 0.0F || entity.getServer() == null) {
				return;
			}
			int now = entity.getServer().getTicks();
			if (entity instanceof ServerPlayerEntity player) {
				LAST_COMBAT_TICK.put(player.getUuid(), now);
			}
			if (source.getAttacker() instanceof ServerPlayerEntity attacker && attacker != entity) {
				LAST_COMBAT_TICK.put(attacker.getUuid(), now);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> LAST_COMBAT_TICK.clear());
	}

	/** Null when saving is allowed. */
	@Nullable
	public static Refusal check(MinecraftServer server, @Nullable ServerPlayerEntity player) {
		QuicksaveConfig config = QuicksaveConfig.get();
		if (!QuicksaveMod.isActive(server)) {
			return Refusal.INACTIVE;
		}
		if (server.isHardcore() && !config.allowInHardcore) {
			return Refusal.HARDCORE;
		}
		if (player == null) {
			return Refusal.NO_PLAYER;
		}
		if (player.isDead() || player.getHealth() <= 0.0F) {
			return Refusal.DEAD;
		}
		if (config.blocks("in_lava") && player.isInLava()) {
			return Refusal.IN_LAVA;
		}
		if (config.blocks("falling") && isFalling(player)) {
			return Refusal.FALLING;
		}
		if (!config.allowSaveInCombat && inCombat(server, player, config)) {
			return Refusal.COMBAT;
		}
		return null;
	}

	/** fallDistance resets on landing; server-side velocity isn't updated for players, so it isn't used. */
	private static boolean isFalling(ServerPlayerEntity player) {
		return !player.isOnGround()
				&& player.fallDistance > 1.0F
				&& !player.getAbilities().flying
				&& !player.isFallFlying()
				&& !player.isTouchingWater()
				&& !player.isClimbing()
				&& !player.hasVehicle();
	}

	private static boolean inCombat(MinecraftServer server, ServerPlayerEntity player, QuicksaveConfig config) {
		Integer last = LAST_COMBAT_TICK.get(player.getUuid());
		if (last != null && server.getTicks() - last < config.combatCooldownSeconds * 20) {
			return true;
		}
		double radius = config.combatRadius;
		if (radius <= 0) {
			return false;
		}
		return !player.getServerWorld().getEntitiesByClass(MobEntity.class, player.getBoundingBox().expand(radius),
				mob -> mob.isAlive() && mob.getTarget() == player && mob.squaredDistanceTo(player) <= radius * radius)
				.isEmpty();
	}
}
