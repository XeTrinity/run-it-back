package dev.runitback.data;

import java.util.EnumSet;
import java.util.Set;

/**
 * One player's sidebar choices. Null fields mean "use the server default", so changing the
 * default in the config still reaches players who never touched their sidebar.
 */
public final class SidebarPrefs {
	public Boolean shown;
	/** Exact sections chosen with presets or toggles; null means the server's default preset. */
	public Set<SidebarSection> sections;

	public boolean shown(boolean serverDefault) {
		return shown != null ? shown : serverDefault;
	}

	public EnumSet<SidebarSection> sections(SidebarPreset serverDefault) {
		return sections != null && !sections.isEmpty() ? EnumSet.copyOf(sections) : serverDefault.sections();
	}

	public void applyPreset(SidebarPreset preset) {
		sections = preset.sections();
		shown = true;
	}

	/** Flips one section. Returns true if it is now on. */
	public boolean toggle(SidebarSection section, SidebarPreset serverDefault) {
		EnumSet<SidebarSection> current = sections(serverDefault);
		boolean on = !current.remove(section);
		if (on) current.add(section);
		sections = current;
		shown = true;
		return on;
	}

	public boolean isDefault() {
		return shown == null && sections == null;
	}
}
