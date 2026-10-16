package com.muto118.quicksave.snapshot;

import org.jetbrains.annotations.Nullable;

/**
 * Contents of a slot's meta.json. M4's load menu shows these fields.
 *
 * @param name       display name (manual saves) or a generated label
 * @param type       quick / auto / manual
 * @param createdMs  real time, epoch millis
 * @param dayTime    world time of day in ticks (day = dayTime / 24000 + 1)
 * @param dimension  e.g. "minecraft:overworld"
 * @param playtimeTicks total ticks played in this world
 */
public record SaveMeta(
		String name,
		SlotType type,
		long createdMs,
		long dayTime,
		@Nullable String dimension,
		double x,
		double y,
		double z,
		float health,
		long playtimeTicks) {
}
