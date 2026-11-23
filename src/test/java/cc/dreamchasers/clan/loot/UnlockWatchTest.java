/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.loot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/** Blorva and Radiant oathplate: the item arriving in the player's own inventory (D-231123-2). */
public class UnlockWatchTest
{
	private static final int KIT = ItemID.DT2_SANGUINE_TORVA_KIT;
	private static final int SIGIL = ItemID.PURIFYING_SIGIL;

	private static int[] ids(int... ids)
	{
		return ids;
	}

	@Test
	public void theItemIdsAreTheGamesOwn()
	{
		assertEquals("the Ancient blood ornament kit", 28336, KIT);
		assertEquals("the assembled Purifying sigil", 30793, SIGIL);
		assertEquals(30783, ItemID.PURIFYING_SIGIL_LEFT);
		assertEquals(30785, ItemID.PURIFYING_SIGIL_RIGHT);
		assertEquals(30787, ItemID.PURIFYING_SIGIL_MIDDLE);
		assertEquals(30789, ItemID.PURIFYING_SIGIL_TOP);
		assertEquals(30791, ItemID.PURIFYING_SIGIL_BOTTOM);
		assertEquals("blorva", UnlockWatch.WATCHED.get(0).getKey());
		assertEquals("radiant_oathplate", UnlockWatch.WATCHED.get(1).getKey());
	}

	@Test
	public void theFirstLoadIsOnlyTheStartingPoint()
	{
		UnlockWatch w = new UnlockWatch();
		assertTrue(w.onInventory(ids(KIT, 995), new int[]{1, 100}, true, false).isEmpty());
		assertTrue("still there: nothing new", w.onInventory(ids(KIT, 995), new int[]{1, 200}, true, false).isEmpty());
	}

	@Test
	public void anArrivalAfterTheFirstLoadIsSentOnce()
	{
		UnlockWatch w = new UnlockWatch();
		w.onInventory(ids(995, -1), new int[]{100, 0}, true, false);
		List<UnlockWatch.Unlock> got = w.onInventory(ids(995, KIT), new int[]{100, 1}, true, false);
		assertEquals(1, got.size());
		assertEquals("blorva", got.get(0).getKey());
		assertEquals("Ancient blood ornament kit", got.get(0).getItem());
		w.onInventory(ids(995, -1), new int[]{100, 0}, true, false);     // used on the Torva
		assertTrue("once a session", w.onInventory(ids(995, KIT), new int[]{100, 1}, true, false).isEmpty());
	}

	@Test
	public void theAssembledSigilCountsButNotItsPieces()
	{
		UnlockWatch w = new UnlockWatch();
		w.onInventory(ids(-1), new int[]{0}, true, false);
		assertTrue(w.onInventory(ids(ItemID.PURIFYING_SIGIL_LEFT, ItemID.PURIFYING_SIGIL_TOP), new int[]{1, 1}, true, false).isEmpty());
		List<UnlockWatch.Unlock> got = w.onInventory(ids(SIGIL, -1), new int[]{1, 0}, true, false);
		assertEquals(1, got.size());
		assertEquals("radiant_oathplate", got.get(0).getKey());
		assertEquals(SIGIL, got.get(0).getId());
	}

	@Test
	public void aWithdrawalFromTheBankIsNotAnArrival()
	{
		UnlockWatch w = new UnlockWatch();
		w.onInventory(ids(-1), new int[]{0}, true, false);
		assertTrue(w.onInventory(ids(KIT), new int[]{1}, true, true).isEmpty());
		assertTrue("and it was there when the bank closed", w.onInventory(ids(KIT), new int[]{1}, true, false).isEmpty());
	}

	@Test
	public void loadsBeforeTheGameSettlesOnlyMoveTheStartingPoint()
	{
		UnlockWatch w = new UnlockWatch();
		w.onInventory(ids(-1), new int[]{0}, false, false);
		assertTrue(w.onInventory(ids(SIGIL), new int[]{1}, false, false).isEmpty());
		assertTrue(w.onInventory(ids(SIGIL), new int[]{1}, true, false).isEmpty());
	}

	@Test
	public void aLogoutStartsAgainFromTheNextLoad()
	{
		UnlockWatch w = new UnlockWatch();
		w.onInventory(ids(-1), new int[]{0}, true, false);
		assertEquals(1, w.onInventory(ids(KIT), new int[]{1}, true, false).size());
		w.reset();
		assertTrue("the login load", w.onInventory(ids(-1), new int[]{0}, true, false).isEmpty());
		assertEquals("a new session may send it again; the site keeps one row", 1,
			w.onInventory(ids(KIT), new int[]{1}, true, false).size());
	}
}
