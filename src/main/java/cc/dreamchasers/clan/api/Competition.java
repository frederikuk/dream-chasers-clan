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

@Data
public class Competition
{
	private long id;
	private String title;
	private String metric;
	private String startsAt;
	private String endsAt;
	private int participants;
	/** Only on raid weeks that combine modes; each standing then has gains per part. */
	private List<CompetitionPart> parts;
	private List<Standing> standings;
	private String url;
	private String wom;
}
