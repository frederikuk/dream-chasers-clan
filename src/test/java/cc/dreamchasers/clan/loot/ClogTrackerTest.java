/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.loot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class ClogTrackerTest
{
	private ClogTracker t;

	@Before
	public void setUp()
	{
		t = new ClogTracker();
	}

	private static List<LootPicker.Stack> zulrah()
	{
		return Arrays.asList(new LootPicker.Stack(12921, "Pet snakeling", 1, 0),
			new LootPicker.Stack(12934, "Zulrah's scales", 500, 160),
			new LootPicker.Stack(12922, "Tanzanite fang", 1, 2_000_000));
	}

	@Test
	public void readsTheGameMessage()
	{
		assertEquals("Twisted bow", ClogTracker.clogItem("New item added to your collection log: <col=ef1020>Twisted bow</col>"));
		assertEquals("Pet snakeling", ClogTracker.clogItem("New item added to your collection log: Pet snakeling"));
		assertEquals("Dragon pickaxe", ClogTracker.clogItem("New item added to your collection log: Dragon pickaxe."));
		assertNull(ClogTracker.clogItem("Your Zulrah kill count is: 5."));
		assertNull(ClogTracker.clogItem("New item added to your collection log: "));
		assertNull(ClogTracker.clogItem(null));
	}

	@Test
	public void aSlotWaitsForItsDropEitherOrder()
	{
		t.onClog("Pet snakeling", 100);
		t.onLoot("Zulrah", zulrah(), 101);
		assertTrue("waits a few ticks", t.onTick(101).isEmpty());
		ClogTracker.Released r = t.onTick(100 + ClogTracker.WAIT_TICKS);
		assertEquals(1, r.getClogs().size());
		ClogTracker.Clog c = r.getClogs().get(0);
		assertEquals("Zulrah", c.getSource());
		assertEquals(Integer.valueOf(12921), c.getId());
		assertNull("no guide price for an untradeable", c.getGuidePrice());
		r = t.onTick(101 + ClogTracker.WAIT_TICKS);
		assertEquals("the drop comes out without the new slot", 1, r.getDrops().size());
		assertEquals(Arrays.asList("Zulrah's scales", "Tanzanite fang"),
			Arrays.asList(r.getDrops().get(0).getStacks().get(0).getName(), r.getDrops().get(0).getStacks().get(1).getName()));

		t.reset();
		t.onLoot("Zulrah", zulrah(), 200);
		t.onClog("Tanzanite fang", 201);
		r = t.onTick(203);
		assertEquals("the fang is a new slot, so the drop has the rest", 2, r.getDrops().get(0).getStacks().size());
		r = t.onTick(204);
		assertEquals("Zulrah", r.getClogs().get(0).getSource());
		assertEquals(Long.valueOf(2_000_000), r.getClogs().get(0).getGuidePrice());
	}

	@Test
	public void aSlotWithoutADropStillPosts()
	{
		t.onClog("Scroll sack", 10);
		ClogTracker.Released r = t.onTick(13);
		assertEquals(1, r.getClogs().size());
		assertNull(r.getClogs().get(0).getSource());
		assertNull(r.getClogs().get(0).getId());
		assertTrue("released once", t.onTick(14).isEmpty());
	}

	@Test
	public void aDropFarFromTheSlotIsNotPaired()
	{
		t.onLoot("Zulrah", zulrah(), 10);
		t.onClog("Pet snakeling", 30);
		assertEquals(3, t.onTick(13).getDrops().get(0).getStacks().size());
		assertNull(t.onTick(33).getClogs().get(0).getSource());
	}

	@Test
	public void popupAndChatLineCountOnce()
	{
		assertEquals("Twisted bow", ClogTracker.popupItem("Collection log", "New item:<br>Twisted bow"));
		assertEquals("Pet snakeling", ClogTracker.popupItem("<col=ff981f>Collection log</col>", "New item:<br><col=ffffff>Pet snakeling</col>"));
		assertNull(ClogTracker.popupItem("Combat Task Completed!", "Task: Noxious Foe"));
		assertNull(ClogTracker.popupItem(null, "x"));
		t.onClog("Twisted bow", 100);      // the chat line
		t.onClog("Twisted bow", 101);      // and the popup
		assertTrue(t.onTick(102).isEmpty());
		assertEquals(1, t.onTick(103).getClogs().size());
		t.onClog("twisted bow", 104);      // still the same slot, just after
		assertTrue(t.onTick(110).isEmpty());
	}

	@Test
	public void theSameDropFromTwoEventsCountsOnce()
	{
		t.onLoot("Zulrah", zulrah(), 10);           // ServerNpcLoot
		t.onLoot("Zulrah", zulrah(), 10);           // NpcLootReceived
		t.onLoot("Zulrah", zulrah(), 11);           // the Loot Tracker's LootReceived
		assertEquals(1, t.onTick(13).getDrops().size());
		t.onLoot("Zulrah", zulrah(), 30);           // the next kill is its own drop
		assertEquals(1, t.onTick(33).getDrops().size());
	}

	@Test
	public void resetForgetsEverything()
	{
		t.onClog("Pet snakeling", 1);
		t.onLoot("Zulrah", zulrah(), 1);
		t.reset();
		assertTrue(t.onTick(50).isEmpty());
	}
}
