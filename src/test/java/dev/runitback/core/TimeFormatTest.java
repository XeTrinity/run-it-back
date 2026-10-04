package dev.runitback.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TimeFormatTest {
	@Test
	void clock() {
		assertEquals("0:00", TimeFormat.clock(0));
		assertEquals("0:59", TimeFormat.clock(59_999));
		assertEquals("12:05", TimeFormat.clock(725_000));
		assertEquals("1:02:03", TimeFormat.clock(3_723_000));
		assertEquals("0:00", TimeFormat.clock(-5));
	}

	@Test
	void delta() {
		assertEquals("-0:45", TimeFormat.delta(15_000, 60_000));
		assertEquals("+1:00", TimeFormat.delta(120_000, 60_000));
		assertEquals("+0:00", TimeFormat.delta(60_000, 60_000));
	}

	@Test
	void human() {
		assertEquals("40s", TimeFormat.human(40_000));
		assertEquals("12m 5s", TimeFormat.human(725_000));
		assertEquals("3h 12m", TimeFormat.human(11_520_000));
	}
}
