/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Verified join: the site's reply shapes, and the client's handling of them. */
public class JoinApiTest
{
	private final Gson gson = new Gson();
	private final FakeHttp fake = new FakeHttp();
	private final FakeHttp.MutableClock clock = new FakeHttp.MutableClock(Instant.parse("2026-11-03T20:00:00Z"));

	@Test
	public void joinCodesAreTidiedLikeTheSiteDoes()
	{
		assertEquals("ABCD-2345", DrmApiClient.normalizeJoinCode("abcd-2345"));
		assertEquals("ABCD-2345", DrmApiClient.normalizeJoinCode("ABCD2345"));
		assertEquals("ABCD-2345", DrmApiClient.normalizeJoinCode(" abcd 2345 "));
		assertEquals("ABCD-2345", DrmApiClient.normalizeJoinCode("ab\u00A0cd-23 45"));
		assertEquals("ABC", DrmApiClient.normalizeJoinCode("abc"));
		assertEquals("", DrmApiClient.normalizeJoinCode(null));
	}

	@Test
	public void peekParses()
	{
		JoinPeekResponse p = gson.fromJson("{\"apiVersion\":1,\"discordName\":\"Maho\",\"expiresAt\":\"2026-11-03T20:10:00.000Z\"}",
			JoinPeekResponse.class);
		assertEquals(1, p.getApiVersion());
		assertEquals("Maho", p.getDiscordName());
		assertEquals("2026-11-03T20:10:00.000Z", p.getExpiresAt());
	}

	@Test
	public void attestVerifiedParses()
	{
		JoinAttestResponse r = gson.fromJson("{\"apiVersion\":1,\"status\":\"verified\",\"rsn\":\"Tadeko\",\"discordName\":\"Maho\","
			+ "\"token\":\"drm_pt_x\",\"name\":\"Tadeko\",\"expiresAt\":\"2027-01-01T00:00:00Z\",\"renamedFrom\":\"Old\"}", JoinAttestResponse.class);
		assertEquals(JoinAttestResponse.VERIFIED, r.getStatus());
		assertEquals("Tadeko", r.getRsn());
		assertEquals("Maho", r.getDiscordName());
		assertEquals("drm_pt_x", r.getToken());
		assertEquals("Tadeko", r.getName());
		assertEquals("2027-01-01T00:00:00Z", r.getExpiresAt());
		assertEquals("Old", r.getRenamedFrom());
	}

	@Test
	public void attestHeldParses()
	{
		JoinAttestResponse r = gson.fromJson("{\"apiVersion\":1,\"status\":\"held\",\"reason\":\"x\",\"message\":\"m\"}",
			JoinAttestResponse.class);
		assertEquals(JoinAttestResponse.HELD, r.getStatus());
		assertEquals("x", r.getReason());
		assertEquals("m", r.getMessage());
		assertNull(r.getToken());
	}

	@Test
	public void a202IsAnAnswerNotAFailure() throws Exception
	{
		fake.reply(202, "{\"apiVersion\":1,\"status\":\"awaiting_roster\",\"message\":\"Not on the roster yet.\"}", null);
		DrmApiClient api = new DrmApiClient(fake.client, gson, "https://x/api/v1", "UA", () -> null, clock);
		CompletableFuture<ApiResult<JoinAttestResponse>> got = new CompletableFuture<>();
		api.joinAttest("ABCD2345", "Tadeko", "ab", "DRM Chasers", "0.5.0", got::complete);
		ApiResult<JoinAttestResponse> r = got.get(5, TimeUnit.SECONDS);
		assertEquals(ApiResult.Status.OK, r.getStatus());
		assertEquals(202, r.getCode());
		assertEquals(JoinAttestResponse.AWAITING_ROSTER, r.getValue().getStatus());
	}

	@Test
	public void aJoinFloodBacksTheRouteOff() throws Exception
	{
		fake.reply(429, "{\"error\":\"Too many tries. Wait 10 minutes.\"}", "Retry-After", "600");
		DrmApiClient api = new DrmApiClient(fake.client, gson, "https://x/api/v1", "UA", () -> null, clock);
		CompletableFuture<ApiResult<JoinPeekResponse>> first = new CompletableFuture<>();
		api.joinPeek("ABCD2345", first::complete);
		assertEquals("Too many tries. Wait 10 minutes.", first.get(5, TimeUnit.SECONDS).getMessage());
		CompletableFuture<ApiResult<JoinPeekResponse>> second = new CompletableFuture<>();
		api.joinPeek("ABCD2345", second::complete);
		assertEquals(DrmApiClient.BUSY, second.get(5, TimeUnit.SECONDS).getMessage());
		assertEquals(1, fake.requests.size());
	}

	@Test
	public void pollAnswersParse()
	{
		assertEquals(JoinAttestResponse.PENDING, gson.fromJson("{\"apiVersion\":1,\"status\":\"pending\"}", JoinAttestResponse.class).getStatus());
		assertEquals(JoinAttestResponse.DENIED, gson.fromJson("{\"apiVersion\":1,\"status\":\"denied\"}", JoinAttestResponse.class).getStatus());
	}

