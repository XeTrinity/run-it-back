package dev.runitback.data;

public final class DeathRecord {
	public String uuid;
	public String name;
	/** The vanilla death message as plain text, e.g. "Dave fell from a high place". */
	public String message;
	/** Damage type id, e.g. "minecraft:fall". */
	public String cause;
	/** Entity type id of the killer, if any. */
	public String killer;
	public String dimension;
	public int x;
	public int y;
	public int z;
	/** Run time when it happened. */
	public long timeMs;
	public long epochMs;
	/** True if this death ended the run. */
	public boolean fatal;
}
