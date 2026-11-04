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

/** {@code GET /plugin/events}: every upcoming or running event, soonest first. */
@Data
public class EventsResponse
{
	private int apiVersion;
	private List<EventInfo> events;
}
