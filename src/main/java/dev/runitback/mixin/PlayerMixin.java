package dev.runitback.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.runitback.server.RunManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Counts actual health lost after mitigation and absorption, before lethal damage ends the run. */
@Mixin(Player.class)
abstract class PlayerMixin {
	@WrapOperation(
		method = "actuallyHurt",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;setHealth(F)V")
	)
	private void runitback$recordDamage(Player player, float health, Operation<Void> original) {
		float before = player.getHealth();
		original.call(player, health);
		RunManager manager = RunManager.get();
		if (manager != null && player instanceof ServerPlayer serverPlayer) {
			manager.playerDamaged(serverPlayer, before - player.getHealth());
		}
	}
}
