/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.pb;

import java.util.regex.Pattern;
import lombok.Value;

/**
 * Sailing's Barracuda Trials. The game doesn't put the player's trial time in chat, so it is read
 * from game state, once a game tick:
 *
 * <ul>
 * <li>{@code VarbitID.SAILING_BT_IN_TRIAL}: 0 outside a trial; inside, the rank
 * (1 unranked and 2 Swordfish, 3 Shark, 4 Marlin).</li>
 * <li>{@code VarPlayerID.SAILING_BT_TIME_START}: changes when the run's clock
 * starts; the plugin notes the client tick then.</li>
 * <li>{@code VarPlayerID.SAILING_BT_TRIAL_*_COMPLETED}: each trial's completion
 * count. The one that goes up when the trial ends says which trial it was and
 * that the run was finished, not abandoned.</li>
 * </ul>
 *
 * The time is the ticks from the clock start to the end, at 0.6 s a tick.
 * Client thread only; pure otherwise, so it is unit tested with made-up state.
 */
public final class TrialTimer
{
	public static final String[] TRIALS = {"The Tempor Tantrum", "The Jubbly Jive", "The Gwenith Glide"};
	/** After the trial ends, how long to wait for the completion count to go up. */
	static final int END_WAIT_TICKS = 3;
	private static final double TICK_SECONDS = 0.6;
	private static final Pattern TRIAL_NAME = Pattern.compile("(?i)tempor tantrum|jubbly jive|gwenith glide");

	/** What the plugin reads each tick. {@code kcs} is in the order of {@link #TRIALS}. */
	@Value
	public static class State
	{
		int tick;
		int inTrial;
		int startTime;
		int[] kcs;
	}

	private boolean active;
	private String rank;
	private int[] kcsAtStart;
	private int clockStartTick = -1;
	private int lastStartTime;
	private int endTick = -1;

	/** A finished run's record, or null. */
	public PbRecord onTick(State s)
	{
		PbRecord out = null;
		if (!active && s.getInTrial() != 0)
		{
			active = true;
			rank = rank(s.getInTrial());
			kcsAtStart = s.getKcs().clone();
			clockStartTick = -1;
			endTick = -1;
		}
		if (s.getStartTime() != lastStartTime)
		{
			lastStartTime = s.getStartTime();
			if (active && endTick < 0 && s.getStartTime() != 0)
			{
				clockStartTick = s.getTick();
			}
		}
		if (!active)
		{
			return null;
		}
		if (endTick < 0 && s.getInTrial() == 0)
		{
			endTick = s.getTick();
		}
		if (endTick >= 0)
		{
			int done = finished(s.getKcs());
			if (done >= 0 && rank != null && clockStartTick >= 0 && endTick > clockStartTick)
			{
				double secs = Math.round((endTick - clockStartTick) * TICK_SECONDS * 100) / 100.0;
				out = new PbRecord(TRIALS[done] + " (" + rank + ")", secs, null, s.getKcs()[done], false);
				active = false;
			}
			else if (done >= 0 || s.getTick() - endTick >= END_WAIT_TICKS)
			{
				active = false;     // abandoned, or no clock start seen: nothing to post
			}
		}
		return out;
	}

	public void reset()
	{
		active = false;
		clockStartTick = -1;
		endTick = -1;
	}

	/** True for a trial's name, so a chat line about a trial never posts it a second time. */
	public static boolean isTrial(String boss)
	{
		return boss != null && TRIAL_NAME.matcher(boss).find();
	}

	static String rank(int inTrial)
	{
		switch (inTrial)
		{
			case 1:
			case 2:
				return "Swordfish";
			case 3:
				return "Shark";
			case 4:
				return "Marlin";
			default:
				return null;
		}
	}

	private int finished(int[] kcs)
	{
		for (int i = 0; i < kcs.length && i < kcsAtStart.length; i++)
		{
			if (kcs[i] > kcsAtStart[i])
			{
				return i;
			}
		}
		return -1;
	}
}
