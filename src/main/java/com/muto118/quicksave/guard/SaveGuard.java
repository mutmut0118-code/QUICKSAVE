package com.muto118.quicksave.guard;

import com.muto118.quicksave.QuicksaveMod;
import com.muto118.quicksave.config.QuicksaveConfig;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;
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

	/** How far down to look for a landing spot. Further than this counts as a deadly fall. */
	private static final int MAX_DROP_SCAN = 64;

	/**
	 * "Falling" means a fall that would hurt: the distance already fallen plus the drop
	 * to whatever is below is more than the safe fall distance (3 blocks by default).
	 * A (sprint) jump on flat ground is ~1.25 blocks, so it never counts.
	 *
	 * <p>Why not a grace period: a save made early in a long fall would load you
	 * back mid-air on your way to the same death, and dying loads that save again.
	 *
	 * <p>Server-side player velocity isn't updated from movement packets, so this
	 * looks at position, fallDistance and the blocks below instead.
	 */
	private static boolean isFalling(ServerPlayerEntity player) {
		if (player.isOnGround() || player.getAbilities().flying || player.isFallFlying() || player.isTouchingWater()
				|| player.isClimbing() || player.hasVehicle() || player.hasStatusEffect(StatusEffects.SLOW_FALLING)) {
			return false;
		}
		double safe = player.getAttributeValue(EntityAttributes.GENERIC_SAFE_FALL_DISTANCE);
		Landing landing = landingBelow(player);
		if (landing.soft()) {
			return false;
		}
		return player.fallDistance + landing.drop() > safe;
	}

	/**
	 * @param drop blocks from the player's feet down to the landing surface
	 *             (Double.MAX_VALUE when nothing is within reach: the void, a huge drop)
	 * @param soft lands in water (no fall damage)
	 */
	private record Landing(double drop, boolean soft) {
	}

	private static Landing landingBelow(ServerPlayerEntity player) {
		ServerWorld world = player.getServerWorld();
		double feetY = player.getY();
		BlockPos.Mutable pos = new BlockPos.Mutable(player.getBlockX(), MathHelper.floor(feetY), player.getBlockZ());
		int bottom = Math.max(world.getBottomY(), pos.getY() - MAX_DROP_SCAN);
		for (; pos.getY() >= bottom; pos.move(Direction.DOWN)) {
			if (world.getFluidState(pos).isIn(FluidTags.WATER)) {
				return new Landing(Math.max(0, feetY - (pos.getY() + 1)), true);
			}
			VoxelShape shape = world.getBlockState(pos).getCollisionShape(world, pos);
			if (!shape.isEmpty()) {
				double top = pos.getY() + shape.getMax(Direction.Axis.Y);
				if (top <= feetY + 1.0E-4) {
					return new Landing(feetY - top, false);
				}
			}
		}
		return new Landing(Double.MAX_VALUE, false);
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
