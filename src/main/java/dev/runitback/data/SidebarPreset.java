package dev.runitback.data;

import java.util.EnumSet;
import java.util.Set;

public enum SidebarPreset {
	MIN(EnumSet.of(SidebarSection.DEATHS, SidebarSection.STATS, SidebarSection.BOSSES)),
	NORMAL(EnumSet.of(SidebarSection.DEATHS, SidebarSection.STATS, SidebarSection.DIMS, SidebarSection.BOSSES, SidebarSection.SPLITS)),
	MAX(EnumSet.allOf(SidebarSection.class));

	private final Set<SidebarSection> sections;

	SidebarPreset(Set<SidebarSection> sections) {
		this.sections = sections;
	}

	public EnumSet<SidebarSection> sections() {
		return EnumSet.copyOf(sections);
	}
}
