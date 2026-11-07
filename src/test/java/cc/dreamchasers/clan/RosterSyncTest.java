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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import cc.dreamchasers.clan.api.ApiResult;
import cc.dreamchasers.clan.api.DrmApiClient;
import cc.dreamchasers.clan.api.FakeHttp;
import cc.dreamchasers.clan.api.RosterResponse;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.runelite.api.clan.ClanMember;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;
import net.runelite.api.gameval.InterfaceID;
import org.junit.Test;

public class RosterSyncTest
{
	private static final Instant T0 = Instant.parse("2026-11-05T20:00:00Z");
	private static final LocalTime AT = LocalTime.of(12, 34);
	private final Gson gson = new Gson();

	// ---- a clan record, as the game hands it over ----

	private static final class Member implements ClanMember
	{
		final String name;
		final ClanRank rank;

		Member(String name, int rank)
		{
			this.name = name;
			this.rank = new ClanRank(rank);
		}

		@Override
		public String getName()
		{
			return name;
		}

		@Override
		public ClanRank getRank()
		{
			return rank;
		}

		@Override
		public LocalDate getJoinDate()
		{
			return null;
		}
	}

	private static final class Settings implements ClanSettings
	{
		final String name;
		final List<ClanMember> members;
		final Map<Integer, String> titles = new HashMap<>();

		Settings(String name, ClanMember... members)
		{
			this.name = name;
			this.members = Arrays.asList(members);
			titles.put(10, "Gnome Child");
			titles.put(100, "Deputy Owner");
			titles.put(70, "Wizard");
			titles.put(20, "Sergeant");
		}

		@Override
		public String getName()
		{
			return name;
		}

		@Override
		public List<ClanMember> getMembers()
		{
			return members;
		}

		@Override
		public ClanMember findMember(String name)
		{
			for (ClanMember m : members)
			{
				if (m.getName().equals(name))
				{
					return m;
				}
			}
			return null;
		}

		@Override
		public ClanTitle titleForRank(ClanRank rank)
		{
			String t = titles.get(rank.getRank());
			return t == null ? null : new ClanTitle(rank.getRank(), t);
		}
	}

	// ---- the trigger ----

	@Test
	public void membersScreenIsTheClanMembersInterface()
	{
		// the WOM plugin's Members screen; its components (the search box, Exit) sit in that group
		assertEquals(693, InterfaceID.CLANS_MEMBERS);
		assertEquals(InterfaceID.CLANS_MEMBERS, InterfaceID.ClansMembers.SEARCH >>> 16);
		assertEquals(InterfaceID.CLANS_MEMBERS, InterfaceID.ClansMembers.EXIT >>> 16);
	}

	@Test
	public void onlyAnOfficerSignedInWithEverythingOnMaySend()
	{
		assertTrue(RosterSync.allowed(true, true, true, "DRM Chasers", "legend"));
		assertTrue(RosterSync.allowed(true, true, true, "DRM Chasers", "owner"));
		assertTrue("an officer", RosterSync.allowed(true, true, true, "DRM Chasers", "wizard"));
		assertTrue(RosterSync.allowed(true, true, true, "drm chasers", "deputy_owner"));
		assertFalse("Connect off", RosterSync.allowed(false, true, true, "DRM Chasers", "legend"));
		assertFalse("setting off", RosterSync.allowed(true, false, true, "DRM Chasers", "legend"));
		assertFalse("not signed in", RosterSync.allowed(true, true, false, "DRM Chasers", "legend"));
		assertFalse("another clan", RosterSync.allowed(true, true, true, "Other Clan", "owner"));
		assertFalse("no clan", RosterSync.allowed(true, true, true, null, "owner"));
		assertFalse("not an officer", RosterSync.allowed(true, true, true, "DRM Chasers", "gnome_child"));
		assertFalse("untitled", RosterSync.allowed(true, true, true, "DRM Chasers", "member"));
		assertFalse("not in the list", RosterSync.allowed(true, true, true, "DRM Chasers", null));
	}

