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
import cc.dreamchasers.clan.api.DrmApiClient;
import cc.dreamchasers.clan.api.FakeHttp;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/** Verify in browser: gated on the own clan record, start, open the page, poll until the site's answer. */
public class VerifyFlowTest
{
	private static final long HASH = 4_242_424_242_424_242L;
	private static final OwnClan.Reading IN_CLAN = OwnClan.judge(true, "DRM Chasers", "Tadeko", true, HASH);
	private static final String START = "{\"apiVersion\":1,\"request\":\"abcdefghjkmnpqrstvwxyz23\","
		+ "\"pollKey\":\"k2345678901234567890123456789012\",\"code\":\"AB12\","
		+ "\"url\":\"/join/p/abcdefghjkmnpqrstvwxyz23\",\"expiresAt\":\"2026-11-07T20:10:00Z\",\"pollSeconds\":3}";
	private static final String PENDING = "{\"apiVersion\":1,\"status\":\"pending\"}";
	private static final String VERIFIED = "{\"apiVersion\":1,\"status\":\"verified\",\"rsn\":\"Tadeko\",\"discordName\":\"Maho\","
		+ "\"token\":\"drm_pt_abc\",\"name\":\"Tadeko\",\"expiresAt\":\"2027-11-07T20:00:00Z\"}";

	private final FakeHttp fake = new FakeHttp();
	private final FakeHttp.MutableClock clock = new FakeHttp.MutableClock(Instant.parse("2026-11-07T20:00:00Z"));
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	private final List<String> opened = new CopyOnWriteArrayList<>();
	private final AtomicReference<OwnClan.Reading> reading = new AtomicReference<>(IN_CLAN);
	private final DrmApiClient api = new DrmApiClient(fake.client, new Gson(), "https://staging.dream-chasers.cc/api/v1", "UA",
		() -> "drm_pt_old", clock);

	@After
	public void stop()
	{
		executor.shutdownNow();
	}

	private static class Outcome implements VerifyFlow.Listener
	{
		final List<String> codes = new CopyOnWriteArrayList<>();
		final List<String> nudges = new CopyOnWriteArrayList<>();
		final CompletableFuture<String> done = new CompletableFuture<>();

		@Override
		public void waiting(String code)
		{
			codes.add(code);
		}

		@Override
		public void stillWaiting(String code)
		{
			nudges.add(code);
		}

		@Override
		public void verified(String token, String name, String message)
		{
			done.complete("verified " + token + " " + name + " | " + message);
		}

		@Override
		public void finished(String message, boolean isError)
		{
			done.complete((isError ? "error " : "info ") + message);
		}
	}

	private VerifyFlow flow(Duration maxWait)
	{
		return new VerifyFlow(api, executor, clock, url ->
		{
			opened.add(url);
			return true;
		}, reading::get, "0.6.0", Duration.ofMillis(10), maxWait, Duration.ofMinutes(2));
	}

	private void waitFor(int requests) throws InterruptedException
	{
		for (int i = 0; i < 300 && size() < requests; i++)
		{
			TimeUnit.MILLISECONDS.sleep(10);
		}
	}

	private int size()
	{
		synchronized (fake)
		{
			return fake.requests.size();
		}
	}

	@Test
	public void notInTheClanSendsNothing() throws Exception
	{
		reading.set(OwnClan.judge(true, "Other Clan", "Tadeko", true, HASH));
		Outcome o = new Outcome();
		flow(Duration.ofMinutes(10)).start(o);
		assertEquals("error Your clan in game isn't DRM Chasers. Join DRM Chasers first.", o.done.get(1, TimeUnit.SECONDS));
		reading.set(OwnClan.Reading.LOGGED_OUT);
		Outcome o2 = new Outcome();
		flow(Duration.ofMinutes(10)).start(o2);
		assertEquals("error Log in to the game to verify.", o2.done.get(1, TimeUnit.SECONDS));
		assertTrue(fake.requests.isEmpty());
		assertTrue(opened.isEmpty());
	}

