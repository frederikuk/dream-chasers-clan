/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import cc.dreamchasers.clan.pb.PbRecord;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import org.junit.Test;

/**
 * The old-PB batch carries the player's own account type: it waits for the
 * tick the account-type varbit is trusted on, instead of going on the first
 * tick after a login without it (22 Nov 2026 review).
 */
public class BackfillAcctTest
{
	private static class Ticking extends DreamChasersClanPlugin
	{
		final List<JsonObject> batches = new ArrayList<>();
		boolean ready = true;

		@Override
		boolean backfillOn()
		{
			return true;
		}

		@Override
		boolean loggedIn()
		{
			return true;
		}

		@Override
		int accountTypeVarbit()
		{
			return 1;     // ironman
		}

		@Override
		boolean sendBackfill(Integer acct)
		{
			if (!ready)
			{
				return false;
			}
			PbRecord r = new PbRecord("Vorkath", 75.0, "1", null, false);
			batches.add(Uploads.withAcct(Uploads.batch(Collections.singletonList(r), "Lomaru", "dig"), acct));
			return true;
		}

		@Override
		void verifiedNeeded()
		{
			// no game here
		}

		@Override
		void clientTicks()
		{
			backfillTick();     // the only game read under test
		}

		void state(GameState... states)
		{
			for (GameState s : states)
			{
				GameStateChanged e = new GameStateChanged();
				e.setGameState(s);
				onGameStateChanged(e);
			}
		}

		void tick()
		{
			onGameTick(new GameTick());
		}
	}

	@Test
	public void theBatchWaitsForTheAccountTypeAndCarriesIt()
	{
		Ticking p = new Ticking();
		p.state(GameState.LOGGING_IN, GameState.LOGGED_IN);
		p.tick();
		assertEquals("not on the first tick: the varbit isn't trusted yet", 0, p.batches.size());
		p.tick();
		assertEquals(1, p.batches.size());
		assertEquals(1, p.batches.get(0).get("acct").getAsInt());
		p.tick();
		assertEquals("once a login", 1, p.batches.size());
	}

	@Test
	public void aHopWaitsAgain()
	{
		Ticking p = new Ticking();
		p.state(GameState.LOGGING_IN, GameState.LOGGED_IN);
		p.tick();
		p.tick();
		p.state(GameState.HOPPING, GameState.LOADING, GameState.LOGGED_IN);
		p.tick();
		assertEquals("the hop's batch waits a tick too", 1, p.batches.size());
		p.tick();
		assertEquals(2, p.batches.size());
		assertTrue(p.batches.get(1).has("acct"));
	}

	@Test
	public void aClientNotReadyYetTriesAgainNextTick()
	{
		Ticking p = new Ticking();
		p.ready = false;
		p.state(GameState.LOGGING_IN, GameState.LOGGED_IN);
		p.tick();
		p.tick();
		assertEquals(0, p.batches.size());
		p.ready = true;
		p.tick();
		assertEquals(1, p.batches.size());
		assertEquals(1, p.batches.get(0).get("acct").getAsInt());
	}
}
