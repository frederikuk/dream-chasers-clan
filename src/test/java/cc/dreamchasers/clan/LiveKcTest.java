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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import cc.dreamchasers.clan.api.ApiResult;
import cc.dreamchasers.clan.api.Competition;
import cc.dreamchasers.clan.api.CompetitionResponse;
import cc.dreamchasers.clan.api.DrmApiClient;
import cc.dreamchasers.clan.api.FakeHttp;
import cc.dreamchasers.clan.api.KcResponse;
import cc.dreamchasers.clan.pb.PbParser;
import com.google.gson.Gson;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;

public class LiveKcTest
{
	/** Holds each post until the test answers it. */
	private static final class Held implements LiveKc.Poster
	{
		final List<String> sent = new ArrayList<>();
		final List<Consumer<ApiResult<KcResponse>>> waiting = new ArrayList<>();

		@Override
		public void post(String boss, int kc, Consumer<ApiResult<KcResponse>> done)
		{
			sent.add(boss + "=" + kc);
			waiting.add(done);
		}

		void answer(int i, ApiResult.Status status)
		{
			waiting.get(i).accept(new ApiResult<>(status, status == ApiResult.Status.OK ? new KcResponse() : null,
				Instant.EPOCH, null, status == ApiResult.Status.OK ? 200 : 503));
		}
	}

	@Test
	public void killsWhileOneIsOutSendOnlyTheNewest()
	{
		LiveKc live = new LiveKc();
		Held h = new Held();
		live.offer("Vorkath", 301, h);
		live.offer("Vorkath", 302, h);
		live.offer("Vorkath", 303, h);
		assertEquals(Collections.singletonList("Vorkath=301"), h.sent);
		assertEquals(Integer.valueOf(303), live.waiting("Vorkath"));
		h.answer(0, ApiResult.Status.OK);
		assertEquals(Arrays.asList("Vorkath=301", "Vorkath=303"), h.sent);
		h.answer(1, ApiResult.Status.OK);
		assertNull(live.waiting("Vorkath"));
		assertFalse(live.sending("Vorkath"));
	}

	@Test
	public void eachBossHasItsOwnQueue()
	{
		LiveKc live = new LiveKc();
		Held h = new Held();
		live.offer("Vorkath", 301, h);
		live.offer("Zulrah", 40, h);
		assertEquals(Arrays.asList("Vorkath=301", "Zulrah=40"), h.sent);
	}

	@Test
	public void aFailedSendWaitsForTheNextKillAndIsNeverRetriedByItself()
	{
		LiveKc live = new LiveKc();
		Held h = new Held();
		live.offer("Vorkath", 301, h);
		h.answer(0, ApiResult.Status.ERROR);
		assertEquals("no retry of its own", 1, h.sent.size());
		assertEquals(Integer.valueOf(301), live.waiting("Vorkath"));
		live.offer("Vorkath", 302, h);
		assertEquals("the next kill sends the newest", "Vorkath=302", h.sent.get(1));
	}

	@Test
	public void aFailureWithANewerCountWaitingKeepsTheNewer()
	{
		LiveKc live = new LiveKc();
		Held h = new Held();
		live.offer("Vorkath", 301, h);
		live.offer("Vorkath", 302, h);
		h.answer(0, ApiResult.Status.ERROR);
		assertEquals(1, h.sent.size());
		assertEquals(Integer.valueOf(302), live.waiting("Vorkath"));
	}

	@Test
	public void aBusyAnswerAtOnceDoesNotLoop()
	{
		// the client's back-off answers BUSY on the calling thread
		LiveKc live = new LiveKc();
		int[] calls = {0};
		LiveKc.Poster busy = (boss, kc, done) ->
		{
			calls[0]++;
			done.accept(new ApiResult<>(ApiResult.Status.ERROR, null, null, DrmApiClient.BUSY));
		};
		live.offer("Vorkath", 301, busy);
		live.offer("Vorkath", 302, busy);
		assertEquals(2, calls[0]);
		assertEquals(Integer.valueOf(302), live.waiting("Vorkath"));
	}

	@Test
	public void aLogoutDropsWaitingCountsAndIgnoresLateAnswers()
	{
		LiveKc live = new LiveKc();
		Held h = new Held();
		live.offer("Vorkath", 301, h);
		live.offer("Vorkath", 302, h);
		live.reset();
		assertNull(live.waiting("Vorkath"));
		h.answer(0, ApiResult.Status.OK);
		assertEquals("the old account's count isn't sent", 1, h.sent.size());
		live.offer("Vorkath", 5, h);
		assertEquals("Vorkath=5", h.sent.get(1));
	}

