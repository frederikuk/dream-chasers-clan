/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import lombok.Data;

@Data
public class ErrorResponse
{
	private String error;
	private String note;
	/** A 403 saying the sign-in isn't verified yet (20 Nov 2026), not that the account is refused. */
	private Boolean unverified;
}
