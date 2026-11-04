/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import lombok.Data;

/** {@code POST /plugin/link}: a link code swapped for a long-lived token. */
@Data
public class LinkResponse
{
	private int apiVersion;
	private String token;
	private String name;
	private String expiresAt;
}
