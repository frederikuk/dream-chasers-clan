/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import java.time.Instant;
import lombok.Data;

/** One clan event, as returned by the events routes. Fields may be added. */
@Data
public class EventInfo
{
	private String id;
	private String title;
	private String startsAt;
	private String endsAt;
	private String where;
	private String host;
	private String details;
	private boolean weekly;

	public Instant startInstant()
	{
		return ApiTimes.parse(startsAt);
	}

	public Instant endInstant()
	{
		return ApiTimes.parse(endsAt);
	}

	/** Running now: started and not yet ended. */
	public boolean isLive(Instant now)
	{
		Instant start = startInstant();
		Instant end = endInstant();
		return start != null && !now.isBefore(start) && (end == null || now.isBefore(end));
	}
}
