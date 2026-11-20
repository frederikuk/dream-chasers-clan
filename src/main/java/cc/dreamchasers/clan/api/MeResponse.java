/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import java.util.List;
import lombok.Data;

/**
 * {@code GET /plugin/me}: who the stored token belongs to, and the names
 * verified to that Discord account (frozen ones left out; absent on older sites).
 * A sign-in that isn't verified yet (20 Nov 2026) also says {@code status}
 * {@code held} or {@code awaiting_roster}, or {@code unverified: true}; older
 * sites only ever had verified sign-ins and send neither.
 */
@Data
public class MeResponse
{
	private int apiVersion;
	private String name;
	private List<String> verified;
	private String status;
	private Boolean unverified;

	/** The sign-in isn't verified yet: everything posts, but Events waits. */
	public boolean notVerifiedYet()
	{
		return Boolean.TRUE.equals(unverified)
			|| JoinAttestResponse.HELD.equals(status) || JoinAttestResponse.AWAITING_ROSTER.equals(status);
	}
}
