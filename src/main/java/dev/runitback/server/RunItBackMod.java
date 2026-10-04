package dev.runitback.server;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public final class RunItBackMod implements ModInitializer {
	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(RunManager::serverStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(RunManager::serverStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> ResetService.serverStopped());
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, selection) -> RunCommands.register(dispatcher));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			RunManager manager = RunManager.get();
			if (manager != null) manager.tick();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			RunManager manager = RunManager.get();
			if (manager != null) manager.playerJoined(handler.player);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			RunManager manager = RunManager.get();
			if (manager != null) manager.playerLeft(handler.player);
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			RunManager manager = RunManager.get();
			if (manager != null) manager.entityDied(entity, source);
		});
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> {
			RunManager manager = RunManager.get();
			if (manager != null) manager.playerChangedDimension(player, destination);
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			RunManager manager = RunManager.get();
			if (manager != null) manager.playerRespawned(newPlayer, alive);
		});
	}
}
