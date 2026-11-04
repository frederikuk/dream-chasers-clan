/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan;

import static org.junit.Assert.assertEquals;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import org.junit.Test;

/** LOGGED_IN ends every loading screen too; only a real login (or hop) refreshes. */
public class LoginRefreshTest
{
	private static class Counting extends DreamChasersClanPlugin
	{
		int logins;

		@Override
		void onRealLogin()
		{
			logins++;
		}

		void send(GameState... states)
		{
			for (GameState s : states)
			{
				GameStateChanged e = new GameStateChanged();
				e.setGameState(s);
				onGameStateChanged(e);
			}
		}
	}

	@Test
	public void loginThroughALoadingScreenRefreshesOnce()
	{
		Counting p = new Counting();
		p.send(GameState.LOGIN_SCREEN, GameState.LOGGING_IN, GameState.LOADING, GameState.LOGGED_IN);
		assertEquals(1, p.logins);

		p.send(GameState.LOADING, GameState.LOGGED_IN);
		assertEquals("a teleport or region change", 1, p.logins);

		p.send(GameState.HOPPING, GameState.LOADING, GameState.LOGGED_IN);
		assertEquals("a world hop", 2, p.logins);

		p.send(GameState.CONNECTION_LOST, GameState.LOGGED_IN);
		assertEquals("a reconnect", 2, p.logins);
	}

	@Test
	public void theFirstLoggedInAfterStartingIsALogin()
	{
		Counting p = new Counting();
		p.send(GameState.LOGGED_IN, GameState.LOGGED_IN);
		assertEquals(1, p.logins);
	}

	@Test
	public void aDirectLoginStillCounts()
	{
		Counting p = new Counting();
		p.send(GameState.LOGGED_IN, GameState.LOGIN_SCREEN, GameState.LOGGING_IN, GameState.LOGGED_IN);
		assertEquals(2, p.logins);
	}
}
