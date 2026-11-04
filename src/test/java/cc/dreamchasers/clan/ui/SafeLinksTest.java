/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Site-given links open only over https, and only to the host that kind of link belongs to. */
public class SafeLinksTest
{
	private final SafeLinks prod = new SafeLinks("https://dream-chasers.cc/api/v1");

	@Test
	public void theRightHostsOpen()
	{
		assertTrue(prod.allowed("https://dream-chasers.cc/plugin/link/abc", prod.site()));
		assertTrue(prod.allowed("https://www.dream-chasers.cc/hall-of-fame", prod.site()));
		assertTrue(prod.allowed("https://vdo.ninja/?push=x&password=y", SafeLinks.VDO));
		assertTrue(prod.allowed("https://discord.com/channels/1/2", SafeLinks.DISCORD));
		assertTrue(prod.allowed("https://discord.gg/abc", SafeLinks.DISCORD));
		assertTrue(prod.allowed("https://discord.com/invite/abc", SafeLinks.DISCORD));
		assertTrue(prod.allowed("https://wiseoldman.net/competitions/1", SafeLinks.WOM));
	}

	@Test
	public void stagingAndLocalDevCountAsTheSite()
	{
		SafeLinks staging = new SafeLinks("https://staging.dream-chasers.cc/api/v1");
		assertTrue(staging.allowed("https://staging.dream-chasers.cc/plugin/link/abc", staging.site()));
		assertFalse("not with the live base", prod.allowed("https://staging.dream-chasers.cc/x", prod.site()));

		SafeLinks dev = new SafeLinks("http://localhost:8080/api/v1");
		assertTrue(dev.allowed("http://localhost:8080/plugin/link/abc", dev.site()));
		assertFalse("http only for the API's own host", dev.allowed("http://dream-chasers.cc/x", dev.site()));
	}

	@Test
	public void everythingElseIsRefused()
	{
		assertFalse("plain http", prod.allowed("http://dream-chasers.cc/x", prod.site()));
		assertFalse("a look-alike host", prod.allowed("https://dream-chasers.cc.evil.com/", prod.site()));
		assertFalse("the name in the path", prod.allowed("https://evil.com/dream-chasers.cc", prod.site()));
		assertFalse("userinfo", prod.allowed("https://user@dream-chasers.cc/", prod.site()));
		assertFalse("userinfo hiding the host", prod.allowed("https://dream-chasers.cc@evil.com/", prod.site()));
		assertFalse(prod.allowed("https://evil.example/login", prod.site()));
		assertFalse(prod.allowed("javascript:alert(1)", prod.site()));
		assertFalse(prod.allowed("file:///C:/", prod.site()));
		assertFalse(prod.allowed(null, prod.site()));
		assertFalse(prod.allowed("", prod.site()));
		assertFalse("a stream link is not a site link", prod.allowed("https://vdo.ninja/?push=x", prod.site()));
		assertFalse("a Discord link is not a site link", prod.allowed("https://discord.gg/abc", prod.site()));
		assertFalse("a site link is not a stream link", prod.allowed("https://dream-chasers.cc/", SafeLinks.VDO));
		assertFalse("refused links never reach the browser", prod.open("https://evil.example/login", prod.site()));
	}

	@Test
	public void discordComOnlyForInvitesAndChannels()
	{
		// a real Discord consent page for someone else's bot, which the plugin would seem to vouch for
		assertFalse(prod.allowed("https://discord.com/oauth2/authorize?client_id=1&scope=bot%20guilds.join", SafeLinks.DISCORD));
		assertFalse(prod.allowed("https://discord.com/", SafeLinks.DISCORD));
		assertFalse(prod.allowed("https://discord.com/invitex", SafeLinks.DISCORD));
		assertFalse("dot segments resolve before the check", prod.allowed("https://discord.com/invite/../oauth2/authorize", SafeLinks.DISCORD));
		assertFalse(prod.allowed("https://discord.com/channels/%2e%2e/oauth2/authorize", SafeLinks.DISCORD));
	}

	@Test
	public void theBrowserGetsTheCheckedForm()
	{
		assertEquals("https://dream-chasers.cc/plugin", prod.checked("  https://dream-chasers.cc/plugin ", prod.site()));
		assertEquals("https://dream-chasers.cc/a/b", prod.checked("https://dream-chasers.cc\\a\\b", prod.site()));
		assertEquals("https://vdo.ninja/?push=x&password=y", prod.checked("https://vdo.ninja/?push=x&password=y", SafeLinks.VDO));
		assertNull(prod.checked("https://evil.example/", prod.site()));
	}
}
