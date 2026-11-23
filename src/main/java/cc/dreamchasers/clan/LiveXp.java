/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan;

import cc.dreamchasers.clan.api.ApiResult;
import cc.dreamchasers.clan.api.CompetitionResponse;
import cc.dreamchasers.clan.api.XpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.runelite.api.Skill;

/**
 * Live competition XP: the player's own XP in the running competition's skill,
 * sent to {@code POST /plugin/xp} on a slow timer (D-231123-2).
 *
 * <p>XP moves every few ticks while training, so unlike KC it is never sent per
 * change. The newest XP per skill is noted from {@code StatChanged}, keyed by
 * the Wise Old Man metric name ({@link #metricOf}; "overall" is the total). A
 * game tick sends it only when all of these hold:
 * <ul>
 * <li>the XP has moved this session (the burst of values the game sends at
 * login is the starting point, not a gain);</li>
 * <li>at least {@link #every()} seconds have passed since the last try (the
 * site's {@code xpEvery}, default {@link #DEFAULT_EVERY_SECONDS}, never below
 * {@link #MIN_EVERY_SECONDS} whatever the site says);</li>
 * <li>the site hasn't said "cap" (then only the logout notice carries XP,
 * until the cap's {@code until} or the next UTC day);</li>
 * <li>the competition's {@code xpSkill} names a skill whose XP differs from
 * the last value the site answered for.</li>
 * </ul>
 *
 * <p>Budget, per client, with the site's 300 s: at most 12 {@code POST
 * /plugin/xp} an hour, and only while the competition's skill is being
 * trained; at most 12 {@code GET /competition} an hour on top (the copy is
 * kept at least 60 s, and a try reads it cache-first only once its interval is
 * up and XP in the competition's skill has moved). At the 120 s floor, 30 of
 * each. Training another skill costs nothing once a copy has named the
 * competition's skill; with no skill competition in the copy, a gain checks
 * again at most every {@link #NO_SKILL_RECHECK_SECONDS} s (4 GETs an hour, no
 * POST). A failed send isn't retried before the next interval, and the
 * client's back-off holds it longer. Nothing is kept on disk.
 *
 * <p>Thread safe: XP and ticks come in on the client thread, answers on
 * OkHttp's.
 */
final class LiveXp
{
	/** The interval when the site doesn't say. */
	static final long DEFAULT_EVERY_SECONDS = 300;
	/** The plugin never sends faster than this, even if the site asks. */
	static final long MIN_EVERY_SECONDS = 120;
	/**
	 * With no skill competition in the last copy, a gain checks the copy again
	 * at most this often, so a member training all day outside a competition
	 * costs at most 4 GETs an hour (the panel's own fetches tell it sooner).
	 */
	static final long NO_SKILL_RECHECK_SECONDS = 900;
	/** The metric name of the total, which no {@code StatChanged} carries: read from the game's overall XP. */
	static final String OVERALL = "overall";

	/** The site's interval, floored at {@link #MIN_EVERY_SECONDS}; the default when absent or nonsense. */
	static long every(CompetitionResponse r)
	{
		Integer s = r == null ? null : r.getXpEvery();
		if (s == null || s <= 0)
		{
			return DEFAULT_EVERY_SECONDS;
		}
		return Math.max(s, MIN_EVERY_SECONDS);
	}

	/** The metric name Wise Old Man (and the site) use for a skill: lower case, RUNECRAFT as "runecrafting". */
	static String metricOf(Skill s)
	{
		return s == Skill.RUNECRAFT ? "runecrafting" : s.name().toLowerCase(Locale.ROOT);
	}

	/**
	 * A site metric name made comparable with {@link #metricOf}: trimmed, lower
	 * case, "runecraft" read as "runecrafting". Null for null or blank.
	 */
	static String metricKey(String metric)
	{
		if (metric == null)
		{
			return null;
		}
		String m = metric.trim().toLowerCase(Locale.ROOT);
		if (m.isEmpty())
		{
			return null;
		}
		return m.equals("runecraft") ? "runecrafting" : m;
	}

