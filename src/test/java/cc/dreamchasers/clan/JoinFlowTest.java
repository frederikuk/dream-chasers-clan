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
import cc.dreamchasers.clan.api.DrmApiClient;
import cc.dreamchasers.clan.api.FakeHttp;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Verify for Discord: gated on the own clan record, peek, confirm, attest. */
public class JoinFlowTest
{
	private static final long HASH = 4_242_424_242_424_242L;
	private static final OwnClan.Reading IN_CLAN = OwnClan.judge(true, "DRM Chasers", "Tadeko", true, HASH);
	private static final String PEEK_OK = "{\"apiVersion\":1,\"discordName\":\"Maho\",\"expiresAt\":\"2026-11-03T20:10:00.000Z\"}";
	private static final String VERIFIED = "{\"apiVersion\":1,\"status\":\"verified\",\"rsn\":\"Tadeko\",\"discordName\":\"Maho\","
		+ "\"token\":\"drm_pt_abc\",\"name\":\"Tadeko\",\"expiresAt\":\"2027-11-03T20:00:00.000Z\"}";

	private final FakeHttp fake = new FakeHttp();
	private final FakeHttp.MutableClock clock = new FakeHttp.MutableClock(Instant.parse("2026-11-03T20:00:00Z"));
	private final DrmApiClient api = new DrmApiClient(fake.client, new Gson(), "https://x/api/v1", "UA", () -> "drm_pt_old", clock);
	private final AtomicReference<OwnClan.Reading> reading = new AtomicReference<>(IN_CLAN);
	private final JoinFlow flow = new JoinFlow(api, reading::get, "0.5.0");

	private static final class Outcome implements JoinFlow.Listener
	{
		final CompletableFuture<String> asked = new CompletableFuture<>();
		final CompletableFuture<String> done = new CompletableFuture<>();

