/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import lombok.Data;

/** {@code GET /plugin/link/poll}: pending, denied, or allowed with a token. */
@Data
public class LinkPollResponse
{
	private String status;
	private String token;
	private String name;
	private String expiresAt;
}
