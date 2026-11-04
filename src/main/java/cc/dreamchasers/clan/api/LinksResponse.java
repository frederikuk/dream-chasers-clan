/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import lombok.Data;

/** {@code GET /links}. {@code discord} is null until there is a permanent invite. */
@Data
public class LinksResponse
{
	private int apiVersion;
	private String site;
	private String events;
	private String hallOfFame;
	private String competitions;
	private String discord;
}
