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
import cc.dreamchasers.clan.api.ApiResult;
import cc.dreamchasers.clan.api.CompetitionResponse;
import cc.dreamchasers.clan.api.DrmApiClient;
import cc.dreamchasers.clan.api.FakeHttp;
import cc.dreamchasers.clan.pb.PbParser;
import com.google.gson.Gson;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import net.runelite.api.GameState;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import org.junit.Test;

/**
 * A real logout or a world hop sends one {@code POST /plugin/logout} while a
 * clan competition is running, so the site can ask Wise Old Man for the new
 * hiscores (D-221126-5 round 2).
 */
public class LiveLogoutTest
{
	private static final String COMP = "{\"apiVersion\":1,\"status\":\"ongoing\",\"competition\":{\"id\":7,\"metric\":\"vorkath\"},"
		+ "\"kcBosses\":[\"Vorkath\"]}";
	private static final String NONE = "{\"apiVersion\":1,\"status\":\"none\",\"competition\":null,\"kcBosses\":[]}";

	private static class Game extends DreamChasersClanPlugin
	{
		final DrmApiClient api;
		boolean on = true;
		boolean in;
		boolean main = true;
		Instant now = Instant.parse("2026-11-22T12:00:00Z");
		String name = "Lomaru";

		Game(DrmApiClient api)
		{
			this.api = api;
		}

		@Override
		DrmApiClient api()
		{
			return api;
		}

		@Override
		boolean liveKcOn()
		{
			return on;
		}

		@Override
		boolean loggedIn()
		{
			return in;
		}

		@Override
		boolean mainGameWorld()
		{
			return main;
		}

		@Override
		Instant liveNow()
		{
			return now;
		}

		@Override
		LiveKc.Account liveAccountNow()
		{
			return name == null ? null : new LiveKc.Account(name, "dig", 1);
		}

		@Override
		void verifiedNeeded()
		{
			// no game here
		}

		@Override
		void clientTicks()
		{
			liveAccountTick();     // the only game read under test
		}

		void state(GameState... states)
		{
			for (GameState s : states)
			{
				in = s == GameState.LOGGED_IN;
				GameStateChanged e = new GameStateChanged();
				e.setGameState(s);
				onGameStateChanged(e);
			}
		}

		void login()
		{
			state(GameState.LOGGING_IN, GameState.LOGGED_IN);
			onGameTick(new GameTick());
			onGameTick(new GameTick());
		}
	}

	private static DrmApiClient api(FakeHttp http)
	{
		return new DrmApiClient(http.client, new Gson(), "https://dream-chasers.cc/api/v1", "UA", () -> "drm_pt_x",
			new FakeHttp.MutableClock(Instant.parse("2026-11-22T12:00:00Z")));
	}

	private static void waitFor(FakeHttp http, int n) throws InterruptedException
	{
		for (int i = 0; i < 250 && http.requests.size() < n; i++)
		{
			Thread.sleep(20);
		}
		Thread.sleep(50);     // anything more would show up by now
	}

