/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Raid stats are sent only while the member is on the stream with the consent given. */
public class RaidShareTest
{
	@Test
	public void sentOnlyWithEverythingInPlace()
	{
		assertTrue(DreamChasersClanPlugin.raidShareAllowed(true, true, true, true, true));
	}

	@Test
	public void anyOneMissingSendsNothing()
	{
		for (int off = 0; off < 5; off++)
		{
			boolean[] b = {true, true, true, true, true};
			b[off] = false;
			assertFalse("condition " + off, DreamChasersClanPlugin.raidShareAllowed(b[0], b[1], b[2], b[3], b[4]));
		}
	}
}