	@Test
	public void approvedInTheBrowserGivesAToken() throws Exception
	{
		fake.reply(200, START, null).reply(200, PENDING, null).reply(200, PENDING, null).reply(200, VERIFIED, null);
		Outcome o = new Outcome();
		VerifyFlow f = flow(Duration.ofMinutes(10));
		f.start(o);

		assertEquals("verified drm_pt_abc Tadeko | Verified: Tadeko is linked to the Discord account Maho. Your Discord role arrives "
			+ "within about a minute once you're in the Dream Chasers Discord. Not your Discord account? Press Sign out in the player menu (top right) and tell an officer.",
			o.done.get(5, TimeUnit.SECONDS));
		assertEquals(List.of("AB12"), o.codes);
		assertEquals("the site's relative link, on the API's host", List.of("https://staging.dream-chasers.cc/join/p/abcdefghjkmnpqrstvwxyz23"), opened);
		assertFalse(f.isWaiting());

		FakeHttp.Recorded start = fake.requests.get(0);
		assertEquals("POST", start.request.method());
		assertEquals("https://staging.dream-chasers.cc/api/join/start", start.request.url().toString());
		assertEquals("no token on the join routes", null, start.request.header("Authorization"));
		JsonObject body = new Gson().fromJson(start.body, JsonObject.class);
		Set<String> keys = new TreeSet<>(body.keySet());
		assertEquals("exactly these facts", new TreeSet<>(List.of("rsn", "digest", "clanName", "pluginVersion", "device")), keys);
		assertEquals("Tadeko", body.get("rsn").getAsString());
		assertEquals(OwnClan.digest(HASH), body.get("digest").getAsString());
		assertFalse("never the raw hash", start.body.contains(Long.toString(HASH)));
		assertEquals("DRM Chasers", body.get("clanName").getAsString());
		assertEquals("0.6.0", body.get("pluginVersion").getAsString());
		assertEquals("RuneLite", body.get("device").getAsString());

		FakeHttp.Recorded poll = fake.requests.get(1);
		assertEquals("GET", poll.request.method());
		assertEquals("https://staging.dream-chasers.cc/api/join/poll?req=abcdefghjkmnpqrstvwxyz23", poll.request.url().toString());
		assertEquals("Poll k2345678901234567890123456789012", poll.request.header("Authorization"));
		TimeUnit.MILLISECONDS.sleep(50);
		assertEquals("no poll after the answer", 4, size());
	}

	@Test
	public void aRenameSaysWhereTheLinkMoved() throws Exception
	{
		fake.reply(200, START, null).reply(200, VERIFIED.replace("}", ",\"renamedFrom\":\"Old Tadeko\"}"), null);
		Outcome o = new Outcome();
		flow(Duration.ofMinutes(10)).start(o);
		assertTrue(o.done.get(5, TimeUnit.SECONDS).contains("Your link moved from Old Tadeko to Tadeko."));
	}

	@Test
	public void heldAndAwaitingRosterShowTheSitesMessage() throws Exception
	{
		fake.reply(200, START, null)
			.reply(202, "{\"apiVersion\":1,\"status\":\"held\",\"reason\":\"rsn_taken\",\"message\":\"An officer will check this.\"}", null);
		Outcome o = new Outcome();
		flow(Duration.ofMinutes(10)).start(o);
		assertEquals("info An officer will check this.", o.done.get(5, TimeUnit.SECONDS));

		fake.reply(200, START, null)
			.reply(202, "{\"apiVersion\":1,\"status\":\"awaiting_roster\",\"reason\":\"not_on_roster\",\"message\":\"Not on the roster yet.\"}", null);
		Outcome o2 = new Outcome();
		flow(Duration.ofMinutes(10)).start(o2);
		assertEquals("info Not on the roster yet.", o2.done.get(5, TimeUnit.SECONDS));
	}

	@Test
	public void denyStopsWithAPlainMessage() throws Exception
	{
		fake.reply(200, START, null).reply(200, PENDING, null).reply(200, "{\"apiVersion\":1,\"status\":\"denied\"}", null);
		Outcome o = new Outcome();
		flow(Duration.ofMinutes(10)).start(o);
		assertEquals("error You pressed Deny in the browser, so nothing was linked.", o.done.get(5, TimeUnit.SECONDS));
	}

