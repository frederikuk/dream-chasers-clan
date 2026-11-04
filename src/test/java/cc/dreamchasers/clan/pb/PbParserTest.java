/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.pb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/**
 * The game's own message strings, as the client hands them to plugins (colour
 * tags and all). Sources: RuneLite core's ChatCommandsPlugin tests and the
 * OSRS Wiki; Dink's tests for the plain-text forms.
 */
public class PbParserTest
{
	private PbParser p;
	private int tick;

	@Before
	public void setUp()
	{
		p = new PbParser();
		tick = 1000;
	}

	/** Feeds lines in one tick; returns the last non-null record. */
	private PbRecord feed(String... lines)
	{
		PbRecord out = null;
		for (String l : lines)
		{
			PbRecord r = p.onMessage(l, tick);
			if (r != null)
			{
				out = r;
			}
		}
		return out;
	}

	private static void assertPb(PbRecord r, String boss, double seconds, String team, Integer kc, boolean pb)
	{
		assertEquals(boss, r.getBoss());
		assertEquals(seconds, r.getSeconds(), 0.001);
		assertEquals(team, r.getTeamSize());
		assertEquals(kc, r.getKc());
		assertEquals(pb, r.isPb());
	}

	// ---- kill count first, then the time ----

	@Test
	public void newPbAfterKillCount()
	{
		PbRecord r = feed("Your Kree'arra kill count is: <col=ff0000>4</col>.",
			"Fight duration: <col=ff0000>3:01.40</col> (new personal best).");
		assertPb(r, "Kree'arra", 181.4, null, 4, true);
	}

	@Test
	public void wholeSecondsWithoutPrecisePbs()
	{
		PbRecord r = feed("Your Kree'arra kill count is: <col=ff0000>4</col>.",
			"Fight duration: <col=ff0000>3:01</col> (new personal best).");
		assertEquals(181.0, r.getSeconds(), 0.001);
	}

	@Test
	public void slowerKillReportsTheStandingPb()
	{
		PbRecord r = feed("Your Zulrah kill count is: <col=ff0000>4</col>.",
			"Fight duration: <col=ff0000>0:59.20</col>. Personal best: 0:55.40");
		assertPb(r, "Zulrah", 55.4, null, 4, false);
		r = feed("Your Kree'arra kill count is: <col=ff0000>4</col>.",
			"Fight duration: <col=ff0000>2:06</col>. Personal best: 1:19.");
		assertPb(r, "Kree'arra", 79, null, 4, false);
	}

	@Test
	public void infernoDurationOverAnHour()
	{
		PbRecord r = feed("Your TzKal-Zuk kill count is: <col=ff0000>2</col>.",
			"Duration: <col=ff0000>104:31.20</col> (new personal best)");
		assertPb(r, "TzKal-Zuk", 104 * 60 + 31.2, null, 2, true);
		r = feed("Your TzKal-Zuk kill count is: <col=ff0000>3</col>.",
			"Duration: <col=ff0000>172:18</col>. Personal best: 134:52");
		assertPb(r, "TzKal-Zuk", 134 * 60 + 52, null, 3, false);
	}

	@Test
	public void hoursMinutesSeconds()
	{
		PbRecord r = feed("Your Zulrah kill count is: 1.", "Fight duration: 1:00:56.50 (new personal best).");
		assertPb(r, "Zulrah", 3656.5, null, 1, true);
	}

	@Test
	public void fightCavesWithACombatAchievementLineBetween()
	{
		PbRecord r = feed("Your TzTok-Jad kill count is: <col=ff0000>2</col>.",
			"Congratulations, you've completed a master task: <col=7f3700>Complete the Fight Caves in 25:00</col>.",
			"Duration: <col=ff0000>21:58.40</col> (new personal best)");
		assertPb(r, "TzTok-Jad", 21 * 60 + 58.4, null, 2, true);
	}

