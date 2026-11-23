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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import cc.dreamchasers.clan.api.ApiResult;
import cc.dreamchasers.clan.api.Competition;
import cc.dreamchasers.clan.api.CompetitionResponse;
import cc.dreamchasers.clan.api.XpResponse;
import java.time.Instant;
import net.runelite.api.Skill;
import org.junit.Test;

/** Live competition XP's timer: when a send may go, what it sends, and the cap (D-231123-2). */
public class LiveXpTest
{
	private static final Instant T0 = Instant.parse("2026-11-23T12:00:00Z");

	private static CompetitionResponse comp(String skill, Integer every)
	{
		CompetitionResponse r = new CompetitionResponse();
		r.setStatus("ongoing");
		r.setXpSkill(skill);
		r.setXpEvery(every);
		r.setJitLogout(true);
		return r;
	}

	private static ApiResult<XpResponse> ok(boolean stored, String reason, String until)
	{
		XpResponse x = new XpResponse();
		x.setOk(true);
		x.setStored(stored);
		x.setReason(reason);
		x.setUntil(until);
		return new ApiResult<>(ApiResult.Status.OK, x, T0, null, 200);
	}

	private static ApiResult<XpResponse> failed()
	{
		return new ApiResult<>(ApiResult.Status.ERROR, null, null, "dream-chasers.cc answered 503", 503);
	}

	/** A LiveXp that has seen attack go from 1,000 to 1,500 this session. */
	private static LiveXp training()
	{
		LiveXp x = new LiveXp();
		x.stat("attack", 1000);     // the login burst
		x.stat("attack", 1500);     // a gain
		return x;
	}

	@Test
	public void theIntervalIsTheSitesButNeverUnderTwoMinutes()
	{
		assertEquals(300, LiveXp.every(null));
		assertEquals(300, LiveXp.every(comp("attack", null)));
		assertEquals(300, LiveXp.every(comp("attack", 0)));
		assertEquals(120, LiveXp.every(comp("attack", 30)));
		assertEquals(120, LiveXp.every(comp("attack", 120)));
		assertEquals(600, LiveXp.every(comp("attack", 600)));
	}

	@Test
	public void skillsAreNamedAsWiseOldManNamesThem()
	{
		assertEquals("attack", LiveXp.metricOf(Skill.ATTACK));
		assertEquals("runecrafting", LiveXp.metricOf(Skill.RUNECRAFT));
		assertEquals("sailing", LiveXp.metricOf(Skill.SAILING));     // this client's Skill enum has Sailing
		assertEquals("runecrafting", LiveXp.metricKey("Runecraft"));
		assertEquals("sailing", LiveXp.metricKey(" Sailing "));
		assertNull(LiveXp.metricKey(""));
		assertNull(LiveXp.wanted(comp(null, 300)));
		assertEquals("overall", LiveXp.wanted(comp("overall", 300)));
		CompetitionResponse onComp = comp(null, 300);
		Competition c = new Competition();
		c.setXpSkill("Mining");
		onComp.setCompetition(c);
		assertEquals("mining", LiveXp.wanted(onComp));
	}

	@Test
	public void theLoginBurstIsNotAGain()
	{
		LiveXp x = new LiveXp();
		x.stat("attack", 1000);
		x.stat("mining", 5000);
		assertFalse(x.due(T0));
		x.stat("attack", 1000);     // a boosted-level change: same XP
		assertFalse(x.due(T0));
	}

	@Test
	public void aGainSendsOnceThenWaitsForTheIntervalAndTheNextGain()
	{
		LiveXp x = training();
		assertTrue(x.due(T0));
		assertFalse("one try at a time", x.due(T0));
		int s = x.session();
		LiveXp.Send send = x.pick(s, comp("attack", 300));
		assertNotNull(send);
		assertEquals("attack", send.skill);
		assertEquals(1500, send.xp);
		x.answered(s, send, ok(true, null, null), T0);
		assertFalse(x.sending());
		assertFalse("nothing new", x.due(T0.plusSeconds(600)));
		x.stat("attack", 1600);
		assertFalse("the interval isn't up", x.due(T0.plusSeconds(299)));
		assertTrue(x.due(T0.plusSeconds(300)));
		assertEquals(1600, x.pick(s, comp("attack", 300)).xp);
	}

