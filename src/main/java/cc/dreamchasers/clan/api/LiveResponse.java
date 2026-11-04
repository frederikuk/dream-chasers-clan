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

/** {@code GET /plugin/live}: the stream consent text, whether it's agreed, and the show's state. */
@Data
public class LiveResponse
{
	private int apiVersion;
	private Consent consent;
	private boolean consented;
	private String consentedAt;
	private Status status;

	@Data
	public static class Consent
	{
		private String version;
		private String title;
		private List<String> lines;
	}

	@Data
	public static class Status
	{
		/** "off", "open" (taking guests) or "live". */
		private String show;
		private int slotsFree;
		private String voiceUrl;
		private String note;
	}
}