	@Test
	public void expiredAndUsedRequestsAreReported() throws Exception
	{
		fake.reply(200, START, null).reply(404, "{\"status\":\"expired\",\"error\":\"not found\"}", null);
		Outcome o = new Outcome();
		flow(Duration.ofMinutes(10)).start(o);
		assertEquals("error " + VerifyFlow.EXPIRED, o.done.get(5, TimeUnit.SECONDS));

		fake.reply(200, START, null).reply(409, "{\"error\":\"already collected\"}", null);
		Outcome o2 = new Outcome();
		flow(Duration.ofMinutes(10)).start(o2);
		assertEquals("error " + VerifyFlow.USED, o2.done.get(5, TimeUnit.SECONDS));
	}

	@Test
	public void aRefusedPollKeySaysToStartAgain() throws Exception
	{
		// a rotated site secret, say: never the site's bare "sign in"
		fake.reply(200, START, null).reply(401, "{\"error\":\"sign in\"}", null);
		Outcome o = new Outcome();
		flow(Duration.ofMinutes(10)).start(o);
		assertEquals("error " + VerifyFlow.EXPIRED, o.done.get(5, TimeUnit.SECONDS));
	}

	@Test
	public void aChangedAccountDiscardsTheToken() throws Exception
	{
		fake.reply(200, START, null).reply(200, PENDING, null).reply(200, VERIFIED, null);
		OwnClan.Reading other = OwnClan.judge(true, "DRM Chasers", "Someone Else", true, HASH + 1);
		// the start reads the record before any request; by the verified answer the player has switched account
		VerifyFlow f = new VerifyFlow(api, executor, clock, opened::add, () -> size() >= 2 ? other : IN_CLAN, "0.6.0",
			Duration.ofMillis(10), Duration.ofMinutes(10), Duration.ofMinutes(2));
		Outcome o = new Outcome();
		f.start(o);
		assertEquals("error " + VerifyFlow.CHANGED, o.done.get(5, TimeUnit.SECONDS));
		assertFalse(f.isWaiting());
		waitFor(4);
		assertEquals("the site is told to drop the token it handed over", "DELETE", fake.requests.get(3).request.method());
		assertEquals("Poll k2345678901234567890123456789012", fake.requests.get(3).request.header("Authorization"));
		assertEquals("the start went with the record it read", "Tadeko",
			new Gson().fromJson(fake.requests.get(0).body, JsonObject.class).get("rsn").getAsString());
	}

	@Test
	public void networkBlipsKeepWaitingButTheDeadlineIsHard() throws Exception
	{
		fake.reply(200, START, null).fail().reply(200, PENDING, null).reply(200, PENDING, null);
		// as in ConnectFlowTest: time jumps past 10 minutes once the third request has been made
		Instant t0 = clock.instant();
		java.util.concurrent.atomic.AtomicLong ticks = new java.util.concurrent.atomic.AtomicLong();
		java.time.Clock jumping = new java.time.Clock()
		{
			@Override
			public java.time.ZoneId getZone()
			{
				return java.time.ZoneOffset.UTC;
			}

			@Override
			public java.time.Clock withZone(java.time.ZoneId zone)
			{
				return this;
			}

			@Override
			public Instant instant()
			{
				return size() >= 3 ? t0.plusSeconds(10_000) : t0.plusSeconds(10 * ticks.incrementAndGet());
			}
		};
		DrmApiClient api = new DrmApiClient(fake.client, new Gson(), "https://x/api/v1", "UA", () -> null, jumping);
		VerifyFlow f = new VerifyFlow(api, executor, jumping, opened::add, reading::get, "0.6.0",
			Duration.ofMillis(10), Duration.ofMinutes(10), Duration.ofMinutes(2));
		Outcome o = new Outcome();
		f.start(o);
		assertEquals("error No answer from the browser within 10 minutes. Press Verify in browser to try again.",
			o.done.get(5, TimeUnit.SECONDS));
		waitFor(5);
		assertEquals("the blip, one pending poll, one last poll past the deadline, then the cancel", 5, size());
		assertEquals("GET", fake.requests.get(3).request.method());
		assertEquals("DELETE", fake.requests.get(4).request.method());
		assertFalse(f.isWaiting());
	}

