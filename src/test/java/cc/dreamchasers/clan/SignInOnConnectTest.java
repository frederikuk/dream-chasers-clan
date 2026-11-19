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
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.events.GameTick;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import org.junit.Test;

/**
 * Ticking Connect in the settings while signed out is the sign-in: a later
 * game tick that is logged in with a Dream Chasers clan record opens the
 * browser approval (19 Nov 2026: the sign-in boxes left the sidebar).
 */
public class SignInOnConnectTest
{
	private static final OwnClan.Reading IN_CLAN = new OwnClan.Reading(OwnClan.State.IN_CLAN, "DRM Chasers", "Tadeko", "d1g3st");
	private static final OwnClan.Reading OTHER_CLAN = new OwnClan.Reading(OwnClan.State.NOT_IN_CLAN, "Iron Men", null, null);

	private static class Scripted extends DreamChasersClanPlugin
	{
		boolean connect = true;
		boolean signedIn;
		boolean in = true;
		boolean running;
		OwnClan.Reading reading = OwnClan.Reading.UNKNOWN;
		int starts;
		final List<String> blocked = new ArrayList<>();

		@Override
		boolean connectOn()
		{
			return connect;
		}

		@Override
		boolean isSignedIn()
		{
			return signedIn;
		}

		@Override
		boolean loggedIn()
		{
			return in;
		}

		@Override
		OwnClan.Reading ownClanReading()
		{
			return reading;
		}

		@Override
		boolean signInFlowRunning()
		{
			return running;
		}

		@Override
		public void verifyStart()
		{
			starts++;
		}

		@Override
		void signInBlocked(String blocker)
		{
			blocked.add(blocker);
		}

		@Override
		void clientTicks()
		{
			// no game here: the own clan record is scripted in reading
		}

		/** The member (or a profile switch) changes Connect: the real ConfigChanged route. */
		void connect(String oldValue, String newValue)
		{
			connect = Boolean.parseBoolean(newValue);
			ConfigChanged e = new ConfigChanged();
			e.setGroup(DreamChasersClanConfig.GROUP);
			e.setKey(DreamChasersClanConfig.CONNECT_KEY);
			e.setOldValue(oldValue);
			e.setNewValue(newValue);
			onConfigChanged(e);
		}

		void tickOn()
		{
			connect(null, "true");
		}

		void tick()
		{
			onGameTick(new GameTick());
		}
	}

	@Test
	public void nothingIsDueAtStartUp()
	{
		Scripted p = new Scripted();
		p.reading = IN_CLAN;
		p.connect("false", "false");     // another setting's save repeats Connect's value: not a tick of it
		p.tick();
		assertFalse(p.signInDue());
		assertEquals("never at start-up, only after the member ticks Connect", 0, p.starts);
	}

	@Test
	public void tickingConnectSignsInOnTheFirstTickThatCan()
	{
		Scripted p = new Scripted();
		p.in = false;
		p.reading = OwnClan.Reading.LOGGED_OUT;
		p.tickOn();
		assertTrue(p.signInDue());
		p.tick();     // at the login screen: wait
		p.in = true;
		p.reading = OwnClan.Reading.UNKNOWN;
		p.tick();     // logged in, the clan record not loaded yet: wait
		assertEquals(0, p.starts);
		assertTrue(p.signInDue());

		p.reading = IN_CLAN;
		p.tick();
		assertEquals("the same start as the menu's Sign in with Discord", 1, p.starts);
		assertFalse(p.signInDue());
		p.tick();
		assertEquals("once", 1, p.starts);
		assertEquals(List.of(), p.blocked);
	}

	@Test
	public void onlyAChangeFromOffToOnArmsIt()
	{
		Scripted p = new Scripted();
		p.reading = IN_CLAN;
		p.connect("true", "true");
		p.tick();
		assertEquals("already on: nothing ticked", 0, p.starts);
		p.connect(null, "true");     // unset (off by default) to on
		p.tick();
		assertEquals(1, p.starts);
	}

