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
 * <p>Budget: at most one POST per kill of a boss in a running clan
 * competition (the site caps the route at 60 posts per 10 minutes per member),
 * plus the {@code /competition} GET the panel already makes, served from the
 * client's cache (its max-age, at least 60 s): at most one GET a minute while
 * killing, usually one per 5 minutes, and none while not killing.
 *
 * <p>Thread safe: kills come in on the client thread, answers on OkHttp's.
 */
final class LiveKc
{
	/** Sends one count; {@code done} hears the site's answer. */
	interface Poster
	{
		void post(String boss, int kc, Consumer<ApiResult<KcResponse>> done);
	}

	private final Map<String, Integer> pending = new HashMap<>();
	private final Set<String> inFlight = new HashSet<>();
	private int session;

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

	/** Whether the game's boss name is one of the site's, ignoring case and spaces at the ends. */
	static boolean wanted(List<String> bosses, String boss)
	{
		if (boss == null)
		{
			return false;
		}
		String b = boss.trim().toLowerCase(Locale.ROOT);
		for (String s : bosses)
		{
			if (s != null && s.trim().toLowerCase(Locale.ROOT).equals(b))
			{
				return true;
			}
		}
		return false;
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
	}

	/** The count waiting for a boss; null when none. For tests. */
	synchronized Integer waiting(String boss)
	{
		return pending.get(boss);
	}

	/** Whether a count for the boss is out. For tests. */
	synchronized boolean sending(String boss)
	{
		return inFlight.contains(boss);
	}
}