	@Test
	public void theSiteNamesTheBosses()
	{
		CompetitionResponse r = new CompetitionResponse();
		assertTrue(LiveKc.bosses(null).isEmpty());
		assertTrue("an older site sends none", LiveKc.bosses(r).isEmpty());
		r.setKcBosses(Collections.singletonList("Vorkath"));
		Competition c = new Competition();
		c.setKcBosses(Collections.singletonList("Chambers of Xeric: Challenge Mode"));
		r.setCompetition(c);
		List<String> b = LiveKc.bosses(r);
		assertTrue(LiveKc.wanted(b, "vorkath "));
		assertTrue(LiveKc.wanted(b, "Chambers of Xeric: Challenge Mode"));
		assertFalse(LiveKc.wanted(b, "Chambers of Xeric"));
		assertFalse(LiveKc.wanted(b, null));
	}

	private static CompetitionResponse listing(String... bosses)
	{
		CompetitionResponse r = new CompetitionResponse();
		r.setKcBosses(Arrays.asList(bosses));
		return r;
	}

	@Test
	public void aKillWithNoCompetitionCopyIsKeptNotDropped()
	{
		// the copy is stale and its refresh is out: before the first good answer the check gets nothing
		LiveKc live = new LiveKc();
		Held h = new Held();
		int s = live.session();
		live.kill(s, null, "Vorkath", 301, h);
		live.kill(s, null, "Vorkath", 302, h);
		live.kill(s, null, "Vorkath", 300, h);
		assertTrue(h.sent.isEmpty());
		assertEquals("the highest count is kept", Integer.valueOf(302), live.unchecked("Vorkath"));
		live.checked(listing("Vorkath"));
		assertEquals(Collections.singletonList("Vorkath=302"), h.sent);
		assertNull(live.unchecked("Vorkath"));
	}

	@Test
	public void theNextKillsAnswerSendsTheKeptCountWithIt()
	{
		LiveKc live = new LiveKc();
		Held h = new Held();
		int s = live.session();
		live.kill(s, null, "Vorkath", 302, h);
		live.kill(s, null, "Zulrah", 40, h);
		live.kill(s, listing("Vorkath"), "Vorkath", 301, h);     // the first kill's older answer comes in last
		assertEquals("the kept, newer count goes; Zulrah isn't wanted", Collections.singletonList("Vorkath=302"), h.sent);
		assertNull("dropped once a real answer leaves it out", live.unchecked("Zulrah"));
	}

	@Test
	public void onlyARealAnswerSettlesAKeptCount()
	{
		LiveKc live = new LiveKc();
		Held h = new Held();
		int s = live.session();
		live.kill(s, null, "Vorkath", 302, h);
		live.checked(null);
		assertEquals(Integer.valueOf(302), live.unchecked("Vorkath"));
		live.checked(listing());
		assertNull("no competition: dropped", live.unchecked("Vorkath"));
		assertTrue(h.sent.isEmpty());
	}

	@Test
	public void aLogoutDropsKeptCountsAndLateChecks()
	{
		LiveKc live = new LiveKc();
		Held h = new Held();
		int s = live.session();
		live.kill(s, null, "Vorkath", 302, h);
		live.reset();
		assertNull(live.unchecked("Vorkath"));
		live.kill(s, null, "Vorkath", 303, h);
		live.kill(s, listing("Vorkath"), "Vorkath", 304, h);
		assertNull("a check from before the logout keeps nothing", live.unchecked("Vorkath"));
		assertTrue("and sends nothing", h.sent.isEmpty());
	}

	// ---- the plugin, against a fake site ----

	private static final String COMP = "{\"apiVersion\":1,\"status\":\"ongoing\",\"competition\":{\"id\":7,\"metric\":\"vorkath\"},"
		+ "\"kcBosses\":[\"Vorkath\"]}";

	private static void waitFor(FakeHttp http, int n) throws InterruptedException
	{
		for (int i = 0; i < 250 && http.requests.size() < n; i++)
		{
			Thread.sleep(20);
		}
		Thread.sleep(50);     // anything more would show up by now
	}

	private static DreamChasersClanPlugin plugin(DrmApiClient api, boolean main)
	{
		return new DreamChasersClanPlugin()
		{
			@Override
			DrmApiClient api()
			{
				return api;
			}

			@Override
			boolean mainGameWorld()
			{
				return main;
			}
		};
	}

	private static DreamChasersClanPlugin plugin(DrmApiClient api)
	{
		return plugin(api, true);
	}