	@Test
	public void syncRanksMatchTheSite()
	{
		// gate.js rankLevel 1 and up: the hats, the capes, Administrator, Deputy Owner, Owner
		for (String r : new String[]{"summoner", "illusionist", "wizard", "warlock", "necromancer",
			"legend", "myth", "tztok", "tzkal", "administrator", "deputy_owner", "owner"})
		{
			assertTrue(r, RosterSync.isOfficer(r));
		}
		assertEquals(12, RosterSync.SYNC_RANKS.size());
		for (String r : new String[]{"gnome_child", "imp", "sergeant", "member"})
		{
			assertFalse(r, RosterSync.isOfficer(r));
		}
		assertFalse(RosterSync.isOfficer("Legend"));     // keys only, already normalised
	}

	@Test
	public void rankTitlesNormaliseLikeTheWiseOldManPlugin()
	{
		assertEquals("gnome_child", RosterSync.rankKey("Gnome Child"));
		assertEquals("deputy_owner", RosterSync.rankKey("Deputy Owner"));
		assertEquals("tz_kal", RosterSync.rankKey("Tz-Kal"));
		assertEquals("gnome_child", RosterSync.rankKey("Gnome Child"));
		assertEquals("wizard", RosterSync.rankKey(" WIZARD "));
		assertEquals("member", RosterSync.rankKey(null));
		assertEquals("member", RosterSync.rankKey(""));
		// the site turns away the whole list if one rank isn't ^[a-z0-9_]{1,24}$
		String odd = RosterSync.rankKey("Dragon's-Fire Ultra Long Title Name");
		assertTrue(odd, odd.matches("[a-z0-9_]{1,24}"));
		assertEquals("dragons_fire_ultra_long_", odd);
		assertEquals("sr_mod", RosterSync.rankKey("Sr. Mod"));
		assertEquals("caf", RosterSync.rankKey("Café"));
		assertEquals("member", RosterSync.rankKey("!!!"));
		for (String t : new String[]{"Élite", "A.B.C", "x".repeat(40), "★★", "Tz-Kal"})
		{
			assertTrue(t, RosterSync.rankKey(t).matches("[a-z0-9_]{1,24}"));
		}
	}

	@Test
	public void readsNamesAndTitlesOnly()
	{
		Settings s = new Settings("DRM Chasers",
			new Member("Tadeko", 70),
			new Member("Maho z h", 10),
			new Member("[#12345]", 10),
			new Member("Newbie", 5));
		List<RosterSync.Member> out = RosterSync.read(s);
		assertEquals(Arrays.asList(
			new RosterSync.Member("Tadeko", "wizard"),
			new RosterSync.Member("Maho z h", "gnome_child"),
			new RosterSync.Member("Newbie", "member")), out);
		assertEquals("wizard", RosterSync.ownRank(s, "Tadeko"));
		assertNull(RosterSync.ownRank(s, "Someone else"));
	}

	@Test
	public void bodyMatchesTheWireContract()
	{
		JsonObject body = RosterSync.body("DRM Chasers", "Tadeko",
			Arrays.asList(new RosterSync.Member("Foo Bar", "gnome_child")));
		assertEquals("{\"apiVersion\":1,\"clanName\":\"DRM Chasers\",\"sender\":\"Tadeko\","
			+ "\"members\":[{\"rsn\":\"Foo Bar\",\"rank\":\"gnome_child\"}]}", gson.toJson(body));
	}

	// ---- hash and throttle ----

	@Test
	public void hashIgnoresOrderButSeesRankChanges()
	{
		List<RosterSync.Member> a = new ArrayList<>(Arrays.asList(
			new RosterSync.Member("Tadeko", "wizard"), new RosterSync.Member("Foo", "gnome_child")));
		List<RosterSync.Member> b = new ArrayList<>(a);
		Collections.reverse(b);
		assertEquals(RosterSync.hash(a), RosterSync.hash(b));
		List<RosterSync.Member> c = Arrays.asList(
			new RosterSync.Member("Tadeko", "wizard"), new RosterSync.Member("Foo", "sergeant"));
		assertNotEquals(RosterSync.hash(a), RosterSync.hash(c));
		assertEquals(64, RosterSync.hash(a).length());
	}

