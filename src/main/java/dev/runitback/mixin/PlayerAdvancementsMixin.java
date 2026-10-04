package dev.runitback.mixin;

import dev.runitback.server.RunManager;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Splits are advancement-based. Fabric API has no "advancement completed" event, so hook the point
 * where vanilla hands out the rewards, which happens exactly once when an advancement completes.
 */
@Mixin(PlayerAdvancements.class)
abstract class PlayerAdvancementsMixin {
	@Shadow
	private ServerPlayer player;

	@Inject(
		method = "award",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/advancements/AdvancementRewards;grant(Lnet/minecraft/server/level/ServerPlayer;)V")
	)
	private void runitback$onCompleted(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
		RunManager manager = RunManager.get();
		if (manager != null) {
			manager.advancementCompleted(player, holder.id().toString());
		}
	}
}
