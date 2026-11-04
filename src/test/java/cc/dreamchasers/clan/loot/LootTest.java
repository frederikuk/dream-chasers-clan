/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.loot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class LootTest
{
	private static LootPicker.Stack s(int id, String name, long qty, long price)
	{
		return new LootPicker.Stack(id, name, qty, price);
	}

	@Test
	public void theClanThresholdComesFromTheSite()
	{
		assertEquals(10_000_000L, LootPicker.clanThreshold(null));
		assertEquals("a silly answer is ignored", 10_000_000L, LootPicker.clanThreshold(5L));
		assertEquals(25_000_000L, LootPicker.clanThreshold(25_000_000L));
	}

	@Test
	public void keepsStacksAtTheThresholdPriciestFirst()
	{
		List<LootPicker.Picked> p = LootPicker.pick(Arrays.asList(
			s(4151, "Abyssal whip", 1, 1_750_000),
			s(11832, "Bandos chestplate", 1, 15_000_000),
			s(995, "Coins", 12_000_000, 0),
			s(13204, "Platinum token", 9_000, 0),
			s(560, "Death rune", 30_000, 200),
			s(560, "Death rune", 30_000, 200),
			s(20997, "Twisted bow", 1, 1_450_000_000),
			s(1, "Nothing", 0, 99_999_999)), 10_000_000L);
		assertEquals(Arrays.asList("Twisted bow", "Bandos chestplate", "Coins", "Death rune"),
			Arrays.asList(p.get(0).getName(), p.get(1).getName(), p.get(2).getName(), p.get(3).getName()));
		assertEquals("coins count at 1 each", 1L, p.get(2).getPriceEach());
		assertEquals("stacks of one item are merged", 60_000, p.get(3).getQty());
		assertEquals("9,000 platinum tokens are 9M: under", 4, p.size());
	}

	@Test
	public void atTenMillionMostDropsPostNothing()
	{
		assertTrue(LootPicker.pick(Arrays.asList(s(4151, "Abyssal whip", 1, 1_750_000),
			s(526, "Bones", 1, 100), s(0, "Unpriced", 1, 0)), 10_000_000L).isEmpty());
	}

	@Test
	public void atMostTenItems()
	{
		List<LootPicker.Stack> many = new ArrayList<>();
		for (int i = 0; i < 15; i++)
		{
			many.add(s(1000 + i, "Item " + i, 1, 20_000_000 + i));
		}
		assertEquals(10, LootPicker.pick(many, 10_000_000L).size());
	}
}
