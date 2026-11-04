/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.ui;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.LinkBrowser;
import okhttp3.HttpUrl;

/**
 * Every link the site hands the plugin goes through here before the browser
 * opens it: https only, and only to the hosts that kind of link belongs to
 * (exact match, no userinfo), and on discord.com only to invites and
 * channels. One site bug can't then steer members to an outside page that
 * looks like it came from the clan plugin. The browser gets the form that was
 * checked, never the raw string.
 */
@Slf4j
public final class SafeLinks
{
	public static final Set<String> VDO = Set.of("vdo.ninja");
	public static final Set<String> DISCORD = Set.of("discord.com", "discord.gg");
	public static final Set<String> WOM = Set.of("wiseoldman.net");
	private static final Set<String> SITE_HOSTS = Set.of("dream-chasers.cc", "www.dream-chasers.cc");
	// discord.com also serves bot-consent pages (/oauth2/authorize), which the plugin must not vouch for
	private static final Map<String, List<String>> PATHS = Map.of("discord.com", List.of("/invite/", "/channels/"));

	private final String apiScheme;
	private final String apiHost;
	private final Set<String> site;

	/** {@code apiBaseUrl} is the API base in use, so staging (or a local dev site) counts as the site too. */
	public SafeLinks(String apiBaseUrl)
	{
		HttpUrl base = apiBaseUrl == null ? null : HttpUrl.parse(apiBaseUrl);
		apiScheme = base == null ? "https" : base.scheme();
		apiHost = base == null ? null : base.host();
		Set<String> hosts = new HashSet<>(SITE_HOSTS);
		if (apiHost != null)
		{
			hosts.add(apiHost);
		}
		site = Set.copyOf(hosts);
	}

	/** dream-chasers.cc, plus the API base's own host. */
	public Set<String> site()
	{
		return site;
	}

	public boolean allowed(String url, Set<String> hosts)
	{
		return checked(url, hosts) != null;
	}

	/** The form of {@code url} that was checked, to hand the browser; null if it isn't allowed. */
	public String checked(String url, Set<String> hosts)
	{
		return checked(url, hosts, apiScheme, apiHost);
	}

	/**
	 * {@code url} as parsed, if it is https to one of {@code hosts}; null if
	 * not. The API base's own scheme is accepted for its own host only, so an
	 * http://localhost dev site still works.
	 */
	static String checked(String url, Set<String> hosts, String apiScheme, String apiHost)
	{
		HttpUrl u = url == null ? null : HttpUrl.parse(url);
		if (u == null || hosts == null || !hosts.contains(u.host()))
		{
			return null;
		}
		if (!u.username().isEmpty() || !u.password().isEmpty())
		{
			return null;
		}
		List<String> paths = PATHS.get(u.host());
		if (paths != null && paths.stream().noneMatch(u.encodedPath()::startsWith))
		{
			return null;
		}
		return u.isHttps() || (u.scheme().equals(apiScheme) && u.host().equals(apiHost)) ? u.toString() : null;
	}

	/** Opens {@code url} if it is allowed; false (and nothing opened) if not. */
	public boolean open(String url, Set<String> hosts)
	{
		String safe = checked(url, hosts);
		if (safe == null)
		{
			HttpUrl u = url == null ? null : HttpUrl.parse(url);
			// the host only: a stream link is a private credential
			log.warn("Refused link to {}", u == null ? "an unreadable address" : u.host());
			return false;
		}
		browse(safe);
		return true;
	}

	/** The browser, for a link already checked. */
	public static void browse(String url)
	{
		try
		{
			LinkBrowser.browse(url);
		}
		catch (IllegalArgumentException e)
		{
			log.warn("The browser refused a link");
		}
	}
}
