/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.loot;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;

/**
 * New collection log slots, paired with the drop they came in when there
 * is one, and big drops that aren't new slots.
 *
 * <p>The game says "New item added to your collection log: X" (only with the
 * in-game setting "Collection log - New addition notification" on); RuneLite's
 * Loot Tracker sends the drop as a {@code LootReceived}. They arrive within a
 * tick or two of each other, in either order, so both wait {@link #WAIT_TICKS}
 * before being released: a slot then carries the drop's source, item id and
 * quantity, and that item isn't posted a second time as a big drop.
 *
 * <p>Client thread only. Pure otherwise, so it is unit tested.
 */
public final class ClogTracker
{
	static final int WAIT_TICKS = 3;
	private static final int KEEP_TICKS = 3 * WAIT_TICKS;
	private static final Pattern CLOG = Pattern.compile("^New item added to your collection log: (?<item>.+?)\\.?$");
	private static final Pattern TAG = Pattern.compile("<[^>]*>");

	/** A new collection log slot; {@code source}, {@code id} and the price are null when no drop matched it. */
	@Value
	public static class Clog
	{
		String name;
		String source;
		Integer id;
		long qty;
		Long guidePrice;
	}

	/** A drop's stacks that weren't new slots, for the big-drop check. */
	@Value
	public static class Drop
	{
		String source;
		List<LootPicker.Stack> stacks;
	}

	@Value
	public static class Released
	{
		List<Clog> clogs;
		List<Drop> drops;

		public boolean isEmpty()
		{
			return clogs.isEmpty() && drops.isEmpty();
		}
	}

	private static final class Seen
	{
		final String name;
		final int tick;

		Seen(String name, int tick)
		{
			this.name = name;
			this.tick = tick;
		}
	}

	private static final class Loot
	{
		final String source;
		final List<LootPicker.Stack> stacks;
		final int tick;
		boolean released;

		Loot(String source, List<LootPicker.Stack> stacks, int tick)
		{
			this.source = source;
			this.stacks = stacks;
			this.tick = tick;
		}
	}

	private final List<Seen> pending = new ArrayList<>();
	private final List<Seen> recent = new ArrayList<>();
	private final List<Loot> loots = new ArrayList<>();

	/** The item a "New item added to your collection log" message names, or null for any other message. */
	public static String clogItem(String message)
	{
		if (message == null)
		{
			return null;
		}
		Matcher m = CLOG.matcher(TAG.matcher(message).replaceAll("").replace(' ', ' ').trim());
		if (!m.find())
		{
			return null;
		}
		String item = m.group("item").trim();
		return item.isEmpty() ? null : item;
	}

	/**
	 * The item the "Collection log" popup names: its title and main text, as the
	 * game's notification varcs hold them ("New item:<br>Twisted bow"), or null.
	 */
	public static String popupItem(String title, String main)
	{
		if (title == null || main == null || !"collection log".equalsIgnoreCase(TAG.matcher(title).replaceAll("").trim()))
		{
			return null;
		}
		String text = TAG.matcher(main.replace("<br>", " ")).replaceAll("").replace('\u00a0', ' ').trim();
		if (text.regionMatches(true, 0, "New item:", 0, 9))
		{
			text = text.substring(9).trim();
		}
		return text.isEmpty() ? null : text;
	}

	/** A new slot, from the chat line or the popup; the same slot seen both ways counts once. */
	public void onClog(String name, int tick)
	{
		for (List<Seen> list : java.util.Arrays.asList(pending, recent))
		{
			for (Seen c : list)
			{
				if (same(c.name, name) && Math.abs(c.tick - tick) <= KEEP_TICKS)
				{
					return;
				}
			}
		}
		pending.add(new Seen(name, tick));
	}

	/**
	 * A drop, from core's NpcLootReceived or ServerNpcLoot, or the Loot Tracker's
	 * LootReceived (chests, raids, clues). The same drop reported by two of them
	 * within a few ticks counts once.
	 */
	public void onLoot(String source, List<LootPicker.Stack> stacks, int tick)
	{
		String sig = signature(stacks);
		for (Loot l : loots)
		{
			if (Math.abs(l.tick - tick) <= WAIT_TICKS && same(l.source, source) && signature(l.stacks).equals(sig))
			{
				return;
			}
		}
		loots.add(new Loot(source, new ArrayList<>(stacks), tick));
	}

	private static String signature(List<LootPicker.Stack> stacks)
	{
		java.util.Map<Integer, Long> m = new java.util.TreeMap<>();
		for (LootPicker.Stack s : stacks)
		{
			m.merge(s.getId(), s.getQty(), Long::sum);
		}
		return m.toString();
	}

	/** What is ready to post at this tick. */
	public Released onTick(int tick)
	{
		List<Clog> clogs = new ArrayList<>();
		for (Iterator<Seen> it = pending.iterator(); it.hasNext(); )
		{
			Seen c = it.next();
			if (tick - c.tick < WAIT_TICKS)
			{
				continue;
			}
			it.remove();
			recent.add(c);
			Clog out = new Clog(c.name, null, null, 1, null);
			for (Loot l : loots)
			{
				if (Math.abs(l.tick - c.tick) > WAIT_TICKS)
				{
					continue;
				}
				LootPicker.Stack s = find(l.stacks, c.name);
				if (s != null)
				{
					out = new Clog(c.name, l.source, s.getId(), s.getQty(), s.getGuidePrice() > 0 ? s.getGuidePrice() : null);
					break;
				}
			}
			clogs.add(out);
		}
		List<Drop> drops = new ArrayList<>();
		for (Loot l : loots)
		{
			if (l.released || tick - l.tick < WAIT_TICKS)
			{
				continue;
			}
			l.released = true;
			List<LootPicker.Stack> rest = new ArrayList<>();
			for (LootPicker.Stack s : l.stacks)
			{
				if (!isSlot(s.getName(), l.tick))
				{
					rest.add(s);
				}
			}
			if (!rest.isEmpty())
			{
				drops.add(new Drop(l.source, rest));
			}
		}
		recent.removeIf(c -> tick - c.tick > KEEP_TICKS);
		loots.removeIf(l -> l.released && tick - l.tick > KEEP_TICKS);
		return new Released(clogs, drops);
	}

	/** Forgets everything waiting, e.g. on logout or world hop. */
	public void reset()
	{
		pending.clear();
		recent.clear();
		loots.clear();
	}

	private boolean isSlot(String name, int lootTick)
	{
		for (List<Seen> list : java.util.Arrays.asList(pending, recent))
		{
			for (Seen c : list)
			{
				if (Math.abs(c.tick - lootTick) <= WAIT_TICKS && same(c.name, name))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static LootPicker.Stack find(List<LootPicker.Stack> stacks, String name)
	{
		for (LootPicker.Stack s : stacks)
		{
			if (same(s.getName(), name))
			{
				return s;
			}
		}
		return null;
	}

	private static boolean same(String a, String b)
	{
		return a != null && b != null && a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
	}
}