	@Test
	public void tzhaarKetRakChallenges()
	{
		PbRecord r = feed("Your completion count for TzHaar-Ket-Rak's First Challenge is: <col=ff0000>1</col>.",
			"Challenge duration: <col=ff0000>1:46.40</col> (new personal best)");
		assertPb(r, "TzHaar-Ket-Rak's First Challenge", 106.4, null, 1, true);
		r = feed("Your completion count for TzHaar-Ket-Rak's First Challenge is: <col=ff0000>3</col>.",
			"Challenge duration: <col=ff0000>1:10.00</col>. Personal best: <col=ff0000>0:59.20</col>");
		assertPb(r, "TzHaar-Ket-Rak's First Challenge", 59.2, null, 3, false);
	}

	@Test
	public void nightmareCarriesTeamSize()
	{
		PbRecord r = feed("Your Nightmare kill count is: <col=ff0000>1130</col>",
			"Team size: <col=ff0000>5 players</col> Fight duration: <col=ff0000>3:28</col> (new personal best)");
		assertPb(r, "Nightmare", 208, "5", 1130, true);
		r = feed("Your Nightmare kill count is: <col=ff0000>1,130</col>",
			"Team size: <col=ff0000>Solo</col> Fight duration: <col=ff0000>10:47</col>. Personal best: 8:44");
		assertPb(r, "Nightmare", 524, "1", 1130, false);
	}

	@Test
	public void agilityHighlightTagsAreStripped()
	{
		// the same colour-word form the game uses for some kill counts
		PbRecord r = feed("Your Vorkath kill count is: @mes_hl_red@300</col>.",
			"Fight duration: @mes_hl_red@1:02.40</col> (new personal best).");
		assertPb(r, "Vorkath", 62.4, null, 300, true);
	}

	// ---- time first, then the kill count ----

	@Test
	public void grotesqueGuardiansSendTheTimeFirst()
	{
		PbRecord r = feed("Fight duration: <col=ff0000>1:36.40</col> (new personal best)",
			"Your Grotesque Guardians kill count is: <col=ff0000>179</col>.");
		assertPb(r, "Grotesque Guardians", 96.4, null, 179, true);
		r = feed("Fight duration: <col=ff0000>2:41.40</col>. Personal best: 2:14.20",
			"Your Grotesque Guardians kill count is: <col=ff0000>32</col>.");
		assertPb(r, "Grotesque Guardians", 134.2, null, 32, false);
	}

	@Test
	public void gauntletWithThePreparationLineBetween()
	{
		PbRecord r = feed("Challenge duration: <col=ff0000>10:24.40</col> (new personal best).",
			"Preparation time: 7:09.60. Hunllef kill time: 3:15.40.",
			"Your Gauntlet completion count is: <col=ff0000>124</col>.");
		assertPb(r, "Gauntlet", 624.4, null, 124, true);
		r = feed("Corrupted challenge duration: <col=ff0000>7:59</col> (new personal best).",
			"Your Corrupted Gauntlet completion count is: <col=ff0000>12</col>.");
		assertPb(r, "Corrupted Gauntlet", 479, null, 12, true);
		r = feed("Challenge duration: <col=ff0000>10:24.20</col>. Personal best: 7:52.40.",
			"Your Gauntlet completion count is: <col=ff0000>124</col>.");
		assertPb(r, "Gauntlet", 472.4, null, 124, false);
	}

	@Test
	public void temporossSubdued()
	{
		PbRecord r = feed("Subdued in <col=ef1020>5:20.60</col> (new personal best).",
			"Your Tempoross kill count is: <col=ff0000>60</col>.");
		assertPb(r, "Tempoross", 320.6, null, 60, true);
		r = feed("Subdued in <col=ef1020>7:40</col>. Personal best: 5:38.",
			"Your Tempoross kill count is: <col=ff0000>55</col>.");
		assertPb(r, "Tempoross", 338, null, 55, false);
	}