	@Test
	public void aValueTheSiteAlreadyAnsweredIsNotSentAgain()
	{
		LiveXp x = training();
		x.due(T0);
		int s = x.session();
		LiveXp.Send send = x.pick(s, comp("attack", 300));
		x.answered(s, send, ok(false, null, null), T0);     // lower than the site has, say: still an answer
		x.stat("mining", 10);
		x.stat("mining", 20);     // another skill: the copy named attack, so this costs nothing
		assertFalse(x.due(T0.plusSeconds(300)));
	}

	@Test
	public void aGainWhileTheSendIsOutGoesAtTheNextInterval()
	{
		LiveXp x = training();
		x.due(T0);
		int s = x.session();
		LiveXp.Send send = x.pick(s, comp("attack", 300));
		x.stat("attack", 1700);
		x.answered(s, send, ok(true, null, null), T0);
		assertTrue(x.moved());
		assertTrue(x.due(T0.plusSeconds(300)));
		assertEquals(1700, x.pick(s, comp("attack", 300)).xp);
	}

	@Test
	public void aFailedSendKeepsTheXpForTheNextInterval()
	{
		LiveXp x = training();
		x.due(T0);
		int s = x.session();
		LiveXp.Send send = x.pick(s, comp("attack", 300));
		x.answered(s, send, failed(), T0);
		assertFalse("never retried before the interval", x.due(T0.plusSeconds(60)));
		assertTrue(x.due(T0.plusSeconds(300)));
		assertEquals(1500, x.pick(s, comp("attack", 300)).xp);
	}

	@Test
	public void theCapStopsTimedSendsUntilTheSiteSays()
	{
		LiveXp x = training();
		x.due(T0);
		int s = x.session();
		LiveXp.Send send = x.pick(s, comp("attack", 300));
		x.answered(s, send, ok(false, "cap", "2026-11-24T00:00:00Z"), T0);
		assertTrue(x.capped(T0));
		x.stat("attack", 2000);
		assertFalse(x.due(T0.plusSeconds(3600)));
		assertFalse(x.due(Instant.parse("2026-11-23T23:59:59Z")));
		assertTrue("the next UTC day", x.due(Instant.parse("2026-11-24T00:00:00Z")));
	}

	@Test
	public void theCapEndsAtTheSitesTimeOrTheNextUtcMidnight()
	{
		Instant midnight = Instant.parse("2026-11-24T00:00:00Z");
		assertEquals(midnight, LiveXp.capEnd(null, T0));
		assertEquals(midnight, LiveXp.capEnd("soon", T0));
		assertEquals(midnight, LiveXp.capEnd("2026-11-22T00:00:00Z", T0));     // in the past
		assertEquals(midnight, LiveXp.capEnd("2027-01-01T00:00:00Z", T0));     // far off
		assertEquals(Instant.parse("2026-11-24T00:05:00Z"), LiveXp.capEnd("2026-11-24T00:05:00Z", T0));
	}

	@Test
	public void theCapOutlivesALogout()
	{
		LiveXp x = training();
		x.due(T0);
		int s = x.session();
		x.answered(s, x.pick(s, comp("attack", 300)), ok(false, "cap", null), T0);
		x.reset();
		x.stat("attack", 3000);
		x.stat("attack", 3100);
		assertFalse(x.due(T0.plusSeconds(3600)));
	}

	@Test
	public void noSkillCompetitionChecksAgainOnlyEveryFifteenMinutes()
	{
		LiveXp x = training();
		assertTrue(x.due(T0));
		int s = x.session();
		assertNull(x.pick(s, comp(null, 300)));
		x.stat("attack", 1600);     // the copy names no skill, so any gain may be the start of one
		assertFalse(x.due(T0.plusSeconds(300)));
		assertFalse(x.due(T0.plusSeconds(899)));
		assertTrue(x.due(T0.plusSeconds(900)));
	}

