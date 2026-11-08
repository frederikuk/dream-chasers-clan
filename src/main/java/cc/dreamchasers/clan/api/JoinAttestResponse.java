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
 * {@code POST /api/join/attest}. 200 {@code verified} carries the plugin token
 * ({@code token}, {@code name}, {@code expiresAt} as from {@code /plugin/link});
 * 202 {@code awaiting_roster} or {@code held} carries a message for the member
 * and no token. {@code GET /api/join/poll} (Verify in browser) answers in the
 * same shape, plus {@code pending} and {@code denied}.
 */
@Data
public class JoinAttestResponse
{
	public static final String VERIFIED = "verified";
	public static final String AWAITING_ROSTER = "awaiting_roster";
	public static final String HELD = "held";
	/** Poll only: the member hasn't answered on the site's page yet. */
	public static final String PENDING = "pending";
	/** Poll only: the member pressed Deny on the site's page. */
	public static final String DENIED = "denied";

	private int apiVersion;
	private String status;
	private String rsn;
	private String discordName;
	private String token;
	private String name;
	private String expiresAt;
	/** Set when the link moved to this name from an older one. */
	private String renamedFrom;
	/** Why a request is held (for logs only; the plugin shows {@code message} and never branches on this): the site's reason code. */
	private String reason;
	private String message;
}
