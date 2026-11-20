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
import cc.dreamchasers.clan.api.MeResponse;
import com.google.gson.Gson;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;

/**
 * Sign-in is never held (Frederik, 20 Nov 2026: "Don't hold anything back"):
 * a held member is signed in and posts, and only Events waits until
 * {@code /plugin/me} says the sign-in is verified. No second Connect.
 */
public class HeldSignInTest
{
	private static final Gson GSON = new Gson();

	private static class Scripted extends DreamChasersClanPlugin
	{
		final List<String> shown = new ArrayList<>();

		@Override
		void showWaiting(String name)
		{
			shown.add("waiting " + name);
		}

		@Override
		void showNowVerified(String name)
		{
			shown.add("now verified " + name);
		}

		@Override
		void withRsn(Consumer<String> then)
		{
			// no game here: the fold isn't what this test looks at
		}
	}

	private static ApiResult<MeResponse> me(ApiResult.Status status, String json)
	{
		return new ApiResult<>(status, GSON.fromJson(json, MeResponse.class), Instant.EPOCH, null);
	}

	@Test
	public void aHeldSignInWaitsForEventsThenTurnsVerifiedByItself()
	{
		Scripted p = new Scripted();
		assertFalse(p.onMe(me(ApiResult.Status.OK, "{\"name\":\"Melk\",\"verified\":[],\"status\":\"held\"}")));
		assertTrue(p.signInUnverified);
		assertEquals(List.of("waiting Melk"), p.shown);

		assertFalse("the next ask, still held", p.onMe(me(ApiResult.Status.OK, "{\"name\":\"Melk\",\"status\":\"awaiting_roster\"}")));
		assertFalse("a cached copy changes nothing", p.onMe(me(ApiResult.Status.CACHED, "{\"name\":\"Melk\",\"verified\":[\"Melk\"]}")));
		assertFalse("a failed ask changes nothing", p.onMe(new ApiResult<>(ApiResult.Status.ERROR, null, null, "busy")));

		p.shown.clear();
		assertTrue("an officer approved: Events load", p.onMe(me(ApiResult.Status.OK, "{\"name\":\"Melk\",\"verified\":[\"Melk\"]}")));
		assertFalse(p.signInUnverified);
		assertEquals(List.of("waiting null", "now verified Melk"), p.shown);
		assertEquals(List.of("Melk"), p.verifiedNames);

		p.shown.clear();
		assertTrue(p.onMe(me(ApiResult.Status.OK, "{\"name\":\"Melk\",\"verified\":[\"Melk\"]}")));
		assertEquals("said once", List.of("waiting null"), p.shown);
	}

	@Test
	public void unverifiedTrueAndAnUnnamedTokenWaitToo()
	{
		Scripted p = new Scripted();
		assertFalse(p.onMe(me(ApiResult.Status.OK, "{\"unverified\":true}")));
		assertEquals("cleared by an officer: no name left", List.of("waiting "), p.shown);
	}

	@Test
	public void anOlderSitesAnswerIsAVerifiedSignIn()
	{
		// 0.5.4-era sites only ever handed out verified tokens, and their /me has no status
		Scripted p = new Scripted();
		assertTrue(p.onMe(me(ApiResult.Status.OK, "{\"apiVersion\":1,\"name\":\"Tadeko\",\"verified\":[\"Tadeko\"]}")));
		assertTrue(p.onMe(me(ApiResult.Status.OK, "{\"apiVersion\":1,\"name\":\"Tadeko\"}")));
		assertEquals(List.of("waiting null", "waiting null"), p.shown);
		assertTrue("nothing known yet: Events may load", new Scripted().onMe(new ApiResult<>(ApiResult.Status.ERROR, null, null, "busy")));
	}

	@Test
	public void meReadsTheWaitingStates()
	{
		assertTrue(GSON.fromJson("{\"status\":\"held\"}", MeResponse.class).notVerifiedYet());
		assertTrue(GSON.fromJson("{\"status\":\"awaiting_roster\"}", MeResponse.class).notVerifiedYet());
		assertTrue(GSON.fromJson("{\"unverified\":true}", MeResponse.class).notVerifiedYet());
		assertFalse(GSON.fromJson("{\"unverified\":false,\"status\":\"verified\"}", MeResponse.class).notVerifiedYet());
		assertFalse(GSON.fromJson("{}", MeResponse.class).notVerifiedYet());
	}
}
