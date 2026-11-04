/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import lombok.Data;

/** {@code GET /plugin/me}: who the stored token belongs to. */
@Data
public class MeResponse
{
	private int apiVersion;
	private String name;
}
