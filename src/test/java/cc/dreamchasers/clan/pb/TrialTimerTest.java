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
import org.junit.Before;
import org.junit.Test;

/** A fake client: the trial varbit, the clock-start varp, the completion counts and the tick, driven by hand. */
public class TrialTimerTest
{
	private TrialTimer t;
	private int tick;
	private int inTrial;
	private int startTime;
	private int[] kcs;

	@Before
	public void setUp()
	{
		t = new TrialTimer();
		tick = 1000;
		inTrial = 0;
		startTime = 0;
		kcs = new int[]{10, 4, 0};
	}

	private PbRecord step()
	{
		return t.onTick(new TrialTimer.State(tick++, inTrial, startTime, kcs.clone()));
	}

	/** Runs ticks until one gives a record, at most {@code n}. */
	private PbRecord steps(int n)
	{
		for (int i = 0; i < n; i++)
		{
			PbRecord r = step();
			if (r != null)
			{
				return r;
			}
		}
		return null;
	}

	@Test
	public void aFinishedRunIsTimedFromTheClockStart()
	{
		step();
		inTrial = 3;            // Shark
		assertNull(step());
		assertNull(step());     // waiting at the start line
		startTime = 777123;     // the run's clock starts this tick
		assertNull(step());
		assertNull(steps(278)); // 279 ticks of sailing in all
		inTrial = 0;            // the trial ends...
		kcs[0]++;               // ...and the Tempor Tantrum count goes up
		PbRecord r = step();
		assertEquals("The Tempor Tantrum (Shark)", r.getBoss());
		assertEquals(279 * 0.6, r.getSeconds(), 0.001);
		assertEquals(Integer.valueOf(11), r.getKc());
		assertFalse("every run is sent; the site keeps the best", r.isPb());
		assertNull(r.getTeamSize());
		assertNull("one record per run", steps(5));
	}

	@Test
	public void theCountMayGoUpATickLate()
	{
		inTrial = 4;
		step();
		startTime = 5;
		step();
		steps(100);
		inTrial = 0;
		assertNull(step());
		kcs[2]++;
		PbRecord r = step();
		assertEquals("The Gwenith Glide (Marlin)", r.getBoss());
		assertEquals(Integer.valueOf(1), r.getKc());
	}

	@Test
	public void unrankedCountsAsSwordfishAndTheJiveIsFound()
	{
		inTrial = 1;
		step();
		startTime = 9;
		step();
		steps(50);
		inTrial = 0;
		kcs[1]++;
		assertEquals("The Jubbly Jive (Swordfish)", step().getBoss());
	}

	@Test
	public void anAbandonedRunPostsNothing()
	{
		inTrial = 2;
		step();
		startTime = 9;
		step();
		steps(40);
		inTrial = 0;            // left the trial: no count goes up
		assertNull(steps(TrialTimer.END_WAIT_TICKS + 2));
		// a late count change after giving up doesn't post either
		kcs[0]++;
		assertNull(steps(3));
	}

	@Test
	public void noClockStartSeenPostsNothing()
	{
		inTrial = 2;
		step();
		steps(20);
		inTrial = 0;
		kcs[0]++;
		assertNull("no start tick, no time", steps(5));
	}

	@Test
	public void aClockStartOutsideATrialIsIgnoredAndRunsCanFollowEachOther()
	{
		startTime = 3;          // seen before any trial: not a start
		step();
		inTrial = 2;
		step();
		startTime = 4;
		step();
		steps(9);               // 10 ticks
		inTrial = 0;
		kcs[0]++;
		assertEquals(6.0, step().getSeconds(), 0.001);
		// straight into another run
		inTrial = 3;
		step();
		startTime = 5;
		step();
		steps(19);
		inTrial = 0;
		kcs[0]++;
		PbRecord r = step();
		assertEquals("The Tempor Tantrum (Shark)", r.getBoss());
		assertEquals(12.0, r.getSeconds(), 0.001);
	}

	@Test
	public void namesAndRanks()
	{
		assertEquals("Swordfish", TrialTimer.rank(1));
		assertEquals("Swordfish", TrialTimer.rank(2));
		assertEquals("Shark", TrialTimer.rank(3));
		assertEquals("Marlin", TrialTimer.rank(4));
		assertNull(TrialTimer.rank(7));
		assertTrue(TrialTimer.isTrial("Tempor Tantrum Swordfish"));
		assertTrue(TrialTimer.isTrial("The Gwenith Glide (Marlin)"));
		assertFalse(TrialTimer.isTrial("Tempoross"));
	}
}
