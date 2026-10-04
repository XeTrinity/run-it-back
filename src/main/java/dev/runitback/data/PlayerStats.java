package dev.runitback.data;

/** A player's lifetime stats across every run. */
public final class PlayerStats {
	public String name;
	public int runsPlayed;
	public int runsWon;
	/** Runs that failed because of this player's death. */
	public int runsEnded;
	public int deaths;
	public int bossKills;
	public int mobKills;
	public double damageTaken;
}
