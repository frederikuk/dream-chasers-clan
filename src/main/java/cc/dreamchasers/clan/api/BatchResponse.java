/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import lombok.Data;

/** {@code POST /plugin/pbs/batch}: how many of the old PBs the site kept. */
@Data
public class BatchResponse
{
	private boolean ok;
	/** PBs faster than what the site had. */
	private int stored;
	/** Unknown bosses, bad times, and ones the site already had faster. */
	private int skipped;
}
