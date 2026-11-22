/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.pb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

/** Live competition KC reads a kill-count line on its own, with no time line after it. */
public class KillCountTest
{
	private static void reads(String line, String boss, int kc)
	{
		PbParser.KillCount k = PbParser.killCount(line);
		assertEquals(line, new PbParser.KillCount(boss, kc), k);
	}

	@Test
	public void everyKillCountFormOnItsOwn()
	{
		reads("Your Vorkath kill count is: <col=ff0000>301</col>.", "Vorkath", 301);
		reads("Your Kree'arra kill count is: 4.", "Kree'arra", 4);
		reads("Your completed Theatre of Blood count is: <col=ff0000>52</col>.", "Theatre of Blood", 52);
		reads("Your completion count for Tombs of Amascut: Expert Mode is: <col=ff0000>12</col>.", "Tombs of Amascut: Expert Mode", 12);
		reads("Your Chambers of Xeric completion count is: <col=ff0000>1,130</col>.", "Chambers of Xeric", 1130);
		reads("Your Vorkath kill count is: @mes_hl_red@300</col>.", "Vorkath", 300);
	}

	@Test
	public void otherLinesAreNotCounts()
	{
		assertNull(PbParser.killCount(null));
		assertNull(PbParser.killCount("Fight duration: 1:02.40 (new personal best)."));
		assertNull(PbParser.killCount("Lomaru has received a new collection log item: Pet snakeling"));
		// an anchored "Your": a clan broadcast about someone else's kill is never read as the player's own
		assertNull(PbParser.killCount("Lomaru: Your Vorkath kill count is: 5."));
		assertNull(PbParser.killCount("Your Vorkath kill count is: 99999999999."));
	}

	@Test
	public void readingACountLeavesThePbPairingAlone()
	{
		PbParser p = new PbParser();
		String kc = "Your Vorkath kill count is: <col=ff0000>301</col>.";
		assertNull(p.onMessage(kc, 10));
		PbParser.killCount(kc);     // the live-KC read of the same line
		PbRecord r = p.onMessage("Fight duration: <col=ff0000>1:02.40</col> (new personal best).", 10);
		assertEquals(new PbRecord("Vorkath", 62.4, null, 301, true), r);
	}
}
