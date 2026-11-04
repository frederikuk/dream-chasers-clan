/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import lombok.Value;

/**
 * The "Enable everything" setting, in the plugin's settings menu.
 *
 * <p>Every setting still defaults to off, this one included; ticking it is the
 * member's explicit opt-in, with the standard IP warning. It only flips the
 * settings below: it never agrees to the stream consent (DRM Live keeps its
 * own Agree) and never sends a model (Feature me stays its own press).
 */
final class EnableAll
{
	@Value
	static class Feature
	{
		String key;
		/** As the settings panel names it. */
		String label;
	}

	/** Every server feature, in the order the setting's description lists them. Add new upload features here. */
	static final List<Feature> FEATURES = Collections.unmodifiableList(Arrays.asList(
		new Feature(DreamChasersClanConfig.CONNECT_KEY, "Connect to dream-chasers.cc"),
		new Feature(DreamChasersClanConfig.PBS_KEY, "Post personal bests"),
		new Feature(DreamChasersClanConfig.LOOT_KEY, "Post collection log"),
		new Feature(DreamChasersClanConfig.SPLASH_KEY, "Clan splash"),
		new Feature(DreamChasersClanConfig.TILES_KEY, "Clan tile packs"),
		new Feature(DreamChasersClanConfig.LIVE_KEY, "Join DRM Live"),
		new Feature(DreamChasersClanConfig.RAID_KEY, "Share raid stats on DRM Live")));

	private EnableAll()
	{
	}

	/** True when every feature is on. */
	static boolean allOn(Predicate<String> isOn)
	{
		return FEATURES.stream().allMatch(f -> isOn.test(f.getKey()));
	}

	/** Turns every feature on, or every one off. Touches nothing else (no token, consent or model). */
	static void setAll(BiConsumer<String, Boolean> set, boolean on)
	{
		for (Feature f : FEATURES)
		{
			set.accept(f.getKey(), on);
		}
	}

	static boolean isFeatureKey(String key)
	{
		return FEATURES.stream().anyMatch(f -> f.getKey().equals(key));
	}

	/**
	 * One settings change, as ConfigChanged reports it. Returns true when
	 * "Enable everything" was just ticked. It only flips settings: it never
	 * starts Connect or opens the browser (the panel's Connect card does that).
	 *
	 * <ul>
	 * <li>Ticked while not signed in: unticks itself and changes nothing else.</li>
	 * <li>Ticked: every feature on.</li>
	 * <li>Unticked while signed in and every feature is on (the member unticked it): every feature off.
	 * Not while signed out: then the untick is its own refusal above, and the features
	 * the member ticked by hand stay on.</li>
	 * <li>A feature turned off while it is ticked: untick it, and nothing else.
	 * That untick arrives here with a feature already off, so it switches
	 * nothing off: no loop, whether ConfigChanged is delivered at once or later.</li>
	 * <li>The last feature turned on by hand while signed in: tick it. That tick
	 * arrives here with every feature already on, so it sets nothing: no loop.</li>
	 * </ul>
	 */
	static boolean onChanged(String key, Predicate<String> isOn, BiConsumer<String, Boolean> set, boolean signedIn)
	{
		if (DreamChasersClanConfig.ENABLE_ALL_KEY.equals(key))
		{
			if (isOn.test(key) && !signedIn)
			{
				// Connecting comes first: until signed in, "Enable everything" does nothing.
				set.accept(key, false);
				return false;
			}
			if (isOn.test(key))
			{
				if (!allOn(isOn))
				{
					setAll(set, true);
				}
				return true;
			}
			if (allOn(isOn) && signedIn)
			{
				setAll(set, false);
			}
			return false;
		}
		if (isFeatureKey(key) && !isOn.test(key) && isOn.test(DreamChasersClanConfig.ENABLE_ALL_KEY))
		{
			set.accept(DreamChasersClanConfig.ENABLE_ALL_KEY, false);
		}
		else if (isFeatureKey(key) && isOn.test(key) && signedIn && !isOn.test(DreamChasersClanConfig.ENABLE_ALL_KEY) && allOn(isOn))
		{
			set.accept(DreamChasersClanConfig.ENABLE_ALL_KEY, true);
		}
		return false;
	}
}
