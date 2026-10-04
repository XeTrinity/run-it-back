package dev.runitback.data;

public enum RunStatus {
	/** Fresh world, timer not started yet. */
	WAITING,
	RUNNING,
	WON,
	FAILED,
	/** Reset before the run was won or failed. */
	ABANDONED;

	public boolean isOver() {
		return this == WON || this == FAILED || this == ABANDONED;
	}
}
