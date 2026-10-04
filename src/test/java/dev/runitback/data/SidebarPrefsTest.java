package dev.runitback.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class SidebarPrefsTest {
	@Test
	void untouchedPrefsFollowServerDefault() {
		SidebarPrefs prefs = new SidebarPrefs();
		assertTrue(prefs.isDefault());
		assertTrue(prefs.shown(true));
		assertFalse(prefs.shown(false));
		assertEquals(SidebarPreset.MIN.sections(), prefs.sections(SidebarPreset.MIN));
	}

	@Test
	void presetsGrowFromMinToMax() {
		assertTrue(SidebarPreset.NORMAL.sections().containsAll(SidebarPreset.MIN.sections()));
		assertTrue(SidebarPreset.MAX.sections().containsAll(SidebarPreset.NORMAL.sections()));
		assertEquals(EnumSet.allOf(SidebarSection.class), SidebarPreset.MAX.sections());
	}

	@Test
	void toggleStartsFromDefaultAndShows() {
		SidebarPrefs prefs = new SidebarPrefs();
		prefs.shown = false;
		assertFalse(prefs.toggle(SidebarSection.DEATHS, SidebarPreset.NORMAL), "deaths was on in normal");
		assertTrue(prefs.shown(false), "toggling a section shows the sidebar");
		assertEquals(EnumSet.of(SidebarSection.STATS, SidebarSection.DIMS, SidebarSection.BOSSES, SidebarSection.SPLITS), prefs.sections(SidebarPreset.NORMAL));
		assertTrue(prefs.toggle(SidebarSection.DEATHS, SidebarPreset.NORMAL));
		assertEquals(EnumSet.of(SidebarSection.DEATHS, SidebarSection.STATS, SidebarSection.DIMS, SidebarSection.BOSSES, SidebarSection.SPLITS),
			prefs.sections(SidebarPreset.MIN), "own choice wins over server default");
	}

	@Test
	void everyPresetShowsPlayerStats() {
		for (SidebarPreset preset : SidebarPreset.values()) {
			assertTrue(preset.sections().contains(SidebarSection.STATS), preset.name());
		}
	}

	@Test
	void presetReplacesToggles() {
		SidebarPrefs prefs = new SidebarPrefs();
		prefs.toggle(SidebarSection.STATS, SidebarPreset.NORMAL);
		prefs.applyPreset(SidebarPreset.MIN);
		assertEquals(SidebarPreset.MIN.sections(), prefs.sections(SidebarPreset.MAX));
	}
}
