/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import lombok.Data;

/** {@code POST /api/join/peek}: whose Discord account a join code belongs to (the display name, never the id). */
@Data
public class JoinPeekResponse
{
	private int apiVersion;
	private String discordName;
	private String expiresAt;
}