		@Override
		public void confirm(String rsn, String discordName)
		{
			asked.complete(rsn + " -> " + discordName);
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

	@Test
	public void notInTheClanSendsNothing() throws Exception
	{
		reading.set(OwnClan.judge(true, "Other Clan", "Tadeko", true, HASH));
		Outcome o = new Outcome();
		flow.peek("ABCD-2345", o);
		assertEquals("error Your clan in game isn't DRM Chasers. Join DRM Chasers first.", o.done.get(1, TimeUnit.SECONDS));
		assertTrue(fake.requests.isEmpty());
	}

	@Test
	public void clanRecordNotLoadedYetSendsNothing() throws Exception
	{
		reading.set(OwnClan.Reading.UNKNOWN);
		Outcome o = new Outcome();
		flow.peek("ABCD-2345", o);
		assertEquals("error Join DRM Chasers in game first, or open your clan tab and wait a few seconds.", o.done.get(1, TimeUnit.SECONDS));
		assertTrue(fake.requests.isEmpty());
	}

	@Test
	public void inTheClanPeeksConfirmsAndAttests() throws Exception
	{
		fake.reply(200, PEEK_OK, null).reply(200, VERIFIED, null);
		Outcome o = new Outcome();
		flow.peek(" abcd 2345 ", o);
		assertEquals("Tadeko -> Maho", o.asked.get(5, TimeUnit.SECONDS));
		assertFalse(o.done.isDone());

		FakeHttp.Recorded peek = fake.requests.get(0);
		assertEquals("POST", peek.request.method());
		assertEquals("https://x/api/join/peek", peek.request.url().toString());
		assertEquals("{\"code\":\"ABCD-2345\"}", peek.body);
		assertNull("the code is the credential: no token on join calls", peek.request.header("Authorization"));

		flow.confirm();
		String done = o.done.get(5, TimeUnit.SECONDS);
		assertTrue(done, done.startsWith("verified drm_pt_abc Tadeko | Verified: Tadeko is linked to your Discord."));

		FakeHttp.Recorded attest = fake.requests.get(1);
		assertEquals("https://x/api/join/attest", attest.request.url().toString());
		assertNull(attest.request.header("Authorization"));
		JsonObject body = new Gson().fromJson(attest.body, JsonObject.class);
		// exactly what the contract lists: no rank, no join date, nothing about anyone else
		assertEquals(new TreeSet<>(Set.of("code", "rsn", "digest", "clanName", "pluginVersion", "device")), new TreeSet<>(body.keySet()));
		assertEquals("ABCD-2345", body.get("code").getAsString());
		assertEquals("Tadeko", body.get("rsn").getAsString());
		assertEquals(OwnClan.digest(HASH), body.get("digest").getAsString());
		assertEquals("DRM Chasers", body.get("clanName").getAsString());
		assertEquals("0.5.0", body.get("pluginVersion").getAsString());
		assertEquals("RuneLite", body.get("device").getAsString());
	}

	@Test
	public void theRawAccountHashNeverLeaves() throws Exception
	{
		fake.reply(200, PEEK_OK, null).reply(200, VERIFIED, null);
		Outcome o = new Outcome();
		flow.peek("ABCD2345", o);
		o.asked.get(5, TimeUnit.SECONDS);
		flow.confirm();
		o.done.get(5, TimeUnit.SECONDS);
		assertEquals(2, fake.requests.size());
		String raw = Long.toString(HASH);
		for (FakeHttp.Recorded r : fake.requests)
		{
			assertFalse(r.request.url().toString().contains(raw));
			assertFalse(r.request.headers().toString().contains(raw));
			assertFalse(r.body.contains(raw));
		}
	}

	@Test
	public void awaitingRosterIsShownWithoutAToken() throws Exception
	{
		fake.reply(200, PEEK_OK, null)
			.reply(202, "{\"apiVersion\":1,\"status\":\"awaiting_roster\",\"message\":\"Tadeko isn't on the roster yet.\"}", null);
		Outcome o = attestFor();
		assertEquals("info Tadeko isn't on the roster yet.", o.done.get(5, TimeUnit.SECONDS));
	}

	@Test
	public void heldIsShownWithoutAToken() throws Exception
	{
		fake.reply(200, PEEK_OK, null).reply(202, "{\"apiVersion\":1,\"status\":\"held\",\"reason\":\"x\","
			+ "\"message\":\"An officer will check this. Open dream-chasers.cc/join for your witness code.\"}", null);
		Outcome o = attestFor();
		assertEquals("info An officer will check this. Open dream-chasers.cc/join for your witness code.", o.done.get(5, TimeUnit.SECONDS));
	}

	@Test
	public void aRenameSaysTheLinkMoved() throws Exception
	{
		fake.reply(200, PEEK_OK, null).reply(200, VERIFIED.replace("}", ",\"renamedFrom\":\"Maho old\"}"), null);
		Outcome o = attestFor();
		assertTrue(o.done.get(5, TimeUnit.SECONDS).contains("Your link moved from Maho old to Tadeko."));
	}

	@Test
	public void aWrongCodeShowsTheSitesWords() throws Exception
	{
		fake.reply(400, "{\"error\":\"That code is wrong or has expired. Get a new one at dream-chasers.cc/join.\"}", null);
		Outcome o = new Outcome();
		flow.peek("ZZZZ-ZZZZ", o);
		assertEquals("error That code is wrong or has expired. Get a new one at dream-chasers.cc/join.", o.done.get(5, TimeUnit.SECONDS));
		assertFalse(o.asked.isDone());
	}

	@Test
	public void attestRefusalShowsTheSitesWords() throws Exception
	{
		fake.reply(200, PEEK_OK, null).reply(429, "{\"error\":\"Too many tries. Wait 10 minutes.\"}", null);
		Outcome o = attestFor();
		assertEquals("error Too many tries. Wait 10 minutes.", o.done.get(5, TimeUnit.SECONDS));
	}

	@Test
	public void switchedOffAtTheSiteSaysSo() throws Exception
	{
		fake.reply(404, "{\"error\":\"not found\"}", null);
		Outcome o = new Outcome();
		flow.peek("ABCD-2345", o);
		assertTrue(o.done.get(5, TimeUnit.SECONDS).startsWith("error "));
	}

	@Test
	public void aChangedClanRecordStopsTheAttest() throws Exception
	{
		fake.reply(200, PEEK_OK, null);
		Outcome o = new Outcome();
		flow.peek("ABCD-2345", o);
		o.asked.get(5, TimeUnit.SECONDS);
		reading.set(OwnClan.judge(true, "DRM Chasers", "Someone else", true, HASH + 1));
		flow.confirm();
		assertEquals("error You changed account or clan since. Type the code again.", o.done.get(5, TimeUnit.SECONDS));
		assertEquals(1, fake.requests.size());
	}

	@Test
	public void cancelSendsNothingMore() throws Exception
	{
		fake.reply(200, PEEK_OK, null);
		Outcome o = new Outcome();
		flow.peek("ABCD-2345", o);
		o.asked.get(5, TimeUnit.SECONDS);
		flow.cancel();
		flow.confirm();
		assertFalse(flow.isBusy());
		assertEquals(1, fake.requests.size());
		assertFalse(o.done.isDone());
	}

	@Test
	public void anEmptyCodeSendsNothing() throws Exception
	{
		Outcome o = new Outcome();
		flow.peek("  - ", o);
		assertEquals("error Type the code from dream-chasers.cc/join first", o.done.get(1, TimeUnit.SECONDS));
		assertTrue(fake.requests.isEmpty());
	}

	private Outcome attestFor() throws Exception
	{
		Outcome o = new Outcome();
		flow.peek("ABCD-2345", o);
		o.asked.get(5, TimeUnit.SECONDS);
		flow.confirm();
		return o;
	}
}