	@Test
	public void aLongWaitNudgesOnce() throws Exception
	{
		fake.reply(200, START, null);
		for (int i = 0; i < 400; i++)
		{
			fake.reply(200, PENDING, null);
		}
		Outcome o = new Outcome();
		VerifyFlow f = flow(Duration.ofMinutes(10));
		f.start(o);
		waitFor(4);
		assertTrue("no nudge before two minutes", o.nudges.isEmpty());
		clock.advanceSeconds(121);
		for (int i = 0; i < 300 && o.nudges.isEmpty(); i++)
		{
			TimeUnit.MILLISECONDS.sleep(10);
		}
		int at = size();
		waitFor(at + 5);
		f.cancel();
		assertEquals(List.of("AB12"), o.nudges);
		assertFalse(o.done.isDone());
	}

	@Test
	public void cancelStopsPolling() throws Exception
	{
		fake.reply(200, START, null);
		for (int i = 0; i < 50; i++)
		{
			fake.reply(200, PENDING, null);
		}
		Outcome o = new Outcome();
		VerifyFlow f = flow(Duration.ofMinutes(10));
		f.start(o);
		waitFor(3);
		f.cancel();
		int after = polls();
		TimeUnit.MILLISECONDS.sleep(100);
		assertTrue("at most one poll already in flight", polls() <= after + 1);
		assertFalse(o.done.isDone());
		assertFalse(f.isWaiting());
		List<FakeHttp.Recorded> cancels = deletes();
		assertEquals("Cancel tells the site, so the page can't be approved any more", 1, cancels.size());
		assertEquals("https://staging.dream-chasers.cc/api/join/poll?req=abcdefghjkmnpqrstvwxyz23", cancels.get(0).request.url().toString());
		assertEquals("Poll k2345678901234567890123456789012", cancels.get(0).request.header("Authorization"));
	}

	private int polls()
	{
		synchronized (fake)
		{
			return (int) fake.requests.stream().filter(r -> r.request.method().equals("GET")).count();
		}
	}

	private List<FakeHttp.Recorded> deletes()
	{
		synchronized (fake)
		{
			List<FakeHttp.Recorded> out = new ArrayList<>();
			fake.requests.stream().filter(r -> r.request.method().equals("DELETE")).forEach(out::add);
			return out;
		}
	}

	/** A clock that moves 10 s each time it is read, so the client's 60 s back-off passes after a few polls. */
	private java.time.Clock ticking()
	{
		Instant t0 = clock.instant();
		java.util.concurrent.atomic.AtomicLong reads = new java.util.concurrent.atomic.AtomicLong();
		return new java.time.Clock()
		{
			@Override
			public java.time.ZoneId getZone()
			{
				return java.time.ZoneOffset.UTC;
			}

			@Override
			public java.time.Clock withZone(java.time.ZoneId zone)
			{
				return this;
			}

			@Override
			public Instant instant()
			{
				return t0.plusSeconds(10 * reads.incrementAndGet());
			}
		};
	}

	/** On the ticking clock, with a long enough wait that the back-off can't use it up. */
	private VerifyFlow tickingFlow()
	{
		java.time.Clock c = ticking();
		DrmApiClient a = new DrmApiClient(fake.client, new Gson(), "https://staging.dream-chasers.cc/api/v1", "UA", () -> null, c);
		return new VerifyFlow(a, executor, c, opened::add, reading::get, "0.6.0",
			Duration.ofMillis(10), Duration.ofHours(10), Duration.ofHours(5));
	}

