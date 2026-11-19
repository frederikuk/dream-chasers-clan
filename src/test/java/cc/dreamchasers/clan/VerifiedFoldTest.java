/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import org.junit.Test;

/**
 * Verify for Discord folds once the logged-in name is verified. At the
 * LOGGED_IN event the game has no name yet, so the check waits for a tick
 * that has one (19 Nov 2026: the box stayed for a verified, signed-in member).
 */
public class VerifiedFoldTest
{
	private static class Scripted extends DreamChasersClanPlugin
	{
		final Deque<String> names = new ArrayDeque<>();
		final List<String> applied = new ArrayList<>();
		boolean in = true;

		@Override
		boolean loggedIn()
		{
			return in;
		}

		@Override
		String localName()
		{
			return names.isEmpty() ? null : names.poll();
		}

		@Override
		void showVerifiedAs(String match)
		{
			applied.add(match);
		}

		@Override
		void withRsn(java.util.function.Consumer<String> then)
		{
			then.accept(localName());     // the client thread, inline
		}

		void send(GameState state)
		{
			GameStateChanged e = new GameStateChanged();
			e.setGameState(state);
			onGameStateChanged(e);
		}
	}

	private static Scripted verifiedAs(String... names)
	{
		Scripted p = new Scripted();
		p.verifiedNames = List.of(names);
		return p;
	}

	@Test
	public void theFoldWaitsForATickThatHasTheName()
	{
		Scripted p = verifiedAs("4rederik", "Fred525");
		p.send(GameState.LOGGED_IN);     // a real login: the game has no name yet
		p.verifiedTick();     // two ticks with no name yet
		p.verifiedTick();
		assertEquals("nothing decided before the name is known", List.of(), p.applied);
		p.names.add("4rederik");
		p.verifiedTick();
		assertEquals(List.of("4rederik"), p.applied);
		p.names.add("4rederik");
		p.verifiedTick();
		assertEquals("checked once per login", 1, p.applied.size());
	}

	@Test
	public void loggedOutTicksWait()
	{
		Scripted p = verifiedAs("4rederik");
		p.in = false;
		p.verifiedNeeded();
		p.names.add("4rederik");
		p.verifiedTick();
		assertEquals(List.of(), p.applied);
		p.in = true;
		p.verifiedTick();
		assertEquals(List.of("4rederik"), p.applied);
	}

	@Test
	public void anotherAccountShowsVerify()
	{
		Scripted p = verifiedAs("4rederik");
		p.verifiedNeeded();
		p.names.add("Someone else");
		p.verifiedTick();
		assertEquals(Collections.singletonList(null), p.applied);
	}

	@Test
	public void aNullNameFromTheMeAnswerWaitsForATick()
	{
		Scripted p = verifiedAs("Fred525");
		p.applyVerified(null);     // the /me answer came before the game had the name
		assertEquals("the panel is left as it is", List.of(), p.applied);
		p.names.add("Fred525");
		p.verifiedTick();
		assertEquals(List.of("Fred525"), p.applied);
	}

	@Test
	public void aNameJustVerifiedFoldsAtOnce()
	{
		// 19 Nov 2026: after Sign in with Discord the menu still said "Connected as" and offered Verify again
		Scripted p = verifiedAs("Fred525");
		p.names.add("Tadeko");
		p.addVerified("Tadeko");
		assertEquals("no /me refresh needed", List.of("Tadeko"), p.applied);
		assertEquals(List.of("Fred525", "Tadeko"), p.verifiedNames);
	}

	@Test
	public void nothingDueNothingChecked()
	{
		Scripted p = new Scripted();
		p.names.add("4rederik");
		p.verifiedTick();
		assertEquals(List.of(), p.applied);
	}

	@Test
	public void matchesTheWayTheGameWritesNames()
	{
		List<String> verified = List.of("4rederik", "Fred525", "Frederi k");
		assertEquals("Frederi k", DreamChasersClanPlugin.verifiedMatch("Frederi k", verified));
		assertEquals("fred525", DreamChasersClanPlugin.verifiedMatch("fred525", verified));
		assertNull("another account", DreamChasersClanPlugin.verifiedMatch("Fred r k", verified));
		assertNull("no name yet", DreamChasersClanPlugin.verifiedMatch(null, verified));
	}
}