	@Test
	public void sendsOnlyChangedListsAndAtMostOnceAMinute()
	{
		RosterSync sync = new RosterSync();
		assertEquals(RosterSync.Decision.SEND, sync.check(T0, "a"));
		assertEquals("the same list while its send is on the way: nothing more", RosterSync.Decision.UNCHANGED, sync.check(T0.plusSeconds(30), "a"));
		assertEquals("a changed list inside the minute waits", RosterSync.Decision.TOO_SOON, sync.check(T0.plusSeconds(59), "c"));
		assertTrue(sync.changedSinceLastSend("c"));
		assertEquals(T0.plus(RosterSync.MIN_INTERVAL), sync.nextAllowed());
		sync.answered(ok(2, 1), "a", AT);
		assertEquals("the same list again: nothing to send", RosterSync.Decision.UNCHANGED, sync.check(T0.plusSeconds(120), "a"));
		assertEquals(RosterSync.Decision.TOO_SOON, sync.check(T0.plusSeconds(59), "b"));
		Instant t1 = T0.plus(RosterSync.MIN_INTERVAL);
		assertEquals(RosterSync.Decision.SEND, sync.check(t1, "b"));
		// a failed send leaves the list unaccepted and goes once more when the minute is up
		RosterSync.Outcome failed = sync.answered(new ApiResult<>(ApiResult.Status.ERROR, null, null, "Couldn't reach dream-chasers.cc"), "b", AT);
		assertTrue(failed.isRetry());
		assertEquals("Roster not sent: Couldn't reach dream-chasers.cc. Trying again at 12:35", failed.getLine());
		assertEquals("a retry inside the minute waits", RosterSync.Decision.TOO_SOON, sync.check(t1.plusSeconds(30), "b"));
		assertFalse("the same list as the failed send", sync.changedSinceLastSend("b"));
		Instant t2 = t1.plus(RosterSync.MIN_INTERVAL);
		assertEquals(RosterSync.Decision.SEND, sync.check(t2, "b"));
		RosterSync.Outcome again = sync.answered(new ApiResult<>(ApiResult.Status.ERROR, null, null, "busy"), "b", AT);
		assertFalse("one retry only: then the next close", again.isRetry());
		assertEquals("Roster not sent: busy", again.getLine());
		assertEquals(RosterSync.Decision.SEND, sync.check(t2.plus(RosterSync.MIN_INTERVAL), "b"));
		sync.answered(ok(1, 0), "b", AT);
		assertEquals(RosterSync.Decision.SEND, sync.check(t2.plus(RosterSync.MIN_INTERVAL.multipliedBy(2)), "c"));
		assertTrue("taken: a later failure gets its retry again",
			sync.answered(new ApiResult<>(ApiResult.Status.ERROR, null, null, "busy"), "c", AT).isRetry());
	}

	@Test
	public void statusLines()
	{
		assertEquals("Roster sent 12:34: 2 added, 1 rank changed", RosterSync.sentLine(AT, 2, 1));
		assertEquals("Roster sent 12:34: 3 ranks changed", RosterSync.sentLine(AT, 0, 3));
		assertEquals("Roster sent 09:05: no changes", RosterSync.sentLine(LocalTime.of(9, 5), 0, 0));
		assertEquals("Changed again: sending at 12:34", RosterSync.waitingLine(AT, true));
		assertEquals("Not sent yet: trying again at 12:34", RosterSync.waitingLine(AT, false));
		assertEquals("Roster not sent: the member list is over 600 names", RosterSync.notReadLine(601));
		assertEquals("Roster not sent: the member list isn't loaded yet", RosterSync.notReadLine(0));
		RosterSync sync = new RosterSync();
		RosterSync.Outcome out = sync.answered(ok(2, 1), "a", AT);
		assertEquals("Roster sent 12:34: 2 added, 1 rank changed", out.getLine());
		assertFalse(out.isForgetToken());
		assertEquals("a", sync.acceptedHash());
	}

	// ---- the site's answers, through the real client ----

	private ApiResult<RosterResponse> post(FakeHttp fake, String token) throws Exception
	{
		DrmApiClient api = new DrmApiClient(fake.client, gson, "https://x/api/v1", "UA", () -> token,
			new FakeHttp.MutableClock(T0));
		CompletableFuture<ApiResult<RosterResponse>> done = new CompletableFuture<>();
		api.postAuthed(RosterSync.PATH, RosterSync.body("DRM Chasers", "Tadeko",
			Arrays.asList(new RosterSync.Member("Foo Bar", "gnome_child"))), RosterResponse.class, done::complete);
		return done.get(5, TimeUnit.SECONDS);
	}

