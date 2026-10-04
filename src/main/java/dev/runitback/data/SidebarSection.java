package dev.runitback.data;

/** Parts of the sidebar a player can switch on and off. Declaration order is display order. */
public enum SidebarSection {
	/** One line per player with their deaths this run. */
	DEATHS,
	/** When the group first entered each dimension. */
	DIMS,
	BOSSES,
	SPLITS,
	/** Per-player damage taken and hostile kills, on the same lines as deaths. */
	STATS;

	public String id() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}
