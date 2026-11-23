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
import cc.dreamchasers.clan.api.KcResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Live competition KC: the newest kill count per boss, sent to
 * {@code POST /plugin/kc}.
 *
 * <p>KC only ever goes up, so only the newest count matters. One request per
 * boss is in flight at a time; a kill while it is out replaces the waiting
 * count, which goes as soon as the answer is in. A failed send (or one held
 * back by the client's back-off) keeps its count waiting, and the next kill of
 * that boss sends the newest: nothing is retried on a timer and nothing is
 * kept on disk.
 *
 * <p>A kill whose {@code /competition} check has no answer at all (no copy
 * yet, and the fetch failed or another one is out) is kept unchecked, the
 * highest count per boss, and offered once a real answer lists the boss: the
 * next kill's check, or the panel's own fetch. Only a real answer that leaves
 * the boss out drops it.
 *
 * <p>Budget, per client:
 * <ul>
 * <li>{@code POST /plugin/kc}: at most one per kill of a boss in a running
 * clan competition, never retried on a timer (the site caps the route at 60
 * posts per 10 minutes per member).</li>
 * <li>{@code POST /plugin/logout}: at most one per real logout or world hop,
 * never retried, only while the competition copy says {@code jitLogout} (any
 * clan competition running, skills too) or lists a boss (an older site has
 * only {@code kcBosses}), only from a main-game world, and at most one per account per
 * {@link #NOTICE_GAP_SECONDS} seconds, so hop-heavy play sends at most about
 * 30 an hour. The site turns them into at most one Wise Old Man update per
 * account per 10 minutes.</li>
 * <li>{@code GET /competition}: the copy the panel already uses, kept for its
 * max-age but at least 60 s (the site sends 30 s while a boss competition
 * runs, so in practice 60 s then, and 300 s otherwise). Kills
 * and logouts share it: at most one GET a minute from both together, and none
 * while the player neither kills a competition boss nor logs out nor hops.
 * So a logout or hop more than a minute after the last fetch usually costs one
 * GET and one POST.</li>
 * </ul>
 *
 * <p>A real logout or a world hop sends the logout notice so the site can ask
 * Wise Old Man for the hiscores the logout just moved: see {@link Account}.
 * When there is no copy yet and another {@code /competition} fetch is out,
 * the notice is held and settled once by that fetch's answer, never looped.
 *
 * <p>Thread safe: kills come in on the client thread, answers on OkHttp's.
 */
final class LiveKc
{
	/** The shortest gap between two logout notices for one account; well under the site's 10-minute debounce. */
	static final long NOTICE_GAP_SECONDS = 120;

	/**
	 * Who was logged in, read on the client thread while the game still has
	 * the player: by the time it says LOGIN_SCREEN or HOPPING the name may be
	 * gone. Kept until that logout or hop sends it, once.
	 */
	static final class Account
	{
		final String rsn;
		final String digest;
		final Integer acct;

		Account(String rsn, String digest, Integer acct)
		{
			this.rsn = rsn;
			this.digest = digest;
			this.acct = acct;
		}

		boolean same(String rsn, String digest, Integer acct)
		{
			return java.util.Objects.equals(this.rsn, rsn) && java.util.Objects.equals(this.digest, digest)
				&& java.util.Objects.equals(this.acct, acct);
		}
	}

	/** Whether a {@code /competition} copy lists any boss: a clan competition the plugin sends KC for is running. */
	static boolean running(CompetitionResponse r)
	{
		return !bosses(r).isEmpty();
	}

	/**
	 * Whether a logout or hop should send the logout notice: the site says a
	 * clan competition of any metric is running ({@code jitLogout}), or the
	 * copy lists a boss, so a site from before {@code jitLogout} still gets
	 * them during a boss competition.
	 */
	static boolean logoutWanted(CompetitionResponse r)
	{
		return r != null && (Boolean.TRUE.equals(r.getJitLogout()) || running(r));
	}

	/** Sends one count; {@code done} hears the site's answer. */
	interface Poster
	{
		void post(String boss, int kc, Consumer<ApiResult<KcResponse>> done);
	}

	private final Map<String, Integer> pending = new HashMap<>();
	private final Set<String> inFlight = new HashSet<>();
	/** Kills seen before any {@code /competition} answer could say whether they are wanted. */
	private final Map<String, Unchecked> unchecked = new HashMap<>();
	private int session;

	private static final class Unchecked
	{
		final int kc;
		final Poster poster;

		Unchecked(int kc, Poster poster)
		{
			this.kc = kc;
			this.poster = poster;
		}
	}

	/** The bosses the site wants counts for, from either place it may list them; never null. */
	static List<String> bosses(CompetitionResponse r)
	{
		if (r == null)
		{
			return Collections.emptyList();
		}
		List<String> out = new ArrayList<>();
		if (r.getKcBosses() != null)
		{
			out.addAll(r.getKcBosses());
		}
		if (r.getCompetition() != null && r.getCompetition().getKcBosses() != null)
		{
			out.addAll(r.getCompetition().getKcBosses());
		}
		return out;
	}

	private static final Pattern CURLY = Pattern.compile("[\u2018\u2019`]");
	private static final Pattern PUNCT = Pattern.compile("[:()]");
	private static final Pattern SPACES = Pattern.compile("\\s+");

	/**
	 * A boss name as the site compares it (its bossKey): lower case, curly
	 * apostrophes and backticks made straight, colons and brackets made
	 * spaces, runs of spaces made one, trimmed, then a leading "the " dropped.
	 * So "Theatre of Blood: Entry Mode" and "theatre of blood entry mode" are
	 * one name, and so are "The Gauntlet" and "Gauntlet".
	 */
	static String key(String s)
	{
		String k = s.toLowerCase(Locale.ROOT);
		k = CURLY.matcher(k).replaceAll("'");
		k = PUNCT.matcher(k).replaceAll(" ");
		k = SPACES.matcher(k).replaceAll(" ").trim();
		return k.startsWith("the ") ? k.substring(4) : k;
	}

	/** Whether the game's boss name is one of the site's, compared as {@link #key} has them. */
	static boolean wanted(List<String> bosses, String boss)
	{
		if (boss == null)
		{
			return false;
		}
		String b = key(boss);
		for (String s : bosses)
		{
			if (s != null && key(s).equals(b))
			{
				return true;
			}
		}
		return false;
	}

	/** The current session, read when a kill is seen; {@link #kill} ignores a kill from an earlier one. */
	synchronized int session()
	{
		return session;
	}

	/**
	 * One kill-count line and the {@code /competition} copy its check got
	 * (null when there was none at all). Offers it when the boss is wanted,
	 * keeps it unchecked when there was no copy, and first settles every kill
	 * kept unchecked.
	 */
	void kill(int s, CompetitionResponse comp, String boss, int kc, Poster poster)
	{
		if (comp == null)
		{
			synchronized (this)
			{
				if (s == session)
				{
					Unchecked old = unchecked.get(boss);
					unchecked.put(boss, new Unchecked(old == null ? kc : Math.max(old.kc, kc), poster));
				}
			}
			return;
		}
		synchronized (this)
		{
			if (s != session)
			{
				return;     // a logout since: that account's kill isn't sent
			}
		}
		List<String> bosses = bosses(comp);
		checked(bosses);
		if (wanted(bosses, boss))
		{
			offer(boss, kc, poster);
		}
	}

	/** A real {@code /competition} answer (the panel's, say): settles every kill kept unchecked. */
	void checked(CompetitionResponse comp)
	{
		if (comp != null)
		{
			checked(bosses(comp));
		}
	}

	private void checked(List<String> bosses)
	{
		Map<String, Unchecked> held;
		synchronized (this)
		{
			if (unchecked.isEmpty())
			{
				return;
			}
			held = new HashMap<>(unchecked);
			unchecked.clear();
		}
		for (Map.Entry<String, Unchecked> e : held.entrySet())
		{
			if (wanted(bosses, e.getKey()))
			{
				offer(e.getKey(), e.getValue().kc, e.getValue().poster);
			}
		}
	}

	/** One kill-count line for a wanted boss: sends it now, or keeps it as the newest waiting count. */
	void offer(String boss, int kc, Poster poster)
	{
		int s;
		int send;
		synchronized (this)
		{
			pending.merge(boss, kc, Math::max);
			if (inFlight.contains(boss))
			{
				return;     // the answer sends it
			}
			send = pending.remove(boss);
			inFlight.add(boss);
			s = session;
		}
		send(boss, send, s, poster);
	}

	private void send(String boss, int kc, int s, Poster poster)
	{
		poster.post(boss, kc, result -> answered(boss, kc, s, result, poster));
	}

	private void answered(String boss, int kc, int s, ApiResult<KcResponse> result, Poster poster)
	{
		Integer next = null;
		synchronized (this)
		{
			if (s != session)
			{
				return;     // a logout since: that account's counts are gone
			}
			inFlight.remove(boss);
			Integer waiting = pending.get(boss);
			if (result == null || result.getStatus() != ApiResult.Status.OK)
			{
				// not taken: the newest count waits for the next kill (the back-off would hold a send now anyway)
				pending.merge(boss, kc, Math::max);
			}
			else if (waiting != null && waiting > kc)
			{
				next = pending.remove(boss);
				inFlight.add(boss);
			}
			else
			{
				pending.remove(boss);
			}
		}
		if (next != null)
		{
			send(boss, next, s, poster);
		}
	}

	/** A logout, a hop or the plugin stopping: drops every waiting count; answers still out are ignored. */
	synchronized void reset()
	{
		session++;
		pending.clear();
		inFlight.clear();
		unchecked.clear();
	}

	/** The count waiting for a boss; null when none. For tests. */
	synchronized Integer waiting(String boss)
	{
		return pending.get(boss);
	}

	/** The count kept unchecked for a boss; null when none. For tests. */
	synchronized Integer unchecked(String boss)
	{
		Unchecked u = unchecked.get(boss);
		return u == null ? null : u.kc;
	}

	/** Whether a count for the boss is out. For tests. */
	synchronized boolean sending(String boss)
	{
		return inFlight.contains(boss);
	}
}
