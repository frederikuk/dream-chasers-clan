/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import lombok.Data;

/** The answer to {@code POST /plugin/pbs} and {@code /plugin/loots}. */
@Data
public class SubmitResponse
{
	private boolean ok;
	/** False when the site already had it (a slower time, the same drop twice). */
	private boolean stored;
	/** PBs: the member's best for that boss and team size, in seconds. */
	private Double best;
	/** Loot: what the site counted the drop as, in coins. */
	private Long value;
}
