/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.ui;

import java.awt.Color;

/** The Dream Chasers palette, as on dream-chasers.cc. */
final class Brand
{
	static final Color VOID = new Color(0x060013);
	static final Color NIGHT = new Color(0x18003f);
	static final Color ROYAL = new Color(0x5d1895);
	static final Color LILAC = new Color(0xa737fc);
	static final Color GOLD = new Color(0xf5da84);
	static final Color GOLD_DEEP = new Color(0xc8902e);
	static final Color TEXT = new Color(0xece6f2);
	static final Color MUTED = new Color(0xb6a2b8);
	static final Color LIVE = new Color(0x5fd38a);
	static final Color WARN = new Color(0xe8a0a0);

	private Brand()
	{
	}

	static String hex(Color c)
	{
		return String.format("#%06x", c.getRGB() & 0xffffff);
	}
}
