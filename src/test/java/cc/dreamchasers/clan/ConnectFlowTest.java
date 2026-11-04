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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

/** Connect from the panel: start, open the browser, poll until allowed, denied or out of time. */
public class ConnectFlowTest
{
	private static final String START = "{\"apiVersion\":1,\"request\":\"abcdefghjkmnpqrstvwxyz23\","
		+ "\"pollKey\":\"k2345678901234567890123456789012\",\"code\":\"K7QX\","
		+ "\"url\":\"https://staging.dream-chasers.cc/plugin/link/abcdefghjkmnpqrstvwxyz23\","
		+ "\"expiresAt\":\"2026-11-01T20:10:00Z\",\"pollSeconds\":3}";
	private static final String PENDING = "{\"apiVersion\":1,\"status\":\"pending\"}";

	private final FakeHttp fake = new FakeHttp();
	private final FakeHttp.MutableClock clock = new FakeHttp.MutableClock(Instant.parse("2026-11-01T20:00:00Z"));
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	private final List<String> opened = new ArrayList<>();
	private final DrmApiClient api = new DrmApiClient(fake.client, new Gson(), "https://x/api/v1", "UA", () -> null, clock);

	@After
	public void stop()
	{
		executor.shutdownNow();
	}

	private static final class Outcome implements ConnectFlow.Listener
	{
		final List<String> codes = new ArrayList<>();
		final CompletableFuture<String> done = new CompletableFuture<>();

		@Override
		public void waiting(String code)
		{
			codes.add(code);
		}

		@Override
		public void connected(String token, String name)
		{
			done.complete("connected " + token + " " + name);
		}

		@Override
		public void failed(String message)
		{
			done.complete("failed " + message);
		}
	}

	private ConnectFlow flow(Duration maxWait)
	{
		return new ConnectFlow(api, executor, clock, opened::add, Duration.ofMillis(10), maxWait);
	}

	@Test
	public void allowedInTheBrowserGivesAToken() throws Exception
	{
		fake.reply(200, START, null).reply(200, PENDING, null).reply(200, PENDING, null)
			.reply(200, "{\"status\":\"allowed\",\"token\":\"drm_pt_abc\",\"name\":\"Maho z h\"}", null);
		Outcome o = new Outcome();
		flow(Duration.ofMinutes(10)).start("Maho z h", o);

		assertEquals("connected drm_pt_abc Maho z h", o.done.get(5, TimeUnit.SECONDS));
		assertEquals(List.of("K7QX"), o.codes);
		assertEquals(List.of("https://staging.dream-chasers.cc/plugin/link/abcdefghjkmnpqrstvwxyz23"), opened);

		FakeHttp.Recorded start = fake.requests.get(0);
		assertEquals("POST", start.request.method());
		assertTrue(start.request.url().toString().endsWith("/api/v1/plugin/link/start"));
		assertTrue(start.body.contains("\"rsn\":\"Maho z h\""));
		FakeHttp.Recorded poll = fake.requests.get(1);
		assertEquals("Poll k2345678901234567890123456789012", poll.request.header("Authorization"));
		assertTrue(poll.request.url().toString().endsWith("/plugin/link/poll?req=abcdefghjkmnpqrstvwxyz23"));
		assertEquals(4, fake.requests.size());
	}

	@Test
	public void denyStopsWithAPlainMessage() throws Exception
	{
		fake.reply(200, START, null).reply(200, "{\"status\":\"denied\"}", null);
		Outcome o = new Outcome();
		flow(Duration.ofMinutes(10)).start(null, o);
		assertEquals("failed You pressed Deny in the browser, so nothing was linked.", o.done.get(5, TimeUnit.SECONDS));
		assertFalse("no rsn at the login screen", fake.requests.get(0).body.contains("rsn"));
	}

	@Test
	public void expiryOnTheSiteIsReported() throws Exception
	{
		fake.reply(200, START, null)
			.reply(404, "{\"status\":\"expired\",\"error\":\"That request has expired. Press Connect again.\"}", null);
		Outcome o = new Outcome();
		flow(Duration.ofMinutes(10)).start(null, o);
		assertEquals("failed That request has expired. Press Connect again.", o.done.get(5, TimeUnit.SECONDS));
	}

	@Test
	public void networkBlipsKeepWaitingButTheDeadlineIsHard() throws Exception
	{
		fake.reply(200, START, null).fail().reply(200, PENDING, null);
		// Time jumps past 10 minutes the moment the third request (the pending poll)
		// has been made, so the next poll's deadline check fails whatever the thread
		// timing. A test that slept and then moved the clock let a fourth poll slip in.
		// Until then each reading moves 10 s on, so the client's back-off after the
		// blip (60 s) runs out well inside the deadline.
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
				int made;
				synchronized (fake)
				{
					made = fake.requests.size();
				}
				return made >= 3 ? t0.plusSeconds(10_000) : t0.plusSeconds(10 * ticks.incrementAndGet());
			}
		};
		Outcome o = new Outcome();
		DrmApiClient api = new DrmApiClient(fake.client, new Gson(), "https://x/api/v1", "UA", () -> null, jumping);
		ConnectFlow f = new ConnectFlow(api, executor, jumping, opened::add, Duration.ofMillis(10), Duration.ofMinutes(10));
		f.start(null, o);
		assertTrue(o.done.get(5, TimeUnit.SECONDS).startsWith("failed No answer from the browser within 10 minutes"));
		assertEquals("the blip, then one pending poll, then nothing after the deadline", 3, fake.requests.size());
		assertFalse(f.isWaiting());
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
		ConnectFlow f = flow(Duration.ofMinutes(10));
		f.start(null, o);
		for (int i = 0; i < 200 && fake.requests.size() < 3; i++)
		{
			TimeUnit.MILLISECONDS.sleep(10);
		}
		f.cancel();
		int after = fake.requests.size();
		TimeUnit.MILLISECONDS.sleep(100);
		assertTrue("at most one poll already in flight", fake.requests.size() <= after + 1);
		assertFalse(o.done.isDone());
		assertFalse(f.isWaiting());
	}

	@Test
	public void aStartLinkThatLooksWrongIsNotOpenedOrPolled() throws Exception
	{
		fake.reply(200, START.replace("https://staging.dream-chasers.cc/plugin/link/", "https://evil.example/login/"), null);
		List<String> tried = new ArrayList<>();
		Outcome o = new Outcome();
		new ConnectFlow(api, executor, clock, url ->
		{
			tried.add(url);
			return false;
		}, Duration.ofMillis(10), Duration.ofMinutes(10)).start(null, o);

		assertEquals("failed That link didn't look right. Try Connect again later.", o.done.get(5, TimeUnit.SECONDS));
		assertEquals(List.of("https://evil.example/login/abcdefghjkmnpqrstvwxyz23"), tried);
		assertTrue("no code card for a refused link", o.codes.isEmpty());
		TimeUnit.MILLISECONDS.sleep(50);
		assertEquals("no poll", 1, fake.requests.size());
	}

	@Test
	public void theSiteRefusingTheStartIsReported() throws Exception
	{
		fake.reply(429, "{\"error\":\"too many tries: wait 10 minutes\"}", null);
		Outcome o = new Outcome();
		flow(Duration.ofMinutes(10)).start(null, o);
		assertEquals("failed Too many tries: wait 10 minutes", o.done.get(5, TimeUnit.SECONDS));
		assertTrue(opened.isEmpty());
	}
}