	@Test
	public void chambersOfXericWithTeamSize()
	{
		PbRecord r = feed("<col=ef20ff>Congratulations - your raid is complete!</col><br>Team size: <col=ff0000>24+ players</col> Duration:</col> <col=ff0000>37:04.20</col> (new personal best)</col>>",
			"Your completed Chambers of Xeric count is: <col=ff0000>51</col>.");
		assertPb(r, "Chambers of Xeric", 37 * 60 + 4.2, "24+", 51, true);
		r = feed("<col=ef20ff>Congratulations - your raid is complete!</col><br>Team size: <col=ff0000>11-15 players</col> Duration:</col> <col=ff0000>23:25.40</col> Personal best: </col><col=ff0000>20:19.20</col>",
			"Your completed Chambers of Xeric count is: <col=ff0000>52</col>.");
		assertPb(r, "Chambers of Xeric", 20 * 60 + 19.2, "11-15", 52, false);
		r = feed("<col=ef20ff>Congratulations - your raid is complete!</col><br>Team size: <col=ff0000>3 players</col> Duration:</col> <col=ff0000>41:10</col> Personal best: </col><col=ff0000>40:03</col>",
			"Your completed Chambers of Xeric Challenge Mode count is: <col=ff0000>13</col>.");
		assertPb(r, "Chambers of Xeric Challenge Mode", 40 * 60 + 3, "3", 13, false);
	}

	@Test
	public void theatreOfBloodSkipsTheTotalTime()
	{
		PbRecord r = feed("Wave 'The Final Challenge' (Normal Mode) complete!<br>Duration: <col=ff0000>2:42.0</col><br>"
				+ "Theatre of Blood completion time: <col=ff0000>17:00.20</col> (new personal best)",
			"Theatre of Blood total completion time: <col=ff0000>24:40.20</col>. Personal best: 20:45.00",
			"Your completed Theatre of Blood count is: <col=ff0000>73</col>.");
		assertPb(r, "Theatre of Blood", 17 * 60 + 0.2, null, 73, true);
		assertTrue("the plugin reads the party for ToB", r.needsPartySize());
		r = feed("Wave 'The Final Challenge' (Normal Mode) complete!\nDuration: 6:37.80\nTheatre of Blood completion time: 19:26.40. Personal best: 19:24.00",
			"Theatre of Blood total completion time: 26:34.20 (new personal best)",
			"Your completed Theatre of Blood: Entry Mode count is: 5.");
		assertPb(r, "Theatre of Blood: Entry Mode", 19 * 60 + 24, null, 5, false);
	}

	@Test
	public void tombsOfAmascutAllModes()
	{
		PbRecord r = feed("Challenge complete: The Wardens. Duration: <col=ef1020>8:30</col><br>Tombs of Amascut challenge completion time: <col=ef1020>8:31</col> (new personal best)",
			"Tombs of Amascut total completion time: <col=ef1020>0:01</col> (new personal best)",
			"Your completed Tombs of Amascut count is: <col=ff0000>1</col>.");
		assertPb(r, "Tombs of Amascut", 511, null, 1, true);
		assertTrue(r.needsPartySize());
		r = feed("Challenge complete: The Wardens. Duration: <col=ef1020>9:40</col><br>Tombs of Amascut: Expert Mode challenge completion time: <col=ef1020>9:40</col>. Personal best: 8:31",
			"Tombs of Amascut total completion time: <col=ef1020>0:01</col> (new personal best)",
			"Your completed Tombs of Amascut: Expert Mode count is: <col=ff0000>1</col>.");
		assertPb(r, "Tombs of Amascut: Expert Mode", 511, null, 1, false);
		r = feed("Challenge complete: The Wardens. Duration: <col=ef1020>9:40</col><br>Tombs of Amascut: Entry Mode challenge completion time: <col=ef1020>9:40</col>. Personal best: 20:31",
			"Your completed Tombs of Amascut: Entry Mode count is: <col=ff0000>9</col>.");
		assertPb(r, "Tombs of Amascut: Entry Mode", 20 * 60 + 31, null, 9, false);
	}

	@Test
	public void fracturedArchiveCountsItsTotal()
	{
		PbRecord r = feed("The Fractured Archive: Total completion time: <col=ef1020>2:25.80</col> (new personal best)",
			"Your completed Fractured Archive count is: <col=ff0000>1</col>.");
		assertPb(r, "Fractured Archive", 145.8, null, 1, true);
	}

	// ---- Sailing: Barracuda Trials come from game state (TrialTimer), never chat ----

