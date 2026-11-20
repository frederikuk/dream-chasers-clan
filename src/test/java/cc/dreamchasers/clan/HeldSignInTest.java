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
		void showNotVerified(String message)
		{
			shown.add("not verified " + message);
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
		assertFalse(p.onMe(me(ApiResult.Status.OK, "{\"name\":\"Melk\",\"verified\":null,\"unverified\":true,\"status\":\"held\",\"rsn\":\"Melk\"}")));
		assertTrue(p.signInUnverified);
		assertEquals(List.of("waiting Melk"), p.shown);

		assertFalse("the next ask, still held", p.onMe(me(ApiResult.Status.OK, "{\"name\":\"Melk\",\"unverified\":true,\"status\":\"awaiting_roster\",\"rsn\":\"Melk\"}")));
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
	public void aHeldAnswerWithNoNameWaitsOnYourName()
	{
		Scripted p = new Scripted();
		assertFalse(p.onMe(me(ApiResult.Status.OK, "{\"status\":\"held\"}")));
		assertEquals(List.of("waiting "), p.shown);
	}

	@Test
	public void aClearedRequestSaysAskAgainNotWaiting()
	{
		// the site's real shape after an officer's Clear (or the name verified to someone else):
		// the token's name stays, but there's no rsn, so nothing is with the officers
		Scripted p = new Scripted();
		p.beginSignIn(false);
		String cleared = "This sign-in isn't verified to a game name. Press Connect in the Dream Chasers panel to ask again.";
		assertFalse("Events still wait", p.onMe(me(ApiResult.Status.OK, "{\"name\":\"Tadeko\",\"verified\":null,\"unverified\":true,"
			+ "\"status\":\"unverified\",\"message\":\"" + cleared + "\"}")));
		assertTrue(p.signInUnverified);
		assertEquals("never 'waiting Tadeko'", List.of("not verified " + cleared), p.shown);

		p.shown.clear();
		assertFalse(p.onMe(me(ApiResult.Status.OK, "{\"unverified\":true}")));
		assertEquals("no line from the site: the plugin's own", List.of("not verified " + DreamChasersClanPlugin.NOT_VERIFIED), p.shown);

		p.shown.clear();
		assertFalse("asked again: waiting on that name", p.onMe(me(ApiResult.Status.OK,
			"{\"name\":\"Tadeko\",\"unverified\":true,\"status\":\"held\",\"rsn\":\"Tadeko\"}")));
		assertEquals(List.of("waiting Tadeko"), p.shown);
	}

	@Test
	public void anAnswerForTheTokenBeforeANewSignInIsDropped()
	{
		// a verified member's /me is on its way when Verify hands over a held token for a second name
		Scripted p = new Scripted();
		p.beginSignIn(true);
		int asked = p.signInGen;
		p.beginSignIn(false);
		List<Boolean> events = new ArrayList<>();
		p.onMeAnswer(asked, me(ApiResult.Status.OK, "{\"name\":\"Oldname\",\"verified\":[\"Oldname\"]}"), events::add);
		assertTrue("still waiting", p.signInUnverified);
		assertEquals("no false Verified line", List.of(), p.shown);
		assertEquals("no Events with the held token", List.of(), events);

		// the new token's own answer still counts
		p.onMeAnswer(p.signInGen, me(ApiResult.Status.OK, "{\"name\":\"Melk\",\"verified\":[\"Melk\"]}"), events::add);
		assertFalse(p.signInUnverified);
		assertEquals(List.of("waiting null", "now verified Melk"), p.shown);
		assertEquals("loaded, forced: it was waiting", List.of(true), events);

		p.shown.clear();
		events.clear();
		p.onMeAnswer(p.signInGen, new ApiResult<>(ApiResult.Status.UNAUTHORIZED, null, null, "Sign in again"), events::add);
		assertEquals("a 401 still asks Events, so the token goes", List.of(false), events);
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
		assertEquals("Melk", GSON.fromJson("{\"name\":\"X\",\"status\":\"held\",\"rsn\":\" Melk \"}", MeResponse.class).waitingOn());
		assertEquals("", GSON.fromJson("{\"name\":\"X\",\"status\":\"awaiting_roster\"}", MeResponse.class).waitingOn());
		assertEquals(null, GSON.fromJson("{\"name\":\"X\",\"unverified\":true,\"status\":\"unverified\"}", MeResponse.class).waitingOn());
		assertEquals(null, GSON.fromJson("{\"name\":\"X\",\"verified\":[\"X\"]}", MeResponse.class).waitingOn());
	}
}
