package dev.runitback.mixin;

import dev.runitback.server.RunManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the full death message ("fell from a high place while fleeing a Creeper"). Fabric's
 * AFTER_DEATH fires late enough that the combat log may already be cleared.
 */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
	@Inject(method = "die", at = @At("HEAD"))
	private void runitback$captureDeathMessage(DamageSource source, CallbackInfo ci) {
		RunManager manager = RunManager.get();
		if (manager != null) {
			ServerPlayer self = (ServerPlayer) (Object) this;
			manager.captureDeathMessage(self, self.getCombatTracker().getDeathMessage());
		}
	}
}
