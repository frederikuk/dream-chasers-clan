/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import lombok.Data;

/** The answer to {@code POST /plugin/roster} (officer roster sync). */
@Data
public class RosterResponse
{
	private int apiVersion;
	private boolean ok;
	/** Names the site added to the Wise Old Man group. */
	private int added;
	/** Members whose rank the site changed. */
	private int changed;
	/** Waiting requests (rank-ups, verified joins) the new roster settled. */
	private int settled;
}
