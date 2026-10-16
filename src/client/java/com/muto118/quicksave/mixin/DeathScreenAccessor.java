package com.muto118.quicksave.mixin;

import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DeathScreen.class)
public interface DeathScreenAccessor {
	@Accessor("message")
	@Nullable
	Text quicksave$getMessage();
}
