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

/** {@code GET /hall-of-fame}: the Clan's Firsts board. */
@Data
public class HallOfFameResponse
{
	private int apiVersion;
	private String updatedAt;
	private List<HallOfFameFirst> firsts;
	/** Only its threshold is read: the clan's big-drop amount. */
	private Feed loots;

	@Data
	public static class Feed
	{
		private String status;
		private Long threshold;
	}

	@Data
	public static class HallOfFameFirst
	{
		private String label;
		/** boss, skill or achievement. */
		private String category;
		private String metric;
		private List<Tier> tiers;
	}

	@Data
	public static class Tier
	{
		private long threshold;
		private String label;
		private List<Holder> holders;
	}

	@Data
	public static class Holder
	{
		/** 1 is the clan's first. */
		private int pos;
		private String name;
		/** YYYY-MM-DD, or null when WiseOldMan has no date. */
		private String at;
	}
}
