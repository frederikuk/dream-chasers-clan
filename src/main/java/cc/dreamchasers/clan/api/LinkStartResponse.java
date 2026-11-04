/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import lombok.Data;

/** {@code POST /plugin/link/start}: a browser-approval request. */
@Data
public class LinkStartResponse
{
	private String request;
	/** Proves to the site that the poll comes from this plugin; never shown. */
	private String pollKey;
	/** Four characters the member matches against the site's consent page. */
	private String code;
	private String url;
	private String expiresAt;
	private int pollSeconds;
}
