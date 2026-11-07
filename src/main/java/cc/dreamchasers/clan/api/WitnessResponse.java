/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import lombok.Data;

/** The answer to {@code POST /plugin/witness} (verify pickup and the officer's "here"). */
@Data
public class WitnessResponse
{
	public static final String VERIFIED = "verified";
	public static final String NO_MATCH = "no_match";
	public static final String NEEDS_OFFICER = "needs_officer";
	public static final String REFUSED = "refused";

	private int apiVersion;
	private boolean ok;
	/** Officers' plugins online for the pickup, this one included. */
	private int pickup;
	/** Only for a pickup. */
	private Witness witness;

	@Data
	public static class Witness
	{
		private String status;
		private String rsn;
		private String message;
	}
}
