/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import lombok.Data;

/** The answer to consent, leave and withdraw on {@code /plugin/live/*}. */
@Data
public class LiveActionResponse
{
	private boolean ok;
	private boolean consented;
	private String role;
	private String note;
}
