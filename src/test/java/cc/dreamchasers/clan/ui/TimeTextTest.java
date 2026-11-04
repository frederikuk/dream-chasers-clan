/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.ui;

import static org.junit.Assert.assertEquals;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.Test;

public class TimeTextTest
{
	@Test
	public void spans()
	{
		assertEquals("under a minute", TimeText.span(Duration.ofSeconds(59)));
		assertEquals("under a minute", TimeText.span(Duration.ofMinutes(-5)));
		assertEquals("45m", TimeText.span(Duration.ofMinutes(45)));
		assertEquals("2h", TimeText.span(Duration.ofHours(2)));
		assertEquals("2h 15m", TimeText.span(Duration.ofMinutes(135)));
		assertEquals("3d", TimeText.span(Duration.ofDays(3)));
		assertEquals("3d 4h", TimeText.span(Duration.ofHours(76).plusMinutes(10)));
	}

	@Test
	public void whenUsesThePlayersZone()
	{
		Instant t = Instant.parse("2026-11-02T19:00:00Z");
		assertEquals("Mon 2 Nov, 19:00", TimeText.when(t, ZoneId.of("Europe/London")));
		assertEquals("Mon 2 Nov, 14:00", TimeText.when(t, ZoneId.of("America/New_York")));
		assertEquals("", TimeText.when(null, ZoneId.of("UTC")));
	}

	@Test
	public void daysShowTheYearOnlyWhenItIsNotThisYear()
	{
		assertEquals("2 Nov", TimeText.day(java.time.LocalDate.of(2026, 11, 2), 2026));
		assertEquals("31 Jan 2025", TimeText.day(java.time.LocalDate.of(2025, 1, 31), 2026));
		assertEquals("", TimeText.day(null, 2026));
	}

	@Test
	public void until()
	{
		Instant now = Instant.parse("2026-11-02T17:00:00Z");
		assertEquals("in 2h", TimeText.until(now, Instant.parse("2026-11-02T19:00:00Z")));
		assertEquals("", TimeText.until(now, null));
	}

	@Test
	public void siteTextIsEscapedForSwingHtml()
	{
		assertEquals("&lt;html&gt;&lt;img src=x&gt; &amp; &quot;hi&quot; &#39;",
			DreamChasersPanel.esc("<html><img src=x> & \"hi\" '"));
		assertEquals("", DreamChasersPanel.esc(null));
	}
}
