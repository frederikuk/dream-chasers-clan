/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import lombok.Data;

/** {@code GET /competition}. {@code status} is ongoing, upcoming, finished or none. */
@Data
public class CompetitionResponse
{
	private int apiVersion;
	private String status;
	private Competition competition;
}
