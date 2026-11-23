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
import cc.dreamchasers.clan.api.DrmApiClient;
import cc.dreamchasers.clan.api.FakeHttp;
import com.google.gson.Gson;
import java.time.Instant;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import org.junit.Test;

/** Live competition XP through the plugin: StatChanged, the game tick, {@code /plugin/xp} and the logout notice. */
public class LiveXpPluginTest
{
	private static final String ATTACK = "{\"apiVersion\":1,\"status\":\"ongoing\",\"competition\":{\"id\":8,\"metric\":\"attack\"},"
		+ "\"kcBosses\":[],\"jitLogout\":true,\"xpSkill\":\"attack\",\"xpEvery\":300}";
	private static final String SAILING = "{\"apiVersion\":1,\"status\":\"ongoing\",\"competition\":{\"id\":9,\"metric\":\"sailing\"},"
		+ "\"kcBosses\":[],\"jitLogout\":true,\"xpSkill\":\"sailing\",\"xpEvery\":300}";

	private static class Game extends DreamChasersClanPlugin
	{
		final DrmApiClient api;
		boolean on = true;
		boolean in;
		boolean main = true;
		long overall = 50_000_000L;
		Instant now = Instant.parse("2026-11-23T12:00:00Z");

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
		long overallXp()
		{
			return overall;
		}

		@Override
		LiveKc.Account liveAccountNow()
		{
			return new LiveKc.Account("Lomaru", "dig", 1);
		}

		@Override
		void verifiedNeeded()
		{
			// no game here
		}

		@Override
		void clientTicks()
		{
			liveAccountTick();
			liveXpTick();
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

		void login(Skill skill, int xp)
		{
			state(GameState.LOGGING_IN, GameState.LOGGED_IN);
			stat(skill, xp);     // the login burst
			onGameTick(new GameTick());
			onGameTick(new GameTick());
		}

		void stat(Skill skill, int xp)
		{
			onStatChanged(new StatChanged(skill, xp, 1, 1));
		}

		void tick()
		{
			onGameTick(new GameTick());
		}
	}

	private static DrmApiClient api(FakeHttp http)
	{
		return new DrmApiClient(http.client, new Gson(), "https://dream-chasers.cc/api/v1", "UA", () -> "drm_pt_x",
			new FakeHttp.MutableClock(Instant.parse("2026-11-23T12:00:00Z")));
	}

	private static void waitFor(FakeHttp http, int n) throws InterruptedException
	{
		for (int i = 0; i < 250 && http.requests.size() < n; i++)
		{
			Thread.sleep(20);
		}
		Thread.sleep(50);     // anything more would show up by now
	}

	private static void waitIdle(LiveXp x) throws InterruptedException
	{
		for (int i = 0; i < 250 && x.sending(); i++)
		{
			Thread.sleep(20);
		}
	}

	@Test
	public void aGainInTheCompetitionsSkillSendsItsXp() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, ATTACK, "public, max-age=300");
		http.reply(200, "{\"ok\":true,\"stored\":true}", null);
		Game p = new Game(api(http));
		p.login(Skill.ATTACK, 1_000_000);
		p.tick();
		waitFor(http, 1);
		assertEquals("the login burst alone sends nothing", 0, http.requests.size());

		p.stat(Skill.ATTACK, 1_000_350);
		p.tick();
		waitFor(http, 2);
		assertEquals(2, http.requests.size());
		assertEquals("/api/v1/competition", http.requests.get(0).request.url().encodedPath());
		FakeHttp.Recorded post = http.requests.get(1);
		assertEquals("POST", post.request.method());
		assertEquals("https://dream-chasers.cc/api/v1/plugin/xp", post.request.url().toString());
		assertEquals("Bearer drm_pt_x", post.request.header("Authorization"));
		assertEquals("{\"rsn\":\"Lomaru\",\"digest\":\"dig\",\"skill\":\"attack\",\"xp\":1000350,\"acct\":1}", post.body);

