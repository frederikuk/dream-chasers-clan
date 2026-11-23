/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import lombok.Data;

/**
 * The answer to {@code POST /plugin/xp}. A 200 with {@code stored} false is
 * still an answer (no competition for that skill, a lower XP than the site
 * has, or the day's cap): that XP isn't sent again.
 */
@Data
public class XpResponse
{
	private boolean ok;
	private boolean stored;
	/** Why it wasn't stored, e.g. "no-competition" or "cap"; null when stored. */
	private String reason;
	/** With "cap": when timed sends may start again (ISO-8601, the next UTC day); null otherwise. */
	private String until;
}