	@Test
	public void aProfileSwitchIsNotTheMembersTick()
	{
		Scripted p = new Scripted();
		p.reading = IN_CLAN;
		// RuneLite posts ConfigChanged for each differing setting, then ProfileChanged
		p.connect("false", "true");
		p.onProfileChanged(new ProfileChanged());
		p.tick();
		assertEquals("no browser for a profile that just has Connect on", 0, p.starts);
		assertFalse(p.signInDue());
	}

	@Test
	public void stoppingThePluginDropsIt() throws Exception
	{
		Scripted p = new Scripted();
		p.in = false;
		p.reading = OwnClan.Reading.LOGGED_OUT;
		p.tickOn();     // at the login screen: still waiting
		assertTrue(p.signInDue());
		p.shutDown();     // RuneLite reuses this instance when the plugin is turned on again
		assertFalse(p.signInDue());
		p.in = true;
		p.reading = IN_CLAN;
		p.tick();
		assertEquals("not at the next start-up either", 0, p.starts);
	}

	@Test
	public void aSignedInMemberIsNotAskedAgain()
	{
		Scripted p = new Scripted();
		p.signedIn = true;
		p.reading = IN_CLAN;
		p.tickOn();
		assertFalse(p.signInDue());
		p.tick();
		assertEquals(0, p.starts);

		// signed in on another route between the tick of Connect and the game tick
		Scripted q = new Scripted();
		q.tickOn();
		q.signedIn = true;
		q.reading = IN_CLAN;
		q.tick();
		assertEquals(0, q.starts);
		assertFalse(q.signInDue());
	}

	@Test
	public void turningConnectOffClearsIt()
	{
		Scripted p = new Scripted();
		p.tickOn();
		p.connect("true", "false");
		assertFalse(p.signInDue());
		p.reading = IN_CLAN;
		p.tick();
		assertEquals(0, p.starts);

		Scripted q = new Scripted();
		q.tickOn();
		q.connect = false;     // off again before the tick
		q.reading = IN_CLAN;
		q.tick();
		assertEquals(0, q.starts);
		assertFalse(q.signInDue());
	}

	@Test
	public void aBlockingClanRecordEndsTheWait()
	{
		Scripted p = new Scripted();
		p.tickOn();
		p.reading = OTHER_CLAN;
		p.tick();
		assertEquals("nothing starts", 0, p.starts);
		assertEquals("the status line says why", List.of(OwnClan.blocker(OTHER_CLAN)), p.blocked);
		assertFalse(p.signInDue());
		p.reading = IN_CLAN;
		p.tick();
		assertEquals("not later either: the menu is the way now", 0, p.starts);
	}

	@Test
	public void noClanRecordAtAllEndsTheWaitToo()
	{
		Scripted p = new Scripted();
		p.tickOn();
		p.reading = OwnClan.Reading.UNKNOWN;     // logged in, in no clan: the record never loads
		for (int i = 1; i < DreamChasersClanPlugin.SIGN_IN_UNKNOWN_LIMIT; i++)
		{
			p.tick();
		}
		assertTrue("still giving the record time to load", p.signInDue());
		assertEquals(List.of(), p.blocked);
		p.tick();
		assertFalse(p.signInDue());
		assertEquals(List.of(OwnClan.blocker(OwnClan.Reading.UNKNOWN)), p.blocked);

		p.reading = IN_CLAN;     // joins DRM Chasers an hour later
		p.tick();
		assertEquals("no browser out of nowhere", 0, p.starts);
	}

	@Test
	public void theLoginScreenRestartsTheRecordsTime()
	{
		Scripted p = new Scripted();
		p.tickOn();
		for (int i = 1; i < DreamChasersClanPlugin.SIGN_IN_UNKNOWN_LIMIT; i++)
		{
			p.tick();
		}
		p.in = false;     // logs out and back in: a new wait for the record
		p.tick();
		p.in = true;
		p.tick();
		assertTrue(p.signInDue());
		p.reading = IN_CLAN;
		p.tick();
		assertEquals(1, p.starts);
	}

	@Test
	public void waitsForASignInAlreadyRunning()
	{
		Scripted p = new Scripted();
		p.tickOn();
		p.reading = IN_CLAN;
		p.running = true;
		p.tick();
		assertEquals(0, p.starts);
		assertTrue(p.signInDue());
		p.running = false;
		p.tick();
		assertEquals(1, p.starts);
	}
}