	/** The skill metric the site wants XP for now, from the cached {@code /competition}; null when none. */
	static String wanted(CompetitionResponse r)
	{
		if (r == null)
		{
			return null;
		}
		String s = r.getXpSkill();
		if (s == null && r.getCompetition() != null)
		{
			s = r.getCompetition().getXpSkill();
		}
		return metricKey(s);
	}

	/** One XP value to send: its skill, as the site names it, and the XP. */
	static final class Send
	{
		final String skill;
		final long xp;

		Send(String skill, long xp)
		{
			this.skill = skill;
			this.xp = xp;
		}
	}

	/** The newest XP per metric this session. */
	private final Map<String, Long> newest = new HashMap<>();
	/** The first XP per metric this session (the login values): a value no higher than this hasn't moved. */
	private final Map<String, Long> start = new HashMap<>();
	/** The last XP the site answered for, per metric: an unchanged value isn't sent again. */
	private final Map<String, Long> answered = new HashMap<>();
	/** The skill the last copy named; null when it named none. */
	private String target;
	/** Whether a copy has been seen at all (until then every gain counts). */
	private boolean targetKnown;
	/** No skill in the last copy: no try before this. */
	private Instant idleUntil;
	private boolean moved;
	private boolean inFlight;
	private Instant lastTry;
	/** The site said "cap": no timed sends before this. Kept over logouts: the cap is per member per UTC day. */
	private Instant cappedUntil;
	private long every = DEFAULT_EVERY_SECONDS;
	private int session;

	/** The game's XP for one of the player's own skills ({@code StatChanged}), or {@link #OVERALL}. */
	synchronized void stat(String metric, long xp)
	{
		if (metric == null || xp < 0)
		{
			return;
		}
		Long old = newest.put(metric, xp);
		if (old == null)
		{
			start.put(metric, xp);
		}
		if (old != null && xp > old && (!targetKnown || target == null || target.equals(metric)))
		{
			moved = true;     // the first value per session is where it starts, not a gain
		}
	}

	/** Any {@code /competition} answer (the panel's, a kill's, a try's): the interval and the skill. */
	synchronized void heard(CompetitionResponse r)
	{
		if (r == null)
		{
			return;
		}
		every = every(r);
		String was = target;
		target = wanted(r);
		targetKnown = true;
		if (target != null)
		{
			idleUntil = null;     // a skill competition is on: the normal interval applies
			if (!target.equals(was) && changed(target))
			{
				moved = true;     // it just started (or changed) and this session has moved
			}
		}
	}

	/** The interval in use now, in seconds. */
	synchronized long every()
	{
		return every;
	}

	/**
	 * Whether a game tick should look for a send now: XP has moved, nothing is
	 * out, the interval is up and no cap holds. Claims the try when it says
	 * yes, so the next ticks don't ask again; {@link #pick}, {@link #settle} or
	 * {@link #answered} ends it.
	 */
	synchronized boolean due(Instant now)
	{
		if (!moved || inFlight)
		{
			return false;
		}
		if (cappedUntil != null && now.isBefore(cappedUntil))
		{
			return false;
		}
		if (lastTry != null && now.isBefore(lastTry.plusSeconds(every)))
		{
			return false;
		}
		if (idleUntil != null && now.isBefore(idleUntil))
		{
			return false;
		}
		inFlight = true;
		lastTry = now;
		return true;
	}

	/** The current session, read when a try starts; an answer from an earlier one is ignored. */
	synchronized int session()
	{
		return session;
	}

