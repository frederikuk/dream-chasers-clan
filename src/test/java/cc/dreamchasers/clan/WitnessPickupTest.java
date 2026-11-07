/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan;

import cc.dreamchasers.clan.api.ApiResult;
import cc.dreamchasers.clan.api.WitnessResponse;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.LocalTime;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class WitnessPickupTest
{
	private static final Instant T0 = Instant.parse("2026-11-06T12:00:00Z");

	@Test
	public void findsTheCodeHoweverItIsTyped()
	{
		assertEquals("DRM-ABC234", WitnessPickup.findCode("DRM-ABC234"));
		assertEquals("DRM-ABC234", WitnessPickup.findCode("drm-abc234"));        // the page shows it lower case
		assertEquals("DRM-ABC234", WitnessPickup.findCode("drmabc234"));
		assertEquals("DRM-ABC234", WitnessPickup.findCode("drm abc234"));
		assertEquals("DRM-ABC234", WitnessPickup.findCode("hi! my code is drm-abc234 thanks"));
		assertEquals("DRM-ABC234", WitnessPickup.findCode("<col=ff0000>drm-abc234</col>"));
		assertEquals("DRM-ABCDEF", WitnessPickup.findCode("drm-abcdef"));   // no digit, but the dash
		assertEquals("DRM-ABC234", WitnessPickup.findCode("drm events then drm abc234"));
	}

	@Test
	public void anythingElseInChatIsLeftAlone()
	{
		assertNull(WitnessPickup.findCode("gz on the drop"));
		assertNull(WitnessPickup.findCode("drm-abc23"));           // too short
		assertNull(WitnessPickup.findCode("drm-abc2345"));         // too long
		assertNull(WitnessPickup.findCode("xdrm-abc234"));         // inside a word
		assertNull(WitnessPickup.findCode("drm"));
		assertNull("chat about the clan", WitnessPickup.findCode("drm events"));
		assertNull(WitnessPickup.findCode("drm member"));
		assertNull(WitnessPickup.findCode("drmforums"));
		assertNull("0 and O aren't in the alphabet", WitnessPickup.findCode("drm-abcd0e"));
		assertNull(WitnessPickup.findCode("drm-abcdoe"));
		assertNull(WitnessPickup.findCode(""));
		assertNull(WitnessPickup.findCode(null));
	}

	@Test
	public void senderIsTheGamesNameWithoutIcons()
	{
		assertEquals("Bob The Great", WitnessPickup.senderName("<img=41>Bob The Great"));
		assertEquals("bob", WitnessPickup.senderName("bob"));
		assertNull(WitnessPickup.senderName("<img=2>"));
		assertNull(WitnessPickup.senderName(null));
	}

	@Test
	public void bodiesCarryOnlyTheCodeTheSenderAndWhere()
	{
		JsonObject b = WitnessPickup.witnessBody("DRM Chasers", "Alice", "Bob", "DRM-ABC234", WitnessPickup.Via.PM);
		assertEquals(1, b.get("apiVersion").getAsInt());
		assertEquals("Alice", b.get("sender").getAsString());
		JsonObject w = b.getAsJsonObject("witness");
		assertEquals("Bob", w.get("rsn").getAsString());
		assertEquals("DRM-ABC234", w.get("code").getAsString());
		assertEquals("pm", w.get("via").getAsString());
		assertEquals(3, w.size());
		assertEquals(4, b.size());
		JsonObject h = WitnessPickup.hereBody("DRM Chasers", "Alice", false);
		assertFalse(h.get("here").getAsBoolean());
		assertFalse(h.has("witness"));
	}

	@Test
	public void aCodeGoesOnceAndFloodsAreCapped()
	{
		WitnessPickup p = new WitnessPickup();
		assertTrue(p.take(T0, "Bob", "DRM-ABC234"));
		assertFalse("the same line again", p.take(T0, "bob", "DRM-ABC234"));
		assertTrue("another code", p.take(T0, "Bob", "DRM-ABC235"));
		assertFalse("a third code from one sender", p.take(T0, "Bob", "DRM-ABC236"));
		for (int i = 2; i < WitnessPickup.MAX_SENDS; i++)
		{
			assertTrue(p.take(T0, "Carl" + i, "DRM-ABC234"));
		}
		assertFalse("over the limit", p.take(T0, "Dave", "DRM-ABC234"));
		assertTrue("the window has passed", p.take(T0.plus(WitnessPickup.WINDOW), "Dave", "DRM-ABC234"));
	}

	@Test
	public void aSendWithNoAnswerMayGoAgain()
	{
		WitnessPickup p = new WitnessPickup();
		assertTrue(p.take(T0, "Bob", "DRM-ABC234"));
		p.release("Bob", "DRM-ABC234");
		assertTrue(p.take(T0, "Bob", "DRM-ABC234"));
		assertFalse(p.take(T0, "Bob", "DRM-ABC234"));
	}

	@Test
	public void aRefusedHereOwesNoGone()
	{
		WitnessPickup p = new WitnessPickup();
		assertTrue(p.hereDue(T0));
		p.answered(new ApiResult<>(ApiResult.Status.FORBIDDEN, null, null, "no"), LocalTime.NOON);
		assertFalse(p.goneDue());
		p.resume();
		assertTrue("one here per login", p.hereDue(T0));
	}

	@Test
	public void hereEveryFiveMinutesAndGoneOnce()
	{
		WitnessPickup p = new WitnessPickup();
		assertFalse("nothing owed before a here", p.goneDue());
		assertTrue(p.hereDue(T0));
		assertFalse(p.hereDue(T0.plusSeconds(60)));
		assertTrue(p.hereDue(T0.plus(WitnessPickup.HERE_EVERY)));
		assertTrue(p.goneDue());
		assertFalse("once", p.goneDue());
		assertTrue("back again: here at once", p.hereDue(T0.plus(WitnessPickup.HERE_EVERY).plusSeconds(1)));
	}

	@Test
	public void answers()
	{
		WitnessPickup p = new WitnessPickup();
		LocalTime at = LocalTime.of(12, 34);
		assertEquals("Verified Bob 12:34", p.answered(ok(WitnessResponse.VERIFIED, "Bob", null), at).getLine());
		assertNull("no match: quiet", p.answered(ok(WitnessResponse.NO_MATCH, null, null), at).getLine());
		assertEquals("Young's code: This one needs an officer to look on the site.",
			p.answered(ok(WitnessResponse.NEEDS_OFFICER, "Young", "This one needs an officer to look on the site."), at).getLine());
		assertNull("a here: quiet", p.answered(new ApiResult<>(ApiResult.Status.OK, new WitnessResponse(), null, null), at).getLine());
		assertTrue(p.answered(new ApiResult<>(ApiResult.Status.UNAUTHORIZED, null, null, null), at).isForgetToken());
		assertFalse(p.isStopped());
		assertEquals("Verify pickup stopped: nope",
			p.answered(new ApiResult<>(ApiResult.Status.FORBIDDEN, null, null, "nope"), at).getLine());
		assertTrue(p.isStopped());
		assertFalse("stopped: nothing goes", p.take(T0, "Bob", "DRM-ABC234"));
		assertFalse(p.hereDue(T0));
		p.resume();
		assertTrue(p.take(T0, "Bob", "DRM-ABC234"));
	}

	private static ApiResult<WitnessResponse> ok(String status, String rsn, String message)
	{
		WitnessResponse r = new WitnessResponse();
		WitnessResponse.Witness w = new WitnessResponse.Witness();
		w.setStatus(status);
		w.setRsn(rsn);
		w.setMessage(message);
		r.setWitness(w);
		return new ApiResult<>(ApiResult.Status.OK, r, null, null);
	}
}
