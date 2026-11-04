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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.Test;

/** Verified join: the own-clan-record gate and the account digest. */
public class OwnClanTest
{
	private static final long HASH = 1234567890123456789L;

	@Test
	public void digestIsLowerHexSha256OfTheSaltedHash() throws Exception
	{
		String d = OwnClan.digest(HASH);
		assertTrue(d, d.matches("[0-9a-f]{64}"));
		byte[] want = MessageDigest.getInstance("SHA-256")
			.digest(("drm-clan-v1|" + HASH).getBytes(StandardCharsets.UTF_8));
		StringBuilder hex = new StringBuilder();
		for (byte b : want)
		{
			hex.append(String.format("%02x", b));
		}
		assertEquals(hex.toString(), d);
		// a fixed vector, so the site's implementation can be checked against it
		assertEquals("fa0c8dbc3532e888cd84925bf7afa27bb74fb4c2ab1e27336be590c006b20679", d);
		assertFalse(d.contains(Long.toString(HASH)));
	}

	@Test
	public void digestIsStableAndDiffersPerAccount()
	{
		assertEquals(OwnClan.digest(42), OwnClan.digest(42));
		assertFalse(OwnClan.digest(42).equals(OwnClan.digest(43)));
		assertNotNull(OwnClan.digest(Long.MIN_VALUE));
		assertTrue(OwnClan.digest(-5).matches("[0-9a-f]{64}"));
	}

	@Test
	public void noDigestBeforeLogin()
	{
		assertNull(OwnClan.digest(-1));
	}

	@Test
	public void clanRecordNotLoadedIsUnknown()
	{
		OwnClan.Reading r = OwnClan.judge(false, null, "Maho z h", false, HASH);
		assertEquals(OwnClan.State.UNKNOWN, r.getState());
		assertNull(r.getDigest());
		assertEquals("Open your clan tab or wait a few seconds after login", OwnClan.blocker(r.getState()));
	}

	@Test
	public void noLocalPlayerYetIsUnknown()
	{
		assertEquals(OwnClan.State.UNKNOWN, OwnClan.judge(true, "DRM Chasers", null, true, HASH).getState());
	}

	@Test
	public void accountHashNotKnownYetIsUnknown()
	{
		assertEquals(OwnClan.State.UNKNOWN, OwnClan.judge(true, "DRM Chasers", "Maho z h", true, -1).getState());
	}

	@Test
	public void anotherClanIsNotInClan()
	{
		OwnClan.Reading r = OwnClan.judge(true, "Some Other Clan", "Maho z h", true, HASH);
		assertEquals(OwnClan.State.NOT_IN_CLAN, r.getState());
		assertNull(r.getRsn());
		assertNull(r.getDigest());
		assertEquals("Join the clan in game first", OwnClan.blocker(r.getState()));
	}

	@Test
	public void notFoundInOwnRecordIsNotInClan()
	{
		assertEquals(OwnClan.State.NOT_IN_CLAN, OwnClan.judge(true, "DRM Chasers", "Maho z h", false, HASH).getState());
	}

	@Test
	public void drmChasersIgnoringCaseAndSpacingIsInClan()
	{
		for (String name : new String[]{"DRM Chasers", "drm chasers", " DRM CHASERS ", "DRM\u00A0Chasers", "DRM  Chasers"})
		{
			OwnClan.Reading r = OwnClan.judge(true, name, "Maho z h", true, HASH);
			assertEquals(name, OwnClan.State.IN_CLAN, r.getState());
			assertEquals("Maho z h", r.getRsn());
			assertEquals(OwnClan.digest(HASH), r.getDigest());
			assertNull(OwnClan.blocker(r.getState()));
		}
		assertFalse(OwnClan.isDreamChasers("DRM Chasers 2"));
		// the WOM group's display name is not the in-game clan
		assertFalse(OwnClan.isDreamChasers("DREAM Chasers"));
		assertFalse(OwnClan.isDreamChasers("Dream Chasers"));
		assertFalse(OwnClan.isDreamChasers(null));
	}
}
