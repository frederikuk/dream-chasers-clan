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
 * The answer to {@code POST /plugin/kc}. A 200 with {@code stored} false is
 * still an answer (no competition for that boss, or a lower count than the
 * site has): nothing is sent again for it.
 */
@Data
public class KcResponse
{
	private boolean ok;
	private boolean stored;
	/** Why it wasn't stored, e.g. "no-competition"; null when stored. */
	private String reason;
}
