/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.pb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Old personal bests: the ones RuneLite's core Chat Commands plugin has
 * already stored, read-only, so a member doesn't have to re-kill every boss.
 * Chat Commands keeps them in the RS-profile config group {@code personalbest},
 * one key per boss in lower case, with raid team sizes as their own keys
 * ("chambers of xeric 3 players", "theatre of blood solo"). The plugin never
 * writes that group. The site checks the boss names against its own list.
 */
public final class PbBackfill
{
	public static final String GROUP = "personalbest";
	/** The site takes at most 300 in one batch. */
	static final int MAX = 300;
	private static final Pattern TEAM = Pattern.compile("^(?<boss>.+?) (?<team>solo|\\d{1,2}(?:\\+|-\\d{1,2})? players?)$");

	private PbBackfill()
	{
	}

	/** Chat Commands' keys and times (seconds) as records, in key order; non-positive times are skipped. */
	public static List<PbRecord> records(Map<String, Double> stored)
	{
		List<PbRecord> out = new ArrayList<>();
		for (Map.Entry<String, Double> e : new TreeMap<>(stored).entrySet())
		{
			Double v = e.getValue();
			String key = e.getKey() == null ? "" : e.getKey().trim();
			if (v == null || !(v > 0) || key.isEmpty())
			{
				continue;
			}
			String boss = key;
			String team = null;
			Matcher m = TEAM.matcher(key);
			if (m.find())
			{
				boss = m.group("boss");
				String t = m.group("team").replaceAll("\\s*players?$", "");
				team = "solo".equals(t) ? "1" : t;
			}
			out.add(new PbRecord(boss, Math.round(v * 100) / 100.0, team, null, false));
			if (out.size() == MAX)
			{
				break;
			}
		}
		return out;
	}

	/** A short fingerprint of the stored PBs, so an unchanged set isn't sent again. */
	public static String hash(Map<String, Double> stored)
	{
		return Integer.toHexString(new TreeMap<>(stored).toString().hashCode());
	}

	/** The key name after the RS profile's prefix, whatever form ConfigManager lists it in. */
	public static String keyName(String listed)
	{
		int dot = listed.lastIndexOf('.');
		return dot >= 0 ? listed.substring(dot + 1) : listed;
	}

	/**
	 * Whether to send today: not when the same account already sent today, nor
	 * when its PBs haven't changed since the last send. {@code last} is what
	 * the plugin saved: "date|rsn|hash".
	 */
	public static boolean due(String last, String today, String rsn, String hash)
	{
		if (last == null || last.isEmpty())
		{
			return true;
		}
		String[] p = last.split("\\|", 3);
		if (p.length < 3 || !p[1].equalsIgnoreCase(rsn))
		{
			return true;
		}
		return !p[0].equals(today) && !p[2].equals(hash);
	}

	/**
	 * Whether a batch answer settles the day: the site took it, or refused it
	 * for good (a 4xx). A network failure (0), a 5xx or a 401 (the token is
	 * dropped, so it waits for the next sign-in) leaves it open.
	 */
	public static boolean settled(int code)
	{
		return code >= 200 && code < 500 && code != 401;
	}

	public static String stamp(String today, String rsn, String hash)
	{
		return today + "|" + rsn + "|" + hash;
	}
}
