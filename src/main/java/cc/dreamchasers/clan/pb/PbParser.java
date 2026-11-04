/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.pb;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the player's own boss and raid times out of game messages.
 *
 * <p>The game sends two lines for a timed kill: the kill count ("Your Vorkath
 * kill count is: 300.") and the time ("Fight duration: 1:02.40 (new personal
 * best)." or "... Personal best: 0:58.20"). Most bosses send the count first;
 * raids, Grotesque Guardians and Tempoross send the time first. The parser
 * keeps whichever arrives first for {@link #PAIR_TICKS} game ticks and pairs
 * it with the other.
 *
 * <p>Not thread safe: feed it from the client thread only. Pure otherwise,
 * so it is unit tested on the game's own message strings.
 */
public final class PbParser
{
	/** The two lines of one kill arrive within the same tick or the next few. */
	static final int PAIR_TICKS = 3;

	private static final String TIME = "\\d{1,3}(?::\\d{2}){1,2}(?:\\.\\d{1,2})?";
	private static final String LABEL = "(?<label>[^.!:\\n]*?)(?:duration:?|completion time:?|subdued in)";
	/** "Fight duration: 1:02.40 (new personal best)" */
	private static final Pattern NEW_PB = Pattern.compile(
		"(?i)" + LABEL + " (?<t>" + TIME + ")\\s*\\(new personal best\\)");
	/** "Fight duration: 1:10.20. Personal best: 0:58.20", CoX without the full stop */
	private static final Pattern OLD_PB = Pattern.compile(
		"(?i)" + LABEL + " (?<t>" + TIME + ")\\.?\\s*Personal best: (?<pb>" + TIME + ")");
	/** "Team size: 3 players", "Solo", "24+ players", "11-15 players" */
	private static final Pattern TEAM = Pattern.compile(
		"Team size: (?<team>Solo|\\d{1,2}(?:\\+|-\\d{1,2})? players?)", Pattern.CASE_INSENSITIVE);

	private static final Pattern KC_FOR = Pattern.compile("^Your completion count for (?<boss>.+?) is: ?(?<kc>[\\d,]+)");
	private static final Pattern KC_COMPLETED = Pattern.compile("^Your completed (?<boss>.+?) count is: ?(?<kc>[\\d,]+)");
	private static final Pattern KC_KILL = Pattern.compile("^Your (?<boss>.+?) (?:kill|completion) count is: ?(?<kc>[\\d,]+)");

	private static final Pattern TAG = Pattern.compile("<[^>]*>");
	private static final Pattern COLOUR_WORD = Pattern.compile("@[a-z_]+@");

	private String boss;
	private Integer kc;
	private int bossTick = Integer.MIN_VALUE;
	private Time time;
	private int timeTick = Integer.MIN_VALUE;

	private static final class Time
	{
		final double seconds;
		final boolean isPb;
		final String team;

		Time(double seconds, boolean isPb, String team)
		{
			this.seconds = seconds;
			this.isPb = isPb;
			this.team = team;
		}
	}

	/**
	 * Feeds one game message. Returns a finished record when this message
	 * completes a kill-count and time pair, else null.
	 */
	public PbRecord onMessage(String raw, int tick)
	{
		if (raw == null)
		{
			return null;
		}
		String text = clean(raw);
		Kc k = killCount(text);
		if (k != null)
		{
			if (time != null && tick - timeTick <= PAIR_TICKS)
			{
				PbRecord r = record(k.boss, k.kc, time);
				reset();
				return r;
			}
			time = null;
			boss = k.boss;
			kc = k.kc;
			bossTick = tick;
			return null;
		}
		Time t = time(text);
		if (t == null)
		{
			return null;
		}
		if (boss != null && tick - bossTick <= PAIR_TICKS)
		{
			PbRecord r = record(boss, kc, t);
			reset();
			return r;
		}
		boss = null;
		time = t;
		timeTick = tick;
		return null;
	}

	/** Forgets a half-seen kill, e.g. on logout or world hop. */
	public void reset()
	{
		boss = null;
		kc = null;
		time = null;
		bossTick = Integer.MIN_VALUE;
		timeTick = Integer.MIN_VALUE;
	}

	private static PbRecord record(String boss, Integer kc, Time t)
	{
		return new PbRecord(boss, t.seconds, t.team, kc, t.isPb);
	}

	/** Tags out, line breaks kept as newlines, non-breaking spaces made plain. */
	static String clean(String raw)
	{
		String s = raw.replace("<br>", "\n").replace(' ', ' ');
		s = TAG.matcher(s).replaceAll("");
		return COLOUR_WORD.matcher(s).replaceAll("");
	}

	private static final class Kc
	{
		final String boss;
		final Integer kc;

		Kc(String boss, Integer kc)
		{
			this.boss = boss;
			this.kc = kc;
		}
	}

	private static Kc killCount(String text)
	{
		for (Pattern p : new Pattern[]{KC_FOR, KC_COMPLETED, KC_KILL})
		{
			Matcher m = p.matcher(text);
			if (m.find())
			{
				String boss = m.group("boss").trim();
				Integer kc = null;
				try
				{
					kc = Integer.parseInt(m.group("kc").replace(",", ""));
				}
				catch (NumberFormatException e)
				{
					// kept without a count
				}
				return boss.isEmpty() ? null : new Kc(boss, kc);
			}
		}
		return null;
	}

	/** The time line of a kill: a new PB, or this kill with the standing PB. */
	private static Time time(String text)
	{
		String team = teamSize(text);
		for (String line : text.split("\n"))
		{
			Matcher m = NEW_PB.matcher(line);
			while (m.find())
			{
				if (counts(m.group("label"), line))
				{
					return new Time(seconds(m.group("t")), true, team);
				}
			}
			m = OLD_PB.matcher(line);
			while (m.find())
			{
				if (counts(m.group("label"), line))
				{
					return new Time(seconds(m.group("pb")), false, team);
				}
			}
		}
		return null;
	}

	/**
	 * Skips the times that aren't a boss's: raid totals (which include the
	 * waiting in the lobby; the Fractured Archive's total is its only time),
	 * Colosseum and ToB wave times, and agility laps.
	 */
	private static boolean counts(String label, String line)
	{
		String l = label.toLowerCase(Locale.ROOT);
		if (l.contains("total"))
		{
			return line.contains("Fractured Archive");
		}
		return !l.contains("wave") && !l.contains("lap") && !l.contains("preparation") && !l.contains("room");
	}

	/** "Solo" is "1", "3 players" is "3", "24+ players" and "11-15 players" keep their range. */
	static String teamSize(String text)
	{
		Matcher m = TEAM.matcher(text);
		if (!m.find())
		{
			return null;
		}
		String t = m.group("team").toLowerCase(Locale.ROOT).replaceAll("\\s*players?$", "");
		return "solo".equals(t) ? "1" : t;
	}

	/** "1:02.40" is 62.4, "1:00:56.50" is 3656.5, "104:31" is 6271. */
	static double seconds(String time)
	{
		String[] parts = time.split(":");
		double s = 0;
		for (int i = 0; i < parts.length - 1; i++)
		{
			s = s * 60 + Integer.parseInt(parts[i]);
		}
		s = s * 60 + Double.parseDouble(parts[parts.length - 1]);
		return Math.round(s * 100) / 100.0;
	}
}