		waitIdle(p.liveXp());
		p.stat(Skill.ATTACK, 1_000_700);
		p.now = p.now.plusSeconds(299);
		p.tick();
		waitFor(http, 3);
		assertEquals("not before the interval", 2, http.requests.size());
	}

	@Test
	public void sailingIsReadLikeAnyOtherSkill() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, SAILING, "public, max-age=300");
		http.reply(200, "{\"ok\":true,\"stored\":true}", null);
		Game p = new Game(api(http));
		p.login(Skill.SAILING, 200_000);
		p.stat(Skill.SAILING, 200_120);
		p.tick();
		waitFor(http, 2);
		assertEquals(2, http.requests.size());
		assertEquals("{\"rsn\":\"Lomaru\",\"digest\":\"dig\",\"skill\":\"sailing\",\"xp\":200120,\"acct\":1}",
			http.requests.get(1).body);
	}

	@Test
	public void theLogoutNoticeCarriesTheXpEvenUnderTheCap() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, ATTACK, "public, max-age=300");
		http.reply(200, "{\"ok\":true,\"stored\":false,\"reason\":\"cap\",\"until\":\"2026-11-24T00:00:00Z\"}", null);
		http.reply(200, "{\"ok\":true}", null);
		Game p = new Game(api(http));
		p.login(Skill.ATTACK, 1_000_000);
		p.stat(Skill.ATTACK, 1_000_100);
		p.tick();
		waitFor(http, 2);
		waitIdle(p.liveXp());
		assertTrue(p.liveXp().capped(p.now));

		p.stat(Skill.ATTACK, 1_000_900);
		p.now = p.now.plusSeconds(3600);
		p.tick();
		waitFor(http, 3);
		assertEquals("capped: no timed send", 2, http.requests.size());

		p.state(GameState.LOGIN_SCREEN);
		waitFor(http, 3);
		assertEquals(3, http.requests.size());
		FakeHttp.Recorded notice = http.requests.get(2);
		assertEquals("https://dream-chasers.cc/api/v1/plugin/logout", notice.request.url().toString());
		assertEquals("{\"rsn\":\"Lomaru\",\"digest\":\"dig\",\"skill\":\"attack\",\"xp\":1000900,\"acct\":1}", notice.body);
	}

	@Test
	public void aHopWithTheNoticeSkippedStillSendsTheXp() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, ATTACK, "public, max-age=300");
		http.reply(200, "{\"ok\":true}", null);
		http.reply(200, "{\"ok\":true,\"stored\":true}", null);
		Game p = new Game(api(http));
		p.login(Skill.ATTACK, 1_000_000);
		p.state(GameState.HOPPING);     // a first hop: its notice carries no XP (attack hadn't moved)
		waitFor(http, 2);
		assertEquals(2, http.requests.size());

		p.login(Skill.ATTACK, 1_000_000);
		p.stat(Skill.ATTACK, 1_000_400);
		p.now = p.now.plusSeconds(60);
		p.state(GameState.HOPPING);     // within two minutes: no notice, the XP is unsent
		p.login(Skill.ATTACK, 1_000_400);
		p.now = p.now.plusSeconds(300);
		p.tick();
		waitFor(http, 3);
		assertEquals(3, http.requests.size());
		assertEquals("https://dream-chasers.cc/api/v1/plugin/xp", http.requests.get(2).request.url().toString());
		assertEquals("{\"rsn\":\"Lomaru\",\"digest\":\"dig\",\"skill\":\"attack\",\"xp\":1000400,\"acct\":1}",
			http.requests.get(2).body);
	}

	@Test
	public void aCompetitionHeardMidSessionSendsNoUnchangedXp() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, ATTACK, "public, max-age=300");
		Game p = new Game(api(http));
		p.login(Skill.ATTACK, 1_000_000);
		p.stat(Skill.MINING, 10_000);
		p.liveXp().heard(new Gson().fromJson(ATTACK, cc.dreamchasers.clan.api.CompetitionResponse.class));     // the panel's answer
		p.tick();
		p.now = p.now.plusSeconds(600);
		p.tick();
		waitFor(http, 1);
		assertEquals("attack never moved: no GET, no POST", 0, http.requests.size());
	}

	@Test
	public void theLogoutNoticeLeavesOutXpThatNeverMoved() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, ATTACK, "public, max-age=300");
		http.reply(200, "{\"ok\":true}", null);
		Game p = new Game(api(http));
		p.login(Skill.ATTACK, 1_000_000);
		p.state(GameState.LOGIN_SCREEN);
		waitFor(http, 2);
		assertEquals(2, http.requests.size());
		FakeHttp.Recorded notice = http.requests.get(1);
		assertEquals("https://dream-chasers.cc/api/v1/plugin/logout", notice.request.url().toString());
		assertEquals("{\"rsn\":\"Lomaru\",\"digest\":\"dig\",\"acct\":1}", notice.body);
	}

	@Test
	public void theLogoutNoticeLeavesOutXpTheSiteAlreadyHas() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, ATTACK, "public, max-age=300");
		http.reply(200, "{\"ok\":true,\"stored\":true}", null);
		http.reply(200, "{\"ok\":true}", null);
		Game p = new Game(api(http));
		p.login(Skill.ATTACK, 1_000_000);
		p.stat(Skill.ATTACK, 1_000_100);
		p.tick();
		waitFor(http, 2);
		waitIdle(p.liveXp());
		p.state(GameState.LOGIN_SCREEN);
		waitFor(http, 3);
		assertEquals(3, http.requests.size());
		assertEquals("{\"rsn\":\"Lomaru\",\"digest\":\"dig\",\"acct\":1}", http.requests.get(2).body);
	}

	@Test
	public void anotherSkillsGainSendsNoXp() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, ATTACK, "public, max-age=300");
		Game p = new Game(api(http));
		p.login(Skill.MINING, 10_000);
		p.stat(Skill.MINING, 10_500);     // no copy yet: the try reads one
		p.tick();
		waitFor(http, 1);
		waitIdle(p.liveXp());
		assertEquals("one GET, no POST: the competition is attack", 1, http.requests.size());

		p.stat(Skill.MINING, 11_000);
		p.now = p.now.plusSeconds(600);
		p.tick();
		waitFor(http, 2);
		assertEquals("the copy named attack: mining costs nothing", 1, http.requests.size());
	}

	@Test
	public void theToggleOffSendsNothing() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, ATTACK, "public, max-age=300");
		Game p = new Game(api(http));
		p.on = false;
		p.login(Skill.ATTACK, 1_000_000);
		p.stat(Skill.ATTACK, 1_000_500);
		p.tick();
		waitFor(http, 1);
		assertEquals(0, http.requests.size());
	}

	@Test
	public void aLeaguesOrDeadmanWorldSendsNothing() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, ATTACK, "public, max-age=300");
		Game p = new Game(api(http));
		p.main = false;
		p.login(Skill.ATTACK, 1_000_000);
		p.stat(Skill.ATTACK, 1_000_500);
		p.tick();
		waitFor(http, 1);
		assertEquals(0, http.requests.size());
	}

	@Test
	public void overallIsTheGamesTotalReadOnTheTick() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, ATTACK.replace("\"xpSkill\":\"attack\"", "\"xpSkill\":\"overall\""), "public, max-age=300");
		http.reply(200, "{\"ok\":true,\"stored\":true}", null);
		Game p = new Game(api(http));
		p.login(Skill.ATTACK, 1_000_000);
		p.tick();
		waitFor(http, 1);
		assertEquals("the total hasn't moved", 0, http.requests.size());
		p.overall += 250;
		p.tick();
		waitFor(http, 2);
		assertEquals(2, http.requests.size());
		assertEquals("{\"rsn\":\"Lomaru\",\"digest\":\"dig\",\"skill\":\"overall\",\"xp\":50000250,\"acct\":1}",
			http.requests.get(1).body);
	}
}