	@Test
	public void acceptedListIsSentWithTheToken() throws Exception
	{
		FakeHttp fake = new FakeHttp().reply(200, "{\"apiVersion\":1,\"ok\":true,\"added\":2,\"changed\":1,\"settled\":1}",
			"private, no-store");
		ApiResult<RosterResponse> r = post(fake, "drm_pt_x");
		assertEquals("https://x/api/v1/plugin/roster", fake.requests.get(0).request.url().toString());
		assertEquals("Bearer drm_pt_x", fake.requests.get(0).request.header("Authorization"));
		assertTrue(fake.requests.get(0).body.contains("\"members\":[{\"rsn\":\"Foo Bar\",\"rank\":\"gnome_child\"}]"));
		RosterSync sync = new RosterSync();
		RosterSync.Outcome out = sync.answered(r, "h", AT);
		assertEquals("Roster sent 12:34: 2 added, 1 rank changed", out.getLine());
		assertEquals(1, r.getValue().getSettled());
	}

	@Test
	public void forbiddenStopsTheSyncForTheSession() throws Exception
	{
		FakeHttp fake = new FakeHttp().reply(403, "{\"apiVersion\":1,\"error\":\"only officers can sync the roster\"}", "private, no-store");
		RosterSync sync = new RosterSync();
		assertEquals(RosterSync.Decision.SEND, sync.check(T0, "h"));
		RosterSync.Outcome out = sync.answered(post(fake, "drm_pt_x"), "h", AT);
		assertEquals("Roster sync stopped: Only officers can sync the roster", out.getLine());
		assertFalse("a 403 keeps the token", out.isForgetToken());
		assertTrue(sync.isStopped());
		assertEquals(RosterSync.Decision.STOPPED, sync.check(T0.plusSeconds(3600), "other"));
		sync.resume();     // next login, or the setting ticked again
		assertEquals(RosterSync.Decision.SEND, sync.check(T0.plusSeconds(3600), "other"));
	}

	@Test
	public void unauthorizedForgetsTheToken() throws Exception
	{
		FakeHttp fake = new FakeHttp().reply(401, "{\"apiVersion\":1,\"error\":\"unknown token\"}", "private, no-store");
		RosterSync sync = new RosterSync();
		RosterSync.Outcome out = sync.answered(post(fake, "drm_pt_x"), "h", AT);
		assertTrue(out.isForgetToken());
		assertNull("signed out: no line", out.getLine());
		assertFalse(sync.isStopped());
		assertNull(sync.acceptedHash());
	}

	@Test
	public void wrongClanAndTooSoonAreShownNotStopped() throws Exception
	{
		FakeHttp fake = new FakeHttp()
			.reply(409, "{\"apiVersion\":1,\"error\":\"that clan isn't Dream Chasers\"}", "private, no-store")
			.reply(429, "{\"apiVersion\":1,\"error\":\"too soon, try again in a minute\"}", "private, no-store");
		RosterSync sync = new RosterSync();
		RosterSync.Outcome wrong = sync.answered(post(fake, "t"), "h", AT);
		assertEquals("Roster not sent: That clan isn't Dream Chasers", wrong.getLine());
		assertFalse("a refusal isn't tried again", wrong.isRetry());
		RosterSync.Outcome soon = sync.answered(post(fake, "t"), "h", AT);
		assertEquals("Roster not sent: Too soon, try again in a minute. Trying again at 12:35", soon.getLine());
		assertTrue("busy: once more when the minute is up", soon.isRetry());
		assertFalse(sync.isStopped());
		assertNull(sync.acceptedHash());
	}

	@Test
	public void noTokenSendsNothing() throws Exception
	{
		FakeHttp fake = new FakeHttp();
		ApiResult<RosterResponse> r = post(fake, null);
		assertEquals(ApiResult.Status.UNAUTHORIZED, r.getStatus());
		assertTrue(fake.requests.isEmpty());
	}

	private static ApiResult<RosterResponse> ok(int added, int changed)
	{
		RosterResponse v = new RosterResponse();
		v.setApiVersion(1);
		v.setOk(true);
		v.setAdded(added);
		v.setChanged(changed);
		return new ApiResult<>(ApiResult.Status.OK, v, T0, null, 200);
	}
}
