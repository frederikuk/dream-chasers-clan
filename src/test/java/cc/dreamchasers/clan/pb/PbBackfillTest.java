/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.pb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class PbBackfillTest
{
	@Test
	public void chatCommandsKeysBecomeRecords()
	{
		Map<String, Double> stored = new HashMap<>();
		stored.put("vorkath", 62.4);
		stored.put("chambers of xeric", 1500.0);
		stored.put("chambers of xeric 3 players", 1520.2);
		stored.put("chambers of xeric 24+ players", 2100.0);
		stored.put("theatre of blood solo", 2000.0);
		stored.put("tombs of amascut expert mode 11-15 players", 3000.0);
		stored.put("zulrah", 0.0);
		List<PbRecord> r = PbBackfill.records(stored);
		assertEquals(6, r.size());
		assertEquals("chambers of xeric", r.get(0).getBoss());
		assertNull(r.get(0).getTeamSize());
		assertEquals("24+", r.get(1).getTeamSize());
		assertEquals("3", r.get(2).getTeamSize());
		assertEquals("chambers of xeric", r.get(2).getBoss());
		assertEquals("1", r.get(3).getTeamSize());
		assertEquals("theatre of blood", r.get(3).getBoss());
		assertEquals("11-15", r.get(4).getTeamSize());
		assertEquals("vorkath", r.get(5).getBoss());
		assertFalse("old PBs, never news", r.get(5).isPb());
	}

	@Test
	public void atMostOnceADayAndOnlyWhenChanged()
	{
		String h = "abc";
		assertTrue(PbBackfill.due(null, "2026-11-02", "Maho z h", h));
		String s = PbBackfill.stamp("2026-11-02", "Maho z h", h);
		assertFalse("sent today", PbBackfill.due(s, "2026-11-02", "Maho z h", "def"));
		assertFalse("nothing changed", PbBackfill.due(s, "2026-11-03", "Maho z h", h));
		assertTrue("a new day and something changed", PbBackfill.due(s, "2026-11-03", "Maho z h", "def"));
		assertTrue("another account", PbBackfill.due(s, "2026-11-02", "Tadeko", h));
		Map<String, Double> a = new HashMap<>();
		a.put("vorkath", 62.4);
		Map<String, Double> b = new HashMap<>(a);
		assertEquals(PbBackfill.hash(a), PbBackfill.hash(b));
		b.put("zulrah", 50.0);
		assertFalse(PbBackfill.hash(a).equals(PbBackfill.hash(b)));
		assertEquals("vorkath", PbBackfill.keyName("rsprofile.abc123.vorkath"));
		assertEquals("vorkath", PbBackfill.keyName("vorkath"));
	}

	@Test
	public void aRefusedBatchIsNotSentAgainToday()
	{
		assertTrue("taken", PbBackfill.settled(200));
		assertTrue("refused for good", PbBackfill.settled(400));
		assertTrue("not a member", PbBackfill.settled(403));
		assertTrue("rate limited", PbBackfill.settled(429));
		assertFalse("no answer", PbBackfill.settled(0));
		assertFalse("the site was down: next real login", PbBackfill.settled(503));
		assertFalse("the token is dropped: next sign-in", PbBackfill.settled(401));
		// what a settled answer stores holds the batch until a new day and new PBs
		String s = PbBackfill.stamp("2026-11-02", "Maho z h", "abc");
		assertFalse(PbBackfill.due(s, "2026-11-02", "Maho z h", "abc"));
	}
}
