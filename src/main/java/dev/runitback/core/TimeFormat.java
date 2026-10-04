package dev.runitback.core;

public final class TimeFormat {
	private TimeFormat() {
	}

	/** {@code 1:02:03} with hours, {@code 2:03} without. */
	public static String clock(long ms) {
		long totalSeconds = Math.max(0, ms) / 1000;
		long hours = totalSeconds / 3600;
		long minutes = totalSeconds / 60 % 60;
		long seconds = totalSeconds % 60;
		return hours > 0
			? String.format("%d:%02d:%02d", hours, minutes, seconds)
			: String.format("%d:%02d", minutes, seconds);
	}

	/** Signed difference against a best time, e.g. {@code -0:45} or {@code +1:02:00}. */
	public static String delta(long ms, long best) {
		long diff = ms - best;
		return (diff < 0 ? "-" : "+") + clock(Math.abs(diff));
	}

	/** Compact duration for summaries: {@code 3h 12m}, {@code 12m 5s}, {@code 40s}. */
	public static String human(long ms) {
		long totalSeconds = Math.max(0, ms) / 1000;
		long hours = totalSeconds / 3600;
		long minutes = totalSeconds / 60 % 60;
		long seconds = totalSeconds % 60;
		if (hours > 0) return hours + "h " + minutes + "m";
		if (minutes > 0) return minutes + "m " + seconds + "s";
		return seconds + "s";
	}
}