	@Test
	public void aCopyNamingTheSkillLetsTheNormalIntervalBackIn()
	{
		LiveXp x = training();
		x.due(T0);
		int s = x.session();
		assertNull(x.pick(s, comp(null, 300)));
		x.heard(comp("attack", 300));     // the panel's fetch: an attack competition has started
		assertTrue("this session's XP hasn't been sent", x.moved());
		assertTrue(x.due(T0.plusSeconds(300)));
		assertEquals(1500, x.pick(s, comp("attack", 300)).xp);
	}

	@Test
	public void otherSkillsCostNothingOnceTheCompetitionsSkillIsKnown()
	{
		LiveXp x = new LiveXp();
		x.heard(comp("attack", 300));
		x.stat("mining", 100);
		x.stat("mining", 200);
		assertFalse(x.due(T0));
		x.stat("attack", 50);
		x.stat("attack", 60);
		assertTrue(x.due(T0));
	}

	@Test
	public void hoppingNeverSendsFasterThanTheInterval()
	{
		LiveXp x = training();
		x.due(T0);
		int s = x.session();
		x.answered(s, x.pick(s, comp("attack", 300)), ok(true, null, null), T0);
		x.reset();     // a hop
		x.stat("attack", 1500);
		x.stat("attack", 1800);
		assertFalse(x.due(T0.plusSeconds(60)));
		assertTrue(x.due(T0.plusSeconds(300)));
	}

	@Test
	public void aHopCarriesXpTheSiteHadNotGot()
	{
		LiveXp x = training();     // attack 1,500, never sent (say the cap, or a skipped notice)
		x.heard(comp("attack", 300));
		x.hop();
		assertFalse("nothing in the new session yet", x.moved());
		x.stat("attack", 1500);     // the login burst after the hop: the same value
		assertTrue("still a gain to send", x.moved());
		assertTrue(x.due(T0));
		int s = x.session();
		LiveXp.Send send = x.pick(s, comp("attack", 300));
		assertNotNull(send);
		assertEquals(1500, send.xp);
		x.answered(s, send, ok(true, null, null), T0);
		x.hop();
		x.stat("attack", 1500);
		assertFalse("the site has it now: nothing carried", x.moved());
	}

	@Test
	public void aLogoutCarriesNothing()
	{
		LiveXp x = training();
		x.reset();     // a real logout: maybe another account next
		x.stat("attack", 1500);
		assertFalse(x.moved());
		assertTrue(x.snapshot().isEmpty());
	}

	@Test
	public void xpTheLogoutNoticeDeliveredIsNotCarried()
	{
		LiveXp x = training();
		x.heard(comp("attack", 300));
		x.hop();
		x.delivered("attack", 1500);     // the notice's answer lands after the hop
		x.stat("attack", 1500);
		assertFalse(x.moved());
		assertTrue(x.snapshot().isEmpty());
		x.stat("attack", 1600);
		assertEquals("{attack=1600}", x.snapshot().toString());
	}

	@Test
	public void aCarriedOtherSkillDoesNotStartATry()
	{
		LiveXp x = new LiveXp();
		x.stat("mining", 5000);
		x.stat("mining", 5500);
		x.heard(comp("attack", 300));
		x.hop();
		x.stat("mining", 5500);
		assertFalse("the competition is attack", x.moved());
	}

	@Test
	public void anAnswerFromBeforeALogoutIsIgnored()
	{
		LiveXp x = training();
		x.due(T0);
		int s = x.session();
		LiveXp.Send send = x.pick(s, comp("attack", 300));
		x.reset();
		x.answered(s, send, ok(false, "cap", null), T0);
		assertFalse(x.capped(T0));
		assertNull(x.pick(s, comp("attack", 300)));
	}

