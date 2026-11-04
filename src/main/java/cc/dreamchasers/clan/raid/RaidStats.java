/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.raid;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Live raid stats for the DRM Live overlay: the player's OWN hitpoints,
 * prayer and inventory, only inside a raid, at most once every 5 seconds.
 * Nothing about other players and nothing derived from boss mechanics.
 *
 * <p>This class is the pure part: which raid the player is in, the body sent,
 * and when the next post is due. It never touches the client.
 */
public final class RaidStats
{
	/** Never more often than this, whatever the site says. */
	public static final Duration MIN_INTERVAL = Duration.ofSeconds(5);
	/** Unchanged stats are re-sent this often, so the overlay knows they're current. */
	public static final Duration HEARTBEAT = Duration.ofSeconds(30);
	/** The site's answer is ignored past this (a stuck "wait" can't stop it for good). */
	static final Duration MAX_WAIT = Duration.ofMinutes(10);

	/**
	 * Tombs of Amascut's rooms (the Nexus, each path's puzzle and boss room, the
	 * Wardens and the loot room). The lobby outside isn't included. CoX and ToB
	 * come from their own varbits instead.
	 */
	static final Set<Integer> TOA_REGIONS = Set.of(14160, 14162, 14164, 14672, 14674, 14676,
		15184, 15186, 15188, 15696, 15698, 15700);

	private Instant nextAllowed = Instant.EPOCH;
	private Instant lastSent = Instant.EPOCH;
	private String lastKey;
	private boolean inFlight;

	/**
	 * "cox", "tob", "toa" or null. {@code coxInDungeon}: RAIDS_CLIENT_INDUNGEON;
	 * {@code tobStatus}: TOB_CLIENT_PARTYSTATUS (2 = inside, 3 = dead and
	 * spectating, both still the player's own raid); {@code regionId}: the
	 * player's region, instance-resolved.
	 */
	public static String raidOf(int coxInDungeon, int tobStatus, int regionId)
	{
		if (coxInDungeon == 1)
		{
			return "cox";
		}
		if (tobStatus >= 2)
		{
			return "tob";
		}
		return TOA_REGIONS.contains(regionId) ? "toa" : null;
	}

	/** One inventory slot, as sent. */
	public static final class Item
	{
		final int id;
		final int qty;
		final String name;

		public Item(int id, int qty, String name)
		{
			this.id = id;
			this.qty = qty;
			this.name = name;
		}
	}

	/** {raid, hp, maxHp, prayer, maxPrayer, inv: [{id, qty, name}]}: empty slots left out, at most 28. */
	public static JsonObject body(String raid, int hp, int maxHp, int prayer, int maxPrayer, List<Item> inv)
	{
		JsonObject o = new JsonObject();
		o.addProperty("raid", raid);
		o.addProperty("hp", clamp(hp, 0, 150));
		o.addProperty("maxHp", clamp(maxHp, 1, 99));
		o.addProperty("prayer", clamp(prayer, 0, 150));
		o.addProperty("maxPrayer", clamp(maxPrayer, 1, 99));
		JsonArray items = new JsonArray();
		for (Item it : inv)
		{
			if (items.size() >= 28)
			{
				break;
			}
			String name = cleanName(it.name);
			if (it.id <= 0 || it.qty <= 0 || name == null)
			{
				continue;
			}
			JsonObject i = new JsonObject();
			i.addProperty("id", it.id);
			i.addProperty("qty", it.qty);
			i.addProperty("name", name);
			items.add(i);
		}
		o.add("inv", items);
		return o;
	}

	/** The site takes letters, digits, spaces and {@code '()+.,&!/-}, up to 40; anything else is left out. */
	static String cleanName(String name)
	{
		if (name == null)
		{
			return null;
		}
		String s = name.trim();
		if (s.length() > 40)
		{
			s = s.substring(0, 40).trim();
		}
		return !s.isEmpty() && s.matches("[A-Za-z0-9 '()+.,&!/-]+") ? s : null;
	}

	private static int clamp(int v, int lo, int hi)
	{
		return Math.max(lo, Math.min(hi, v));
	}

	/** True when a post of this body is due now: a change, or the heartbeat, and never inside the wait. */
	public synchronized boolean due(Instant now, String key)
	{
		if (inFlight || now.isBefore(nextAllowed))
		{
			return false;
		}
		return !key.equals(lastKey) || !now.isBefore(lastSent.plus(HEARTBEAT));
	}

	/** Call when the post goes out. */
	public synchronized void sending(Instant now, String key)
	{
		inFlight = true;
		lastSent = now;
		lastKey = key;
		nextAllowed = now.plus(MIN_INTERVAL);
	}

	/** The site's answer: {@code nextSeconds} is how long to wait (5 on stream, 60 when not, 300 without consent). */
	public synchronized void answered(Instant sentAt, int nextSeconds)
	{
		inFlight = false;
		Duration wait = Duration.ofSeconds(Math.max(MIN_INTERVAL.getSeconds(), nextSeconds));
		if (wait.compareTo(MAX_WAIT) > 0)
		{
			wait = MAX_WAIT;
		}
		nextAllowed = sentAt.plus(wait);
	}

	/** Leaving the raid, logging out, or the setting turned off: the next raid starts fresh. */
	public synchronized void reset()
	{
		inFlight = false;
		lastKey = null;
		lastSent = Instant.EPOCH;
		// a wait the site asked for stands (no consent, not on stream)
	}
}
