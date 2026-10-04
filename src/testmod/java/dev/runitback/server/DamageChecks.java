package dev.runitback.server;

import com.mojang.authlib.GameProfile;
import dev.runitback.RunItBack;
import dev.runitback.config.RunConfig;
import dev.runitback.data.RunPlayer;
import dev.runitback.data.RunStatus;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Exercises the real damage and death hooks, including the health change before the run ends. */
final class DamageChecks {
	static int run(CommandSourceStack source) {
		try {
			return runChecks(source);
		} catch (RuntimeException e) {
			RunItBack.LOG.error("Damage checks failed", e);
			throw e;
		}
	}

	private static int runChecks(CommandSourceStack source) {
		RunManager manager = RunManager.get();
		if (manager.tracker().current().isOver()) {
			throw new IllegalStateException("Damage checks require a fresh run");
		}
		manager.tracker().start();
		ServerPlayer player = player(source);
		checkHit(player, stats(player), 5, 5);
		player = player(source);
		// Apply armor attributes directly: bare test players do not tick equipment changes.
		player.getAttribute(Attributes.ARMOR).setBaseValue(20);
		player.getAttribute(Attributes.ARMOR_TOUGHNESS).setBaseValue(8);
		RunPlayer stats = stats(player);
		checkHit(player, stats, 5, null);
		float armoredDamage = stats.damageTaken;
		if (armoredDamage <= 0 || armoredDamage >= 5) {
			throw new IllegalStateException("Armor did not reduce recorded damage: " + armoredDamage);
		}

		player = player(source);
		player.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(10);
		player.setAbsorptionAmount(10);
		checkHit(player, stats(player), 5, 0);
		player = player(source);
		stats = stats(player);
		RunConfig.EndRunOn previous = manager.config().endRunOn;
		boolean previousSharedDeath = manager.config().sharedDeath;
		try {
			manager.config().endRunOn = RunConfig.EndRunOn.FIRST_DEATH;
			manager.config().sharedDeath = false;
			player.hurtServer(player.level(), player.damageSources().fall(), 1000);
			assertClose(20, stats.damageTaken, "lethal hit");
			if (manager.tracker().current().status != RunStatus.FAILED || stats.deaths != 1) {
				throw new IllegalStateException("Lethal damage did not finalize the run");
			}
		} finally {
			manager.config().endRunOn = previous;
			manager.config().sharedDeath = previousSharedDeath;
		}
		source.sendSuccess(() -> Component.literal("[hcrtest] Damage checks passed: unarmored, armor, absorption, lethal"), false);
		return 1;
	}

	private static void checkHit(ServerPlayer player, RunPlayer stats, float damage, Integer expected) {
		float before = stats.damageTaken;
		player.hurtServer(player.level(), player.damageSources().cactus(), damage);
		float lost = 20 - player.getHealth();
		assertClose(lost, stats.damageTaken - before, "health lost");
		if (expected != null) assertClose(expected, lost, "expected damage");
	}

	private static ServerPlayer player(CommandSourceStack source) {
		ServerPlayer player = new ServerPlayer(source.getServer(), source.getServer().overworld(),
			new GameProfile(UUID.randomUUID(), "DamageCheck"), ClientInformation.createDefault());
		player.connection = new CapturingListener(player);
		RunManager.get().tracker().playerJoined(player.getStringUUID(), player.getPlainTextName());
		return player;
	}

	private static RunPlayer stats(ServerPlayer player) {
		return RunManager.get().tracker().current().players.get(player.getStringUUID());
	}

	private static void assertClose(float expected, float actual, String context) {
		if (Math.abs(expected - actual) > 0.0001F) {
			throw new IllegalStateException(context + ": expected " + expected + ", got " + actual);
		}
	}
}
