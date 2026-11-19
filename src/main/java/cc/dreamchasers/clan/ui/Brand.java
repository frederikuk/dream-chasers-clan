/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.ui;

import java.awt.Color;

/** The Dream Chasers palette (1107r), as dream-chasers.cc's CSS tokens name it. */
final class Brand
{
	static final Color VOID = new Color(0x0a0514);       // --void
	static final Color NIGHT = new Color(0x150b22);      // --panel
	static final Color ROYAL = new Color(0x6130ce);      // --gem-2
	static final Color GOLD = new Color(0xf5da84);       // --gold
	static final Color GOLD_DEEP = new Color(0xc8902e);  // --gold-deep
	static final Color TEXT = new Color(0xece6f5);       // --fg
	static final Color MUTED = new Color(0xa99cc0);      // --muted
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
