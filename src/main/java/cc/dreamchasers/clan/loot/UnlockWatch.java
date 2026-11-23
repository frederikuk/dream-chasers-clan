/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.loot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Value;
import net.runelite.api.gameval.ItemID;

/**
 * Listed firsts that aren't collection log slots, seen as the item arriving in
 * the player's own inventory (D-231123-2): Blorva (the Ancient blood ornament
 * kit, {@code DT2_SANGUINE_TORVA_KIT}, 28336) and Radiant oathplate (the
 * assembled Purifying sigil, {@code PURIFYING_SIGIL}, 30793; its five pieces,
 * 30783 left, 30785 right, 30787 middle, 30789 top and 30791 bottom, don't
 * count).
 *
 * <p>An arrival is a count going from 0 to more than 0 between two inventory
 * loads in one session. The first load after a login or hop is only the
 * starting point (the items were already there), and so is every load while
 * the game hasn't settled or a bank is open (a withdrawal isn't an
 * acquisition). Each unlock goes at most once a session; the site keeps one
 * row per member, account and unlock, so a repeat in a later session changes
 * nothing there. Client thread only.
 */
public final class UnlockWatch
{
	@Value
	public static class Unlock
	{
		/** The site's key (rules.js / achievements.py UNLOCKS). */
		String key;
		/** The item, as the game names it. */
		String item;
		int id;
	}

	public static final List<Unlock> WATCHED = Collections.unmodifiableList(Arrays.asList(
		new Unlock("blorva", "Ancient blood ornament kit", ItemID.DT2_SANGUINE_TORVA_KIT),
		new Unlock("radiant_oathplate", "Purifying sigil", ItemID.PURIFYING_SIGIL)));

	/** The watched items' counts at the last load; null until the first load of a session. */
	private Map<Integer, Integer> last;
	private final Set<String> sent = new HashSet<>();

	/**
	 * One inventory load: the item ids and quantities, slot by slot (empty
	 * slots as id -1 or 0). Returns the unlocks that just arrived; empty while
	 * {@code settled} is false or {@code storageOpen} is true, which only move
	 * the starting point.
	 */
	public List<Unlock> onInventory(int[] ids, int[] qtys, boolean settled, boolean storageOpen)
	{
		Map<Integer, Integer> now = counts(ids, qtys);
		Map<Integer, Integer> before = last;
		last = now;
		if (before == null || !settled || storageOpen)
		{
			return Collections.emptyList();
		}
		List<Unlock> out = new ArrayList<>();
		for (Unlock u : WATCHED)
		{
			if (before.getOrDefault(u.getId(), 0) == 0 && now.getOrDefault(u.getId(), 0) > 0 && sent.add(u.getKey()))
			{
				out.add(u);
			}
		}
		return out;
	}

	private static Map<Integer, Integer> counts(int[] ids, int[] qtys)
	{
		Map<Integer, Integer> m = new HashMap<>();
		for (int i = 0; ids != null && i < ids.length; i++)
		{
			for (Unlock u : WATCHED)
			{
				if (ids[i] == u.getId())
				{
					int q = qtys != null && i < qtys.length ? Math.max(qtys[i], 0) : 1;
					m.merge(u.getId(), q, Integer::sum);
				}
			}
		}
		return m;
	}

	/** A logout, a hop, the setting going off or the plugin stopping: the next load is a starting point again. */
	public void reset()
	{
		last = null;
		sent.clear();
	}
}