	/**
	 * A claimed try once the {@code /competition} copy is in: the value to send
	 * for the skill it names, or null (no copy, no skill, no XP for it, or the
	 * same as the site last had), which ends the try.
	 */
	synchronized Send pick(int s, CompetitionResponse comp)
	{
		if (s != session)
		{
			return null;
		}
		if (comp == null)
		{
			inFlight = false;     // no copy at all: the next interval tries again
			return null;
		}
		heard(comp);
		String skill = wanted(comp);
		if (skill == null)
		{
			idleUntil = lastTry == null ? null : lastTry.plusSeconds(NO_SKILL_RECHECK_SECONDS);
		}
		if (skill == null || !changed(skill))
		{
			inFlight = false;
			moved = false;     // nothing new for the competition's skill: wait for the next gain
			return null;
		}
		return new Send(skill, newest.get(skill));
	}

	/**
	 * Whether a metric's XP has risen this session and isn't the value the
	 * site last took: the only XP worth sending.
	 */
	private boolean changed(String metric)
	{
		Long xp = newest.get(metric);
		Long first = start.get(metric);
		return xp != null && first != null && xp > first && !xp.equals(answered.get(metric));
	}

	/** Ends a try that sent nothing. */
	synchronized void settle(int s)
	{
		if (s == session)
		{
			inFlight = false;
		}
	}

	/**
	 * The site's answer to one send. Any 200 but "cap" is an answer: that XP
	 * isn't sent again. "cap" stores nothing and stops timed sends until its
	 * {@code until} (or the next UTC day), so the XP stays unsent for the
	 * logout notice. A failure keeps the XP for the next interval.
	 */
	synchronized void answered(int s, Send sent, ApiResult<XpResponse> result, Instant now)
	{
		if (s != session)
		{
			return;
		}
		inFlight = false;
		if (result == null || result.getStatus() != ApiResult.Status.OK || result.getValue() == null)
		{
			return;     // not taken: the next interval sends the newest
		}
		XpResponse r = result.getValue();
		if (!r.isStored() && "cap".equals(r.getReason()))
		{
			// not kept: the logout notice (or the first send after the cap) still carries it
			cappedUntil = capEnd(r.getUntil(), now);
			moved = true;
			return;
		}
		answered.put(sent.skill, sent.xp);
		Long latest = newest.get(sent.skill);
		moved = latest != null && latest != sent.xp;     // gained while the send was out
	}

	/**
	 * When a cap ends: the site's {@code until} when it is within the next two
	 * days, otherwise (absent, unreadable or far off) the next UTC midnight.
	 */
	static Instant capEnd(String until, Instant now)
	{
		Instant midnight = LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		if (until == null)
		{
			return midnight;
		}
		try
		{
			Instant u = Instant.parse(until);
			return u.isAfter(now) && u.isBefore(now.plusSeconds(2 * 86400)) ? u : midnight;
		}
		catch (DateTimeParseException e)
		{
			return midnight;
		}
	}

	/** Whether timed sends are held by a cap now. */
	synchronized boolean capped(Instant now)
	{
		return cappedUntil != null && now.isBefore(cappedUntil);
	}

	/**
	 * The newest XP of each metric that has risen this session and isn't what
	 * the site last took, taken on the client thread for the logout notice. A
	 * skill nobody trained, or one the site already has, isn't in it, so its
	 * notice carries no XP.
	 */
	synchronized Map<String, Long> snapshot()
	{
		Map<String, Long> out = new HashMap<>();
		for (String m : newest.keySet())
		{
			if (changed(m))
			{
				out.put(m, newest.get(m));
			}
		}
		return out;
	}

	/**
	 * A logout, a hop or the plugin stopping: the next session starts again
	 * from the game's login values; an answer still out is ignored. The cap,
	 * the interval and the time of the last try stay, so hopping never sends
	 * faster than the interval.
	 */
	synchronized void reset()
	{
		session++;
		newest.clear();
		start.clear();
		answered.clear();
		moved = false;
		inFlight = false;
	}

	/** Whether a try is out. For tests. */
	synchronized boolean sending()
	{
		return inFlight;
	}

	/** Whether XP has moved since the last answer. For tests. */
	synchronized boolean moved()
	{
		return moved;
	}
}
