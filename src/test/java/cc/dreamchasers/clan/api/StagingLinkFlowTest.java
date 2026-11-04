/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import com.google.gson.Gson;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;
import org.junit.Test;

/**
 * The sign-in flow against a real site. Skipped unless both are given:
 * <pre>
 * gradlew test --tests '*StagingLinkFlowTest' -PdrmclanItBase=https://staging.dream-chasers.cc/api/v1 -PdrmclanItCode=DRM-XXXX-XXXX
 * </pre>
 * The code is used up by the test. Never point this at the live site with a
 * real member's code.
 */
public class StagingLinkFlowTest
{
	private static final String UA = "DreamChasersClanPlugin/0.1.0-it (+https://dream-chasers.cc; Discord: frederikuk)";
	private final OkHttpClient http = new OkHttpClient();
	private final Gson gson = new Gson();

	private static <T> T await(Consumer<Consumer<T>> call) throws Exception
	{
		CompletableFuture<T> f = new CompletableFuture<>();
		call.accept(f::complete);
		return f.get(20, TimeUnit.SECONDS);
	}

	private DrmApiClient client(String base, String token)
	{
		return new DrmApiClient(http, gson, base, UA, () -> token, Clock.systemUTC());
	}

	@Test
	public void linkMeEventsReuseUnlink() throws Exception
	{
		String base = System.getProperty("drmclan.it.base");
		String code = System.getProperty("drmclan.it.code");
		assumeTrue("set -PdrmclanItBase and -PdrmclanItCode to run", base != null && code != null);

		// public routes parse
		DrmApiClient anon = client(base, null);
		assertTrue(await((Consumer<ApiResult<CompetitionResponse>> cb) -> anon.fetch(Endpoint.COMPETITION, false, cb)).isFresh());
		assertTrue(await((Consumer<ApiResult<HallOfFameResponse>> cb) -> anon.fetch(Endpoint.HALL_OF_FAME, false, cb)).isFresh());
		assertTrue(await((Consumer<ApiResult<LinksResponse>> cb) -> anon.fetch(Endpoint.LINKS, false, cb)).isFresh());

		// a made-up token is turned away
		DrmApiClient bogus = client(base, "drm_pt_not-a-real-token");
		assertEquals(ApiResult.Status.UNAUTHORIZED,
			await((Consumer<ApiResult<EventsResponse>> cb) -> bogus.fetch(Endpoint.EVENTS, false, cb)).getStatus());

		// link
		ApiResult<LinkResponse> linked = await(cb -> anon.link(code, "RuneLite", cb));
		assertEquals(linked.getMessage(), ApiResult.Status.OK, linked.getStatus());
		String token = linked.getValue().getToken();
		assertTrue(token.startsWith("drm_pt_"));
		System.out.println("linked as " + linked.getValue().getName() + ", expires " + linked.getValue().getExpiresAt());

		// authenticated reads
		DrmApiClient member = client(base, token);
		ApiResult<MeResponse> me = await(cb -> member.fetch(Endpoint.ME, false, cb));
		assertEquals(me.getMessage(), ApiResult.Status.OK, me.getStatus());
		assertEquals(linked.getValue().getName(), me.getValue().getName());
		ApiResult<EventsResponse> events = await(cb -> member.fetch(Endpoint.EVENTS, false, cb));
		assertEquals(events.getMessage(), ApiResult.Status.OK, events.getStatus());
		assertNotNull(events.getValue().getEvents());
		System.out.println("events: " + events.getValue().getEvents().size());

		// the code works once
		ApiResult<LinkResponse> again = await(cb -> anon.link(code, "RuneLite", cb));
		assertEquals(ApiResult.Status.ERROR, again.getStatus());
		assertEquals("Invalid or expired code", again.getMessage());

		// sign out revokes the token at the site
		member.unlink();
		ApiResult.Status after = null;
		for (int i = 0; i < 20 && after != ApiResult.Status.UNAUTHORIZED; i++)
		{
			Thread.sleep(500);
			DrmApiClient check = client(base, token);
			after = await((Consumer<ApiResult<MeResponse>> cb) -> check.fetch(Endpoint.ME, false, cb)).getStatus();
		}
		assertEquals(ApiResult.Status.UNAUTHORIZED, after);
		assertFalse(token.isEmpty());
	}
}
