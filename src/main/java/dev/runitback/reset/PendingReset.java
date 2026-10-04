package dev.runitback.reset;

import java.util.ArrayList;
import java.util.List;

/**
 * Marker file written by {@code /run reset} just before the server stops. The next boot finds it
 * and replaces the world before Minecraft loads anything.
 */
public final class PendingReset {
	public static final String FILE_NAME = "pending-reset.json";

	/** Absolute path of the world folder to replace. */
	public String worldDir;
	/** Seed for the new world as typed in server.properties. Empty means random. */
	public String seed = "";
	/** Run number of the world being replaced, used to name archived worlds. */
	public int runNumber;
	public int keepOldWorlds;
	public List<String> preserve = new ArrayList<>();
	public long requestedAt;
	/** Destination recorded before the atomic world move, so an interrupted reset can resume. */
	public String movedWorldDir;
}
