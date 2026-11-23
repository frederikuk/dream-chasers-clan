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

/** {@code GET /competition}. {@code status} is ongoing, upcoming, finished or none. */
@Data
public class CompetitionResponse
{
	private int apiVersion;
	private String status;
	private Competition competition;
	/**
	 * The bosses, as the game names them in its kill-count lines, that Live
	 * competition KC sends a count for right now; empty (or absent, on an older
	 * site) when no clan competition is running.
	 */
	private List<String> kcBosses;
	/**
	 * Whether any clan competition (any metric, skills too) is running now, so
	 * a logout or hop should send the logout notice; null on a site from
	 * before 23 Nov 2026, which only says so through {@link #kcBosses}.
	 */
	private Boolean jitLogout;
	/**
	 * The running clan competition's skill, as Wise Old Man names the metric
	 * ("attack", "runecrafting", "sailing"), that Live competition XP sends
	 * the player's XP for; null when no skill competition is running (and on a
	 * site from before D-231123-2).
	 */
	private String xpSkill;
	/**
	 * How often, in seconds, Live competition XP may send; the plugin never
	 * goes below 120 whatever this says, and uses 300 when it is absent.
	 */
	private Integer xpEvery;
}