	@Test
	public void noCopyAtAllEndsTheTryWithoutForgettingTheGain()
	{
		LiveXp x = training();
		x.due(T0);
		int s = x.session();
		assertNull(x.pick(s, null));
		assertFalse(x.sending());
		assertTrue(x.moved());
		assertTrue(x.due(T0.plusSeconds(300)));
	}

	@Test
	public void theSitesIntervalIsUsedOnceHeard()
	{
		LiveXp x = training();
		x.heard(comp("attack", 600));
		assertEquals(600, x.every());
		x.due(T0);
		int s = x.session();
		x.answered(s, x.pick(s, comp("attack", 600)), ok(true, null, null), T0);
		x.stat("attack", 1600);
		assertFalse(x.due(T0.plusSeconds(300)));
		assertTrue(x.due(T0.plusSeconds(600)));
		x.heard(comp("attack", 10));
		assertEquals("never under two minutes", 120, x.every());
	}

	@Test
	public void aCompetitionStartingWithNoGainInItsSkillSendsNothing()
	{
		LiveXp x = new LiveXp();
		x.stat("attack", 1000);     // the login burst
		x.stat("mining", 5000);
		x.heard(comp(null, 300));
		x.heard(comp("attack", 300));     // an attack competition starts mid-session
		assertFalse("attack never moved", x.moved());
		assertFalse(x.due(T0));
	}

	@Test
	public void anotherSkillsGainBeforeAnyCopySendsNothingForTheUnmovedSkill()
	{
		LiveXp x = new LiveXp();
		x.stat("attack", 1000);
		x.stat("mining", 5000);
		x.stat("mining", 5500);     // no copy yet: any gain counts for a try
		assertTrue(x.due(T0));
		int s = x.session();
		assertNull("attack is still its login value", x.pick(s, comp("attack", 300)));
		assertFalse(x.sending());
		assertFalse(x.moved());
	}

	@Test
	public void theSnapshotHoldsOnlyXpTheSiteHasNotGot()
	{
		LiveXp x = training();
		x.stat("mining", 5000);     // login value only
		assertEquals("{attack=1500}", x.snapshot().toString());
		x.due(T0);
		int s = x.session();
		x.answered(s, x.pick(s, comp("attack", 300)), ok(true, null, null), T0);
		assertTrue("the site has 1500", x.snapshot().isEmpty());
		x.stat("attack", 1600);
		assertEquals("{attack=1600}", x.snapshot().toString());
	}

	@Test
	public void aCappedSendStaysInTheSnapshot()
	{
		LiveXp x = training();
		x.due(T0);
		int s = x.session();
		x.answered(s, x.pick(s, comp("attack", 300)), ok(false, "cap", null), T0);
		assertEquals("the cap stored nothing: the logout notice carries it", "{attack=1500}", x.snapshot().toString());
		assertTrue("sent once the cap ends", x.due(Instant.parse("2026-11-24T00:00:00Z")));
		assertEquals(1500, x.pick(s, comp("attack", 300)).xp);
	}

	@Test
	public void theBodies()
	{
		assertEquals("{\"rsn\":\"Lomaru\",\"digest\":\"d\",\"skill\":\"sailing\",\"xp\":13034431,\"acct\":2}",
			Uploads.xp("sailing", 13_034_431L, "Lomaru", "d", 2).toString());
		assertEquals("{\"rsn\":\"Lomaru\",\"skill\":\"overall\",\"xp\":4600000000}",
			Uploads.xp("overall", 4_600_000_000L, "Lomaru", null, null).toString());
		assertEquals("{\"rsn\":\"Lomaru\",\"digest\":\"d\",\"skill\":\"attack\",\"xp\":1500,\"acct\":0}",
			Uploads.logout("Lomaru", "d", 0, "attack", 1500L).toString());
		assertEquals("both or neither", "{\"rsn\":\"Lomaru\"}", Uploads.logout("Lomaru", null, null, "attack", null).toString());
		assertEquals("{\"rsn\":\"Lomaru\"}", Uploads.logout("Lomaru", null, null, null, 5L).toString());
	}
}