	@Test
	public void someoneElsesBroadcastIsNeverOurs()
	{
		// the game's clan broadcast format (CLAN_MESSAGE); the plugin doesn't read that type anyway
		assertNull(feed("Sample Name has achieved a new Tempor Tantrum Swordfish personal best: 1:41.20"));
		assertNull(feed("Another One has achieved a new Duke Sucellus personal best: 1:12"));
		// the Sailing plugin's own split lines
		assertNull(feed("[Sailing] Splits:<br>Lap: <col=ff0000>1:02.4</col><br>"));
	}

	// ---- what isn't a boss time ----

	@Test
	public void lapsWavesAndKillCountsWithoutTimesPostNothing()
	{
		assertNull(feed("Your Prifddinas Agility Course lap count is: @mes_hl_red@2</col>.",
			"Lap duration: @mes_hl_red@1:01.20</col> (new personal best)."));
		p.reset();
		assertNull(feed("Your Barrows chest count is: <col=ff0000>100</col>.", "You have opened the chest."));
		assertNull(feed("Your subdued Wintertodt count is: <col=ff0000>10</col>."));
		assertNull(feed("Wave 3 completed! Wave duration: <col=ff0000>1:10.20</col> (new personal best)"));
		assertNull(feed("Floor 5 time: <col=ff0000>2:01.00</col> (new personal best)"));
		assertNull(feed("Your reward is: <col=ff0000>1</col> x <col=ff0000>Kebab</col>."));
		assertNull(feed((String) null));
	}

	@Test
	public void aKillCountWithoutATimeIsForgottenAfterAFewTicks()
	{
		assertNull(p.onMessage("Your Kraken kill count is: 10.", tick));
		tick += PbParser.PAIR_TICKS + 1;
		assertNull("too late to belong to the Kraken", p.onMessage("Fight duration: 1:00 (new personal best).", tick));
		// but that time now waits for its own kill count
		PbRecord r = p.onMessage("Your Vorkath kill count is: 5.", tick + 1);
		assertPb(r, "Vorkath", 60, null, 5, true);
	}

	@Test
	public void aTimeWithoutAKillCountIsForgottenToo()
	{
		assertNull(p.onMessage("Fight duration: 1:36 (new personal best)", tick));
		assertNull(p.onMessage("Your Grotesque Guardians kill count is: 9.", tick + PbParser.PAIR_TICKS + 1));
	}

	@Test
	public void resetDropsAHalfSeenKill()
	{
		assertNull(p.onMessage("Your Vorkath kill count is: 5.", tick));
		p.reset();
		assertNull(p.onMessage("Fight duration: 1:00 (new personal best).", tick));
	}

	@Test
	public void eachKillPairsOnce()
	{
		PbRecord r = feed("Your Vorkath kill count is: 5.", "Fight duration: 1:00 (new personal best).");
		assertFalse(r == null);
		assertNull("the next time line waits for its own kill", p.onMessage("Fight duration: 1:01. Personal best: 1:00", tick));
	}

	// ---- helpers ----

	@Test
	public void secondsAndTeams()
	{
		assertEquals(62.4, PbParser.seconds("1:02.40"), 0.001);
		assertEquals(6271, PbParser.seconds("104:31"), 0.001);
		assertEquals(3656.5, PbParser.seconds("1:00:56.50"), 0.001);
		assertEquals(162.0, PbParser.seconds("2:42.0"), 0.001);
		assertEquals("1", PbParser.teamSize("Team size: Solo Duration"));
		assertEquals("2", PbParser.teamSize("Team size: 2 players"));
		assertEquals("24+", PbParser.teamSize("Team size: 24+ players"));
		assertNull(PbParser.teamSize("no team here"));
		assertEquals("Vorkath|", new PbRecord("Vorkath", 1, null, null, true).key());
		assertEquals("Nex|5", new PbRecord("Nex", 1, "5", null, true).key());
		assertFalse(new PbRecord("Tombs of Amascut", 1, "4", null, true).needsPartySize());
		assertFalse(new PbRecord("Vorkath", 1, null, null, true).needsPartySize());
	}
}