	@Test
	public void startLinksAreResolvedOnTheApisHost()
	{
		DrmApiClient api = new DrmApiClient(fake.client, gson, "https://staging.dream-chasers.cc/api/v1", "UA", () -> null, clock);
		assertEquals("https://staging.dream-chasers.cc/join/p/abc", api.siteUrl("/join/p/abc"));
		assertEquals("absolute links stay as they are (SafeLinks judges them)", "https://evil.example/x", api.siteUrl("https://evil.example/x"));
		DrmApiClient local = new DrmApiClient(fake.client, gson, "http://127.0.0.1:8787/api/v1", "UA", () -> null, clock);
		assertEquals("http://127.0.0.1:8787/join/p/abc", local.siteUrl("/join/p/abc"));
	}

	@Test
	public void startWithoutTheClanFactsSendsNothing() throws Exception
	{
		DrmApiClient api = new DrmApiClient(fake.client, gson, "https://x/api/v1", "UA", () -> null, clock);
		CompletableFuture<ApiResult<LinkStartResponse>> got = new CompletableFuture<>();
		api.joinStart("Tadeko", null, "DRM Chasers", "0.6.0", got::complete);
		assertEquals(ApiResult.Status.ERROR, got.get(5, TimeUnit.SECONDS).getStatus());
		assertTrue(fake.requests.isEmpty());
	}

	@Test
	public void startResolvesItsLinkAndPollSendsTheKey() throws Exception
	{
		fake.reply(200, "{\"apiVersion\":1,\"request\":\"r1\",\"pollKey\":\"pk\",\"code\":\"AB12\",\"url\":\"/join/p/r1\","
			+ "\"expiresAt\":\"2026-11-07T20:10:00Z\",\"pollSeconds\":3}", null)
			.reply(200, "{\"apiVersion\":1,\"status\":\"pending\"}", null);
		DrmApiClient api = new DrmApiClient(fake.client, gson, "https://x/api/v1", "UA", () -> "drm_pt_secret", clock);
		CompletableFuture<ApiResult<LinkStartResponse>> started = new CompletableFuture<>();
		api.joinStart("Tadeko", "ab", "DRM Chasers", "0.6.0", started::complete);
		LinkStartResponse s = started.get(5, TimeUnit.SECONDS).getValue();
		assertEquals("https://x/join/p/r1", s.getUrl());
		assertEquals("AB12", s.getCode());
		assertEquals("https://x/api/join/start", fake.requests.get(0).request.url().toString());

		CompletableFuture<ApiResult<JoinAttestResponse>> polled = new CompletableFuture<>();
		api.joinPoll(s, polled::complete);
		assertEquals(JoinAttestResponse.PENDING, polled.get(5, TimeUnit.SECONDS).getValue().getStatus());
		FakeHttp.Recorded poll = fake.requests.get(1);
		assertEquals("https://x/api/join/poll?req=r1", poll.request.url().toString());
		assertEquals("the poll key, never the sign-in token", "Poll pk", poll.request.header("Authorization"));
	}

	@Test
	public void cancelSendsTheKeyEvenWhileThePollIsBackingOff() throws Exception
	{
		fake.reply(503, "{\"error\":\"down\"}", null).reply(200, "{\"apiVersion\":1,\"status\":\"cancelled\"}", null)
			.reply(401, "{\"error\":\"no\"}", null);
		DrmApiClient api = new DrmApiClient(fake.client, gson, "https://x/api/v1", "UA", () -> "drm_pt_secret", clock);
		LinkStartResponse s = gson.fromJson("{\"request\":\"r1\",\"pollKey\":\"pk\",\"url\":\"https://x/join/p/r1\"}", LinkStartResponse.class);
		CompletableFuture<ApiResult<JoinAttestResponse>> polled = new CompletableFuture<>();
		api.joinPoll(s, polled::complete);
		assertEquals("a 503 keeps its code, for the flow's blip check", 503, polled.get(5, TimeUnit.SECONDS).getCode());
		CompletableFuture<ApiResult<JoinAttestResponse>> busy = new CompletableFuture<>();
		api.joinPoll(s, busy::complete);
		assertEquals("the poll route backs off", DrmApiClient.BUSY, busy.get(5, TimeUnit.SECONDS).getMessage());

		CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
		api.joinCancel(s, cancelled::complete);
		assertTrue(cancelled.get(5, TimeUnit.SECONDS));
		FakeHttp.Recorded del = fake.requests.get(1);
		assertEquals("DELETE", del.request.method());
		assertEquals("https://x/api/join/poll?req=r1", del.request.url().toString());
		assertEquals("Poll pk", del.request.header("Authorization"));
		CompletableFuture<Boolean> refused = new CompletableFuture<>();
		api.joinCancel(s, refused::complete);
		assertEquals(false, refused.get(5, TimeUnit.SECONDS));
		CompletableFuture<Boolean> none = new CompletableFuture<>();
		api.joinCancel(null, none::complete);
		assertEquals(false, none.get(5, TimeUnit.SECONDS));
		assertEquals(3, fake.requests.size());
	}
}
