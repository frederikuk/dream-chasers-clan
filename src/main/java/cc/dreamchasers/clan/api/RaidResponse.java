/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import lombok.Data;

/** The answer to {@code POST /plugin/raid}. */
@Data
public class RaidResponse
{
	private boolean ok;
	/** DRM Live has the member on a guest slot now. */
	private boolean shown;
	/** Seconds to wait before the next post (5 on stream, 60 when not). */
	private int next;
}
