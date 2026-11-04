/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Value;

/** Turns the Clan's Firsts board into a short "recent firsts" list. */
public final class HallOfFameDigest
{
	private HallOfFameDigest()
	{
	}

	@Value
	public static class RecentFirst
	{
		String name;
		/** e.g. "Zulrah 500 KC", "Attack 99", "Slayer 200M XP". */
		String achievement;
		String category;
		LocalDate date;
	}

	/**
	 * The clan's firsts (position 1 in each tier) with a known date, newest
	 * first, at most {@code limit}. Firsts without a date are left out: they
	 * predate WiseOldMan tracking, so they aren't "recent".
	 */
	public static List<RecentFirst> recentFirsts(HallOfFameResponse hof, int limit)
	{
		List<RecentFirst> out = new ArrayList<>();
		if (hof == null || hof.getFirsts() == null)
		{
			return out;
		}
		for (HallOfFameResponse.HallOfFameFirst first : hof.getFirsts())
		{
			if (first == null || first.getTiers() == null)
			{
				continue;
			}
			for (HallOfFameResponse.Tier tier : first.getTiers())
			{
				if (tier == null || tier.getHolders() == null)
				{
					continue;
				}
				for (HallOfFameResponse.Holder holder : tier.getHolders())
				{
					if (holder == null || holder.getPos() != 1)
					{
						continue;
					}
					LocalDate date = ApiTimes.parseDate(holder.getAt());
					if (date != null && holder.getName() != null)
					{
						out.add(new RecentFirst(holder.getName(), describe(first, tier), first.getCategory(), date));
					}
				}
			}
		}
		return out.stream()
			.sorted(Comparator.comparing(RecentFirst::getDate).reversed()
				.thenComparing(RecentFirst::getAchievement))
			.limit(Math.max(0, limit))
			.collect(Collectors.toList());
	}

	static String describe(HallOfFameResponse.HallOfFameFirst first, HallOfFameResponse.Tier tier)
	{
		String label = first.getLabel() == null ? "" : first.getLabel();
		String tierLabel = tier.getLabel() == null ? "" : tier.getLabel();
		String category = first.getCategory() == null ? "" : first.getCategory();
		switch (category)
		{
			case "boss":
				return label + " " + tierLabel + " KC";
			case "skill":
				// "99" is a level; the others (50M, 100M, 200M) are XP.
				return tierLabel.equals("99") ? label + " 99" : label + " " + tierLabel + " XP";
			default:
				return label;
		}
	}
}
