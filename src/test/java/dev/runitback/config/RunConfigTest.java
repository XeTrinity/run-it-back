package dev.runitback.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RunConfigTest {
	private static RunConfig parse(String json) {
		RunConfig config = JsonFiles.GSON.fromJson(json, RunConfig.class);
		config.normalize();
		return config;
	}

	@Test
	void freshConfigHasNoSplitsAndBossesInProgressionOrder() {
		RunConfig config = parse("{}");
		assertTrue(config.splits.isEmpty());
		assertEquals(List.of("minecraft:elder_guardian", "minecraft:warden", "minecraft:wither", "minecraft:ender_dragon"),
			config.bosses.stream().map(b -> b.entity).toList());
		assertEquals(3, config.reset.countdownSeconds);
		assertEquals(RunConfig.CURRENT_VERSION, config.configVersion);
		assertFalse(config.display.bossbar, "run time is in the sidebar title instead");
	}

	@Test
	void version2BossbarIsTurnedOff() {
		RunConfig config = parse("""
			{"configVersion": 2, "display": {"bossbar": true}}""");
		assertFalse(config.display.bossbar);
		assertEquals(RunConfig.CURRENT_VERSION, config.configVersion);
	}

	@Test
	void version1DefaultsAreUpgraded() {
		RunConfig config = parse("""
			{
			  "bosses": [
			    {"entity": "minecraft:ender_dragon", "label": "Dragon", "required": true},
			    {"entity": "minecraft:wither", "label": "Wither", "required": true},
			    {"entity": "minecraft:warden", "label": "Warden", "required": false},
			    {"entity": "minecraft:elder_guardian", "label": "Elder Guardian", "required": false}
			  ],
			  "splits": [
			    {"advancement": "minecraft:story/smelt_iron", "label": "Iron"},
			    {"advancement": "minecraft:story/enter_the_nether", "label": "Nether"}
			  ],
			  "reset": {"countdownSeconds": 5}
			}""");
		assertTrue(config.splits.isEmpty(), "old default splits are dropped");
		assertEquals(3, config.reset.countdownSeconds);
		assertEquals(List.of("Elder Guardian", "Warden", "Wither", "Dragon"), config.bosses.stream().map(b -> b.label).toList());
		assertTrue(config.bosses.get(2).required, "custom required flag kept");
	}

	@Test
	void customisedSettingsSurviveUpgrade() {
		RunConfig config = parse("""
			{
			  "splits": [{"advancement": "minecraft:story/smelt_iron", "label": "Iron"}, {"advancement": "mypack:custom", "label": "Mine"}],
			  "reset": {"countdownSeconds": 10}
			}""");
		assertEquals(2, config.splits.size());
		assertEquals(10, config.reset.countdownSeconds);
	}

	@Test
	void currentVersionIsLeftAlone() {
		RunConfig config = parse("""
			{"configVersion": 3, "splits": [{"advancement": "minecraft:story/smelt_iron", "label": "Iron"}], "reset": {"countdownSeconds": 5}}""");
		assertEquals(1, config.splits.size());
		assertFalse(config.splits.isEmpty());
		assertEquals(5, config.reset.countdownSeconds);
	}
}
