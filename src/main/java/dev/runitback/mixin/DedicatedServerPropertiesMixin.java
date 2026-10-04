package dev.runitback.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.runitback.reset.ResetBoot;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gives the freshly reset world its new seed without rewriting the admin's server.properties.
 * No Fabric event exists for world creation options.
 */
@Mixin(DedicatedServerProperties.class)
abstract class DedicatedServerPropertiesMixin {
	@WrapOperation(
		method = "<init>",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/dedicated/DedicatedServerProperties;get(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
	)
	private String runitback$overrideSeed(DedicatedServerProperties self, String key, String fallback, Operation<String> original) {
		String seed = ResetBoot.pendingSeed();
		if (seed != null && "level-seed".equals(key)) {
			return seed;
		}
		return original.call(self, key, fallback);
	}
}