	@Test
	public void aLogoutDuringACompetitionSendsOneNotice() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, COMP, "public, max-age=300");
		http.reply(200, "{\"ok\":true}", null);
		Game p = new Game(api(http));
		p.login();
		p.state(GameState.LOGIN_SCREEN);
		waitFor(http, 2);
		assertEquals(2, http.requests.size());
		assertEquals("/api/v1/competition", http.requests.get(0).request.url().encodedPath());
		FakeHttp.Recorded post = http.requests.get(1);
		assertEquals("POST", post.request.method());
		assertEquals("https://dream-chasers.cc/api/v1/plugin/logout", post.request.url().toString());
		assertEquals("Bearer drm_pt_x", post.request.header("Authorization"));
		assertEquals("{\"rsn\":\"Lomaru\",\"digest\":\"dig\",\"acct\":1}", post.body);

		p.state(GameState.LOGGING_IN, GameState.LOGIN_SCREEN);     // a failed login back to the login screen
		waitFor(http, 3);
		assertEquals("once per logout: the account was forgotten", 2, http.requests.size());
	}

	@Test
	public void aHopSendsOneFromTheCachedCopyAndTheNextHopAnother() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, COMP, "public, max-age=300");
		http.reply(200, "{\"ok\":true}", null);
		http.reply(200, "{\"ok\":true}", null);
		Game p = new Game(api(http));
		p.login();
		p.state(GameState.HOPPING);
		waitFor(http, 2);
		p.state(GameState.LOADING, GameState.LOGGED_IN);
		p.onGameTick(new GameTick());
		p.onGameTick(new GameTick());
		p.now = p.now.plusSeconds(LiveKc.NOTICE_GAP_SECONDS);     // the gap has passed
		p.state(GameState.HOPPING);
		waitFor(http, 3);
		assertEquals("one competition GET, then the cached copy", 3, http.requests.size());
		assertTrue(http.requests.get(1).request.url().toString().endsWith("/plugin/logout"));
		assertTrue(http.requests.get(2).request.url().toString().endsWith("/plugin/logout"));
	}

	@Test
	public void noCompetitionSendsNoNotice() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, NONE, "public, max-age=300");
		Game p = new Game(api(http));
		p.login();
		p.state(GameState.LOGIN_SCREEN);
		waitFor(http, 2);
		assertEquals("only the competition check", 1, http.requests.size());
	}

	@Test
	public void theToggleOffSendsNothingAtAll() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, COMP, "public, max-age=300");
		Game p = new Game(api(http));
		p.on = false;
		p.login();
		p.state(GameState.LOGIN_SCREEN);
		waitFor(http, 1);
		assertEquals(0, http.requests.size());

		p.on = true;     // on again at the login screen: nobody was noted, so still nothing
		p.state(GameState.LOGIN_SCREEN);
		waitFor(http, 1);
		assertEquals(0, http.requests.size());
	}

	@Test
	public void turnedOffBeforeTheLogoutSendsNothing() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, COMP, "public, max-age=300");
		Game p = new Game(api(http));
		p.login();
		p.on = false;
		p.state(GameState.LOGIN_SCREEN);
		waitFor(http, 1);
		assertEquals(0, http.requests.size());
	}

	@Test
	public void aLogoutBeforeTheAccountIsKnownSendsNothing() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, COMP, "public, max-age=300");
		Game p = new Game(api(http));
		p.state(GameState.LOGGING_IN, GameState.LOGGED_IN);
		p.onGameTick(new GameTick());     // one tick: the account type isn't trusted yet
		p.state(GameState.LOGIN_SCREEN);
		waitFor(http, 1);
		assertEquals(0, http.requests.size());
	}

	@Test
	public void aFailedNoticeIsNeverRetried() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, COMP, "public, max-age=300");
		http.reply(503, "{}", null);
		Game p = new Game(api(http));
		p.login();
		p.state(GameState.LOGIN_SCREEN);
		waitFor(http, 3);
		assertEquals(2, http.requests.size());
	}

	@Test
	public void aHopWithinTwoMinutesOfTheLastNoticeSendsNothingMore() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, COMP, "public, max-age=300");
		http.reply(200, "{\"ok\":true}", null);
		Game p = new Game(api(http));
		p.login();
		p.state(GameState.HOPPING);
		waitFor(http, 2);
		p.state(GameState.LOADING, GameState.LOGGED_IN);
		p.onGameTick(new GameTick());
		p.onGameTick(new GameTick());
		p.now = p.now.plusSeconds(LiveKc.NOTICE_GAP_SECONDS - 1);
		p.state(GameState.HOPPING);
		waitFor(http, 3);
		assertEquals("no second notice and no GET for it", 2, http.requests.size());

		p.name = "Alt";     // another account isn't held back by the first one's notice
		p.state(GameState.LOADING, GameState.LOGGED_IN);
		p.onGameTick(new GameTick());
		p.onGameTick(new GameTick());
		http.reply(200, "{\"ok\":true}", null);
		p.state(GameState.HOPPING);
		waitFor(http, 3);
		assertEquals(3, http.requests.size());
		assertTrue(http.requests.get(2).body.contains("\"rsn\":\"Alt\""));
	}

	@Test
	public void aLeaguesOrDeadmanWorldSendsNothing() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, COMP, "public, max-age=300");
		Game p = new Game(api(http));
		p.main = false;
		p.login();
		p.state(GameState.HOPPING);     // a hop off a Leagues world: its hiscores aren't the main game's
		waitFor(http, 1);
		assertEquals(0, http.requests.size());

		p.state(GameState.LOADING, GameState.LOGGED_IN);
		p.onGameTick(new GameTick());
		p.onGameTick(new GameTick());
		p.main = true;     // the Leagues world noted nobody, so this logout has no account to send
		p.state(GameState.LOGIN_SCREEN);
		waitFor(http, 1);
		assertEquals(0, http.requests.size());
	}

	@Test
	public void aNoticeWithNoCopyWhileAFetchIsOutWaitsForThatAnswer() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, COMP, "public, max-age=300");
		http.reply(200, "{\"ok\":true}", null);
		DrmApiClient api = api(http);
		Game p = new Game(api);
		p.login();
		http.hold();
		p.liveKc(new PbParser.KillCount("Vorkath", 301), "Lomaru", "dig", 1);     // its competition GET is out
		waitFor(http, 1);
		p.state(GameState.LOGIN_SCREEN);     // no copy yet and a fetch out: held, not dropped
		assertEquals(1, http.requests.size());
		http.release();
		waitFor(http, 2);
		assertEquals("the GET, then the held notice (the kill belonged to the old session)", 2, http.requests.size());
		assertTrue(http.requests.get(1).request.url().toString().endsWith("/plugin/logout"));

		p.competitionLanded(new ApiResult<>(ApiResult.Status.OK,
			new Gson().fromJson(COMP, CompetitionResponse.class), Instant.now(), null));
		waitFor(http, 3);
		assertEquals("settled once", 2, http.requests.size());
	}

	@Test
	public void mainGameWorldsAreTheOnesWithoutASeparateHiscore()
	{
		assertTrue(DreamChasersClanPlugin.mainGame(null));
		assertTrue(DreamChasersClanPlugin.mainGame(EnumSet.noneOf(WorldType.class)));
		assertTrue(DreamChasersClanPlugin.mainGame(EnumSet.of(WorldType.MEMBERS, WorldType.PVP, WorldType.SKILL_TOTAL)));
		assertFalse(DreamChasersClanPlugin.mainGame(EnumSet.of(WorldType.MEMBERS, WorldType.SEASONAL)));
		assertFalse(DreamChasersClanPlugin.mainGame(EnumSet.of(WorldType.DEADMAN)));
		assertFalse(DreamChasersClanPlugin.mainGame(EnumSet.of(WorldType.FRESH_START_WORLD)));
		assertFalse(DreamChasersClanPlugin.mainGame(EnumSet.of(WorldType.TOURNAMENT_WORLD)));
		assertFalse(DreamChasersClanPlugin.mainGame(EnumSet.of(WorldType.BETA_WORLD)));
	}

	@Test
	public void runningNeedsABoss()
	{
		assertFalse(LiveKc.running(null));
		assertFalse(LiveKc.running(new Gson().fromJson(NONE, CompetitionResponse.class)));
		assertTrue(LiveKc.running(new Gson().fromJson(COMP, CompetitionResponse.class)));
	}

	@Test
	public void theBodyLeavesOutWhatIsUnknown()
	{
		assertEquals("{\"rsn\":\"Lomaru\"}", Uploads.logout("Lomaru", null, null).toString());
		assertEquals("{\"rsn\":\"Lomaru\",\"digest\":\"d\",\"acct\":0}", Uploads.logout("Lomaru", "d", 0).toString());
		assertEquals("{\"rsn\":\"Lomaru\"}", Uploads.logout("Lomaru", null, 9).toString());
		assertEquals(Collections.emptyList(), LiveKc.bosses(null));
	}
}
