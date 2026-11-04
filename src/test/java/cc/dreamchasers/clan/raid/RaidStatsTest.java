/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.raid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class RaidStatsTest
{
	private static final Instant T0 = Instant.parse("2026-11-02T20:00:00Z");

	@Test
	public void raidsOnly()
	{
		assertEquals("cox", RaidStats.raidOf(1, 0, 12345));
		assertEquals("tob", RaidStats.raidOf(0, 2, 12613));
		assertEquals("tob", RaidStats.raidOf(0, 3, 12613));      // dead, spectating: still the own raid
		assertNull("in a ToB party at the lobby isn't the raid", RaidStats.raidOf(0, 1, 14642));
		assertEquals("toa", RaidStats.raidOf(0, 0, 15700));
		assertNull("ToA lobby", RaidStats.raidOf(0, 0, 13454));
		assertNull("anywhere else", RaidStats.raidOf(0, 0, 12850));
	}

	@Test
	public void bodyIsOwnStatsOnly()
	{
		List<RaidStats.Item> inv = Arrays.asList(new RaidStats.Item(22461, 1, "Saradomin brew(4)"),
			new RaidStats.Item(0, 1, "nothing"), new RaidStats.Item(995, 0, "Coins"),
			new RaidStats.Item(1, 1, "<col=ff0000>Bad</col>"));
		JsonObject o = RaidStats.body("tob", 87, 99, 200, 99, inv);
		assertEquals("tob", o.get("raid").getAsString());
		assertEquals(87, o.get("hp").getAsInt());
		assertEquals("clamped to what the site takes", 150, o.get("prayer").getAsInt());
		assertEquals(1, o.getAsJsonArray("inv").size());
		assertEquals("Saradomin brew(4)", o.getAsJsonArray("inv").get(0).getAsJsonObject().get("name").getAsString());
		assertEquals(6, o.keySet().size());

		List<RaidStats.Item> full = new ArrayList<>();
		for (int i = 0; i < 40; i++)
		{
			full.add(new RaidStats.Item(100 + i, 1, "Shark"));
		}
		assertEquals(28, RaidStats.body("cox", 1, 99, 1, 99, full).getAsJsonArray("inv").size());
		assertEquals(40, RaidStats.cleanName("A very long item name that goes on and on and on").length());
	}

	@Test
	public void atMostEveryFiveSecondsAndOnlyOnAChange()
	{
		RaidStats rs = new RaidStats();
		assertTrue(rs.due(T0, "a"));
		rs.sending(T0, "a");
		assertFalse("one at a time", rs.due(T0.plusSeconds(6), "b"));
		rs.answered(T0, 5);
		assertFalse("5 s at least", rs.due(T0.plusSeconds(4), "b"));
		assertTrue(rs.due(T0.plusSeconds(5), "b"));
		assertFalse("unchanged: no post", rs.due(T0.plusSeconds(10), "a"));
		assertTrue("unchanged: a heartbeat every 30 s", rs.due(T0.plusSeconds(30), "a"));
	}

	@Test
	public void theSiteSetsThePace()
	{
		RaidStats rs = new RaidStats();
		rs.sending(T0, "a");
		rs.answered(T0, 60);                // not on stream
		assertFalse(rs.due(T0.plusSeconds(59), "b"));
		assertTrue(rs.due(T0.plusSeconds(60), "b"));
		rs.sending(T0.plusSeconds(60), "b");
		rs.answered(T0.plusSeconds(60), 0); // nonsense: still 5 s
		assertFalse(rs.due(T0.plusSeconds(64), "c"));
		rs.sending(T0.plusSeconds(65), "c");
		rs.answered(T0.plusSeconds(65), 999999);
		assertTrue("a wait is capped at 10 minutes", rs.due(T0.plusSeconds(65 + 600), "d"));
		rs.reset();
		assertFalse("reset keeps the site's wait", rs.due(T0.plusSeconds(66), "d"));
	}
}