	@Test
	public void aKillOnALeaguesOrDeadmanWorldSendsNothing() throws Exception
	{
		FakeHttp http = new FakeHttp();
		DrmApiClient api = new DrmApiClient(http.client, new Gson(), "https://dream-chasers.cc/api/v1", "UA", () -> "drm_pt_x",
			new FakeHttp.MutableClock(Instant.parse("2026-11-22T12:00:00Z")));
		http.reply(200, COMP, "public, max-age=300");
		plugin(api, false).liveKc(new PbParser.KillCount("Vorkath", 301), "Lomaru", "dig", 1);
		waitFor(http, 1);
		assertEquals("not even the competition check", 0, http.requests.size());
	}

	@Test
	public void aCountForACompetitionBossIsPostedWithTheCachedCompetition() throws Exception
	{
		FakeHttp http = new FakeHttp();
		DrmApiClient api = new DrmApiClient(http.client, new Gson(), "https://dream-chasers.cc/api/v1", "UA", () -> "drm_pt_x",
			new FakeHttp.MutableClock(Instant.parse("2026-11-22T12:00:00Z")));
		http.reply(200, COMP, "public, max-age=300");
		http.reply(200, "{\"ok\":true,\"stored\":true}", null);
		http.reply(200, "{\"ok\":true,\"stored\":false,\"reason\":\"lower\"}", null);
		DreamChasersClanPlugin p = plugin(api);

		p.liveKc(new PbParser.KillCount("Vorkath", 301), "Lomaru", "dig", 1);
		waitFor(http, 2);
		p.liveKc(new PbParser.KillCount("Vorkath", 302), "Lomaru", "dig", 1);
		waitFor(http, 3);
		p.liveKc(new PbParser.KillCount("Zulrah", 40), "Lomaru", "dig", 1);     // not in the competition
		waitFor(http, 4);

		assertEquals("one competition GET, then the cache; nothing for Zulrah", 3, http.requests.size());
		assertEquals("/api/v1/competition", http.requests.get(0).request.url().encodedPath());
		FakeHttp.Recorded post = http.requests.get(1);
		assertEquals("POST", post.request.method());
		assertEquals("https://dream-chasers.cc/api/v1/plugin/kc", post.request.url().toString());
		assertEquals("Bearer drm_pt_x", post.request.header("Authorization"));
		assertEquals("{\"rsn\":\"Lomaru\",\"digest\":\"dig\",\"boss\":\"Vorkath\",\"kc\":301,\"acct\":1}", post.body);
		assertTrue(http.requests.get(2).body.contains("\"kc\":302"));
	}

	@Test
	public void aKillBeforeTheFirstCompetitionCopyIsSentOnceOneComesIn() throws Exception
	{
		FakeHttp http = new FakeHttp();
		FakeHttp.MutableClock clock = new FakeHttp.MutableClock(Instant.parse("2026-11-22T12:00:00Z"));
		DrmApiClient api = new DrmApiClient(http.client, new Gson(), "https://dream-chasers.cc/api/v1", "UA", () -> "drm_pt_x",
			clock);
		http.reply(503, "{}", null);     // the first check fails, and there is no copy yet
		http.reply(200, COMP, "public, max-age=300");
		http.reply(200, "{\"ok\":true,\"stored\":true}", null);
		DreamChasersClanPlugin p = plugin(api);

		p.liveKc(new PbParser.KillCount("Vorkath", 301), "Lomaru", "dig", 1);
		waitFor(http, 1);
		assertEquals("nothing posted on no answer", 1, http.requests.size());
		clock.advanceSeconds(3600);     // past the back-off
		p.liveKc(new PbParser.KillCount("Zulrah", 40), "Lomaru", "dig", 1);     // a later kill's check lists Vorkath
		waitFor(http, 3);
		assertEquals(3, http.requests.size());
		assertEquals("{\"rsn\":\"Lomaru\",\"digest\":\"dig\",\"boss\":\"Vorkath\",\"kc\":301,\"acct\":1}",
			http.requests.get(2).body);
	}

	@Test
	public void noCompetitionSendsNothing() throws Exception
	{
		FakeHttp http = new FakeHttp();
		DrmApiClient api = new DrmApiClient(http.client, new Gson(), "https://dream-chasers.cc/api/v1", "UA", () -> "drm_pt_x",
			new FakeHttp.MutableClock(Instant.parse("2026-11-22T12:00:00Z")));
		http.reply(200, "{\"apiVersion\":1,\"status\":\"none\",\"competition\":null,\"kcBosses\":[]}", "public, max-age=300");
		DreamChasersClanPlugin p = plugin(api);
		p.liveKc(new PbParser.KillCount("Vorkath", 301), "Lomaru", "dig", 0);
		waitFor(http, 2);
		assertEquals(1, http.requests.size());
	}
}
