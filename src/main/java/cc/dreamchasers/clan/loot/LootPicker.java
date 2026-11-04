/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.loot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Value;
import net.runelite.api.gameval.ItemID;

/**
 * Which stacks of a drop are big enough to post. Pure, so it is unit
 * tested. Prices are RuneLite's own ({@code ItemManager.getItemPriceWithSource(id, true)},
 * the Wiki price RuneLite already has): the plugin makes no price calls of its own.
 */
public final class LootPicker
{
	/** The clan's big-drop threshold until the site says otherwise (Hall of Fame feed, loots.threshold). */
	public static final long CLAN_DEFAULT = 10_000_000L;
	/** A site answer below this is ignored, so a mistake there can't make every drop post. */
	static final long FLOOR = 1_000_000L;
	/** The site takes at most 10 items a drop. */
	static final int MAX_ITEMS = 10;

	private LootPicker()
	{
	}

	/** One stack of a drop: canonical (unnoted) id, name, quantity, RuneLite's price each. */
	@Value
	public static class Stack
	{
		int id;
		String name;
		long qty;
		long guidePrice;
	}

	/** One item as posted. */
	@Value
	public static class Picked
	{
		int id;
		String name;
		long qty;
		long priceEach;

		public long value()
		{
			return qty * priceEach;
		}
	}

	/** The threshold the site sent, or the clan default when it sent none (or something silly). */
	public static long clanThreshold(Long fromSite)
	{
		return fromSite == null || fromSite < FLOOR ? CLAN_DEFAULT : fromSite;
	}

	/** Merges stacks of one item and keeps those worth at least the threshold, priciest first, at most 10. */
	public static List<Picked> pick(Collection<Stack> drop, long threshold)
	{
		Map<Integer, Stack> merged = new LinkedHashMap<>();
		for (Stack s : drop)
		{
			if (s.getQty() <= 0)
			{
				continue;
			}
			merged.merge(s.getId(), s, (a, b) -> new Stack(a.getId(), a.getName(), a.getQty() + b.getQty(),
				Math.max(a.getGuidePrice(), b.getGuidePrice())));
		}
		List<Picked> out = new ArrayList<>();
		for (Stack s : merged.values())
		{
			long fixed = fixedPrice(s.getId());
			long each = fixed > 0 ? fixed : s.getGuidePrice();
			if (each > 0 && each * s.getQty() >= threshold)
			{
				out.add(new Picked(s.getId(), s.getName(), s.getQty(), each));
			}
		}
		out.sort((a, b) -> Long.compare(b.value(), a.value()));
		return out.size() > MAX_ITEMS ? new ArrayList<>(out.subList(0, MAX_ITEMS)) : out;
	}

	private static long fixedPrice(int id)
	{
		return id == ItemID.COINS ? 1 : id == ItemID.PLATINUM ? 1000 : 0;
	}
}