	@Test
	public void aSiteHiccupMidWaitKeepsPolling() throws Exception
	{
		// a Gate restart (503), a failed mint (503), the Worker throwing (500), polling too fast (429)
		fake.reply(200, START, null).reply(200, PENDING, null)
			.reply(503, "{\"error\":\"The site can't take new requests right now. Try again in a few minutes.\"}", null)
			.reply(500, "{\"error\":\"internal\"}", null)
			.reply(429, "{\"error\":\"Polling too fast. Wait a few seconds.\"}", null)
			.reply(200, VERIFIED, null);
		Outcome o = new Outcome();
		VerifyFlow f = tickingFlow();
		f.start(o);
		String done = o.done.get(10, TimeUnit.SECONDS);
		assertTrue(done, done.startsWith("verified drm_pt_abc Tadeko | Verified: Tadeko is linked to the Discord account Maho."));
		assertEquals("no cancel after a token", 0, deletes().size());
	}

	@Test
	public void aLostCollectThenUsedSaysToVerifyAgain() throws Exception
	{
		// the token's answer was lost on the way, and the site's 60 s re-delivery has passed too
		fake.reply(200, START, null).reply(200, PENDING, null).fail().reply(409, "{\"error\":\"That request was already collected.\"}", null);
		Outcome o = new Outcome();
		tickingFlow().start(o);
		assertEquals("error " + VerifyFlow.USED, o.done.get(10, TimeUnit.SECONDS));
		assertTrue(VerifyFlow.USED.contains("press Verify in browser again to sign this PC in"));
	}

	@Test
	public void anApproveJustBeforeTheDeadlineIsCollected() throws Exception
	{
		fake.reply(200, START, null).reply(200, PENDING, null).reply(200, VERIFIED, null);
		// time jumps past 10 minutes once the pending poll has been made: the member approved in that last interval
		Instant t0 = clock.instant();
		java.time.Clock jumping = new java.time.Clock()
		{
			@Override
			public java.time.ZoneId getZone()
			{
				return java.time.ZoneOffset.UTC;
			}

			@Override
			public java.time.Clock withZone(java.time.ZoneId zone)
			{
				return this;
			}

			@Override
			public Instant instant()
			{
				return size() >= 2 ? t0.plusSeconds(10_000) : t0;
			}
		};
		DrmApiClient a = new DrmApiClient(fake.client, new Gson(), "https://x/api/v1", "UA", () -> null, jumping);
		VerifyFlow f = new VerifyFlow(a, executor, jumping, opened::add, reading::get, "0.6.0",
			Duration.ofMillis(10), Duration.ofMinutes(10), Duration.ofMinutes(2));
		Outcome o = new Outcome();
		f.start(o);
		assertTrue("one last poll past the deadline", o.done.get(5, TimeUnit.SECONDS).startsWith("verified drm_pt_abc"));
		assertEquals(3, size());
	}


	@Test
	public void aLinkOffTheSiteIsNotOpenedOrPolled() throws Exception
	{
		fake.reply(200, START.replace("/join/p/", "https://evil.example/join/p/"), null);
		List<String> tried = new ArrayList<>();
		Outcome o = new Outcome();
		new VerifyFlow(api, executor, clock, url ->
		{
			tried.add(url);
			return false;
		}, reading::get, "0.6.0", Duration.ofMillis(10), Duration.ofMinutes(10), Duration.ofMinutes(2)).start(o);
		assertEquals("error That link didn't look right. Try Verify in browser again later.", o.done.get(5, TimeUnit.SECONDS));
		assertEquals(List.of("https://evil.example/join/p/abcdefghjkmnpqrstvwxyz23"), tried);
		assertTrue(o.codes.isEmpty());
		TimeUnit.MILLISECONDS.sleep(50);
		assertEquals("no poll", 1, size());
	}

	@Test
	public void theSiteRefusingTheStartIsReported() throws Exception
	{
		fake.reply(429, "{\"error\":\"too many tries: wait 10 minutes\"}", null);
		Outcome o = new Outcome();
		flow(Duration.ofMinutes(10)).start(o);
		assertEquals("error Too many tries: wait 10 minutes", o.done.get(5, TimeUnit.SECONDS));
		assertTrue(opened.isEmpty());
	}
}
