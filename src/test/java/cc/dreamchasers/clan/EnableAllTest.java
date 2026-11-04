/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.junit.Test;

public class EnableAllTest
{
	private static final String ALL = DreamChasersClanConfig.ENABLE_ALL_KEY;

	/**
	 * A stand-in for ConfigManager: each change that really changes a value
	 * raises ConfigChanged, handled by EnableAll.onChanged. {@code sync}: the
	 * event is handled inside setConfiguration (nested); otherwise queued and
	 * handled after, as a later dispatch would.
	 */
	private static final class Settings
	{
		final Map<String, Boolean> values = new HashMap<>();
		final boolean sync;
		final Deque<String> queue = new ArrayDeque<>();
		int events;
		int connects;
		int depth;
		boolean signedIn = true;

		Settings(boolean sync)
		{
			this.sync = sync;
		}

		boolean get(String key)
		{
			return values.getOrDefault(key, false);
		}

		/** What a member (or the plugin) does: change one value. */
		void set(String key, boolean on)
		{
			if (get(key) == on)
			{
				return;     // ConfigManager raises nothing for an unchanged value
			}
			values.put(key, on);
			if (sync)
			{
				dispatch(key);
			}
			else
			{
				queue.add(key);
			}
		}

		void dispatch(String key)
		{
			events++;
			assertTrue("runaway ConfigChanged loop", events < 100 && ++depth < 20);
			if (EnableAll.onChanged(key, this::get, this::set, signedIn))
			{
				connects++;
			}
			depth--;
		}

		/** Delivers queued events until none are left. */
		void drain()
		{
			while (!queue.isEmpty())
			{
				dispatch(queue.poll());
			}
		}

		boolean allFeatures(boolean on)
		{
			return EnableAll.FEATURES.stream().allMatch(f -> get(f.getKey()) == on);
		}
	}

	@Test
	public void flipsExactlyTheFeatureSettings()
	{
		Map<String, Boolean> config = new HashMap<>();
		EnableAll.setAll(config::put, true);
		assertEquals(new HashSet<>(Arrays.asList("connect", "postPbs", "postLoot", "clanSplash", "tilePacks", "joinLive",
			"shareRaidStats")), config.keySet());
		assertTrue(EnableAll.allOn(k -> config.getOrDefault(k, false)));
		// never the token, the stream consent or the splash opt-in (set only by an upload)
		assertFalse(config.containsKey(DreamChasersClanConfig.TOKEN_KEY));
		assertFalse(config.containsKey(DreamChasersClanConfig.SPLASH_OPTED_IN_KEY));
		assertFalse(EnableAll.isFeatureKey(ALL));
	}

	@Test
	public void tickOnUntickOffByHandUntickNoLoopSync()
	{
		run(new Settings(true));
	}

	@Test
	public void sameWhenConfigChangedComesLater()
	{
		run(new Settings(false));
	}

	private static void run(Settings s)
	{
		// tick: all seven on, and Connect is asked for once
		s.set(ALL, true);
		s.drain();
		assertTrue(s.allFeatures(true));
		assertEquals(1, s.connects);

		// one turned off by hand: "Enable everything" unticks, the other six stay on
		s.set(DreamChasersClanConfig.PBS_KEY, false);
		s.drain();
		assertFalse(s.get(ALL));
		assertFalse(s.get(DreamChasersClanConfig.PBS_KEY));
		assertEquals(6, EnableAll.FEATURES.stream().filter(f -> s.get(f.getKey())).count());

		// tick again: back to all on
		s.set(ALL, true);
		s.drain();
		assertTrue(s.allFeatures(true));

		// the member unticks it: all off
		s.set(ALL, false);
		s.drain();
		assertTrue(s.allFeatures(false));
		assertFalse(s.get(ALL));
		assertEquals(2, s.connects);
		assertTrue("a bounded number of events", s.events < 40);

		// turning all seven on by hand ticks it, once the last one goes on (not before)
		for (EnableAll.Feature f : EnableAll.FEATURES)
		{
			assertFalse("not while one is still off", s.get(ALL));
			s.set(f.getKey(), true);
			s.drain();
		}
		assertTrue(s.get(ALL));
		assertTrue(s.allFeatures(true));
		assertTrue("a bounded number of events", s.events < 60);

		// and turning one off again unticks it, leaving the others on
		s.set(DreamChasersClanConfig.LOOT_KEY, false);
		s.drain();
		assertFalse(s.get(ALL));
		assertEquals(6, EnableAll.FEATURES.stream().filter(f -> s.get(f.getKey())).count());
	}

	@Test
	public void allOnByHandWhileSignedOutDoesNotTickIt()
	{
		for (boolean sync : new boolean[]{true, false})
		{
			Settings s = new Settings(sync);
			s.signedIn = false;
			for (EnableAll.Feature f : EnableAll.FEATURES)
			{
				s.set(f.getKey(), true);
			}
			s.drain();
			assertFalse(s.get(ALL));
			assertTrue(s.allFeatures(true));
		}
	}

	@Test
	public void tickedWhileSignedOutKeepsFeaturesTickedByHand()
	{
		for (boolean sync : new boolean[]{true, false})
		{
			Settings s = new Settings(sync);
			s.signedIn = false;
			for (EnableAll.Feature f : EnableAll.FEATURES)
			{
				s.set(f.getKey(), true);
			}
			s.drain();
			s.set(ALL, true);
			s.drain();
			assertFalse("it unticks itself", s.get(ALL));
			assertTrue("and leaves the seven the member ticked on", s.allFeatures(true));
		}
	}

	@Test
	public void ignoredWhileSignedOut()
	{
		for (boolean sync : new boolean[]{true, false})
		{
			Settings s = new Settings(sync);
			s.signedIn = false;
			s.set(ALL, true);
			s.drain();
			assertFalse("it unticks itself", s.get(ALL));
			assertTrue("and turns nothing on", s.allFeatures(false));
			assertEquals(0, s.connects);
			s.signedIn = true;
			s.set(ALL, true);
			s.drain();
			assertTrue("once signed in it works", s.allFeatures(true));
		}
	}

	@Test
	public void everyFeatureDefaultsToOff()
	{
		DreamChasersClanConfig defaults = new DreamChasersClanConfig()
		{
		};
		assertFalse(defaults.enableEverything());
		assertFalse(defaults.connect());
		assertFalse(defaults.postPbs());
		assertFalse(defaults.postLoot());
		assertFalse(defaults.clanSplash());
		assertFalse(defaults.tilePacks());
		assertFalse(defaults.joinLive());
		assertFalse(defaults.shareRaidStats());
		assertEquals(7, EnableAll.FEATURES.size());
		assertTrue(EnableAll.isFeatureKey("postLoot"));
		assertFalse(EnableAll.isFeatureKey("showSidebar"));
	}
}
