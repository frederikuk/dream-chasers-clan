/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import java.util.Locale;

final class CacheControl
{
	private CacheControl()
	{
	}

	/**
	 * The max-age in a Cache-Control header, in seconds, or {@code fallback}
	 * if the header is missing, malformed, or says no-store/no-cache.
	 */
	static int maxAge(String header, int fallback)
	{
		if (header == null)
		{
			return fallback;
		}
		int maxAge = -1;
		for (String part : header.toLowerCase(Locale.ROOT).split(","))
		{
			String directive = part.trim();
			if (directive.equals("no-store") || directive.equals("no-cache"))
			{
				return fallback;
			}
			if (directive.startsWith("max-age="))
			{
				try
				{
					maxAge = Integer.parseInt(directive.substring("max-age=".length()).trim());
				}
				catch (NumberFormatException e)
				{
					return fallback;
				}
			}
		}
		return maxAge < 0 ? fallback : maxAge;
	}
}
