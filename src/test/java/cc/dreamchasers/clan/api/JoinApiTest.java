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
}
