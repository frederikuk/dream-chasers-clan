/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import lombok.Value;

/**
 * A GET route on the site's v1 API. {@code minMaxAgeSeconds} is a floor on
 * how long a response is kept, whatever the server's Cache-Control says.
 */
@Value
public class Endpoint<T>
{
	public static final Endpoint<CompetitionResponse> COMPETITION =
		new Endpoint<>("/competition", CompetitionResponse.class, false, 60);
	public static final Endpoint<HallOfFameResponse> HALL_OF_FAME =
		new Endpoint<>("/hall-of-fame", HallOfFameResponse.class, false, 60);
	public static final Endpoint<LinksResponse> LINKS =
		new Endpoint<>("/links", LinksResponse.class, false, 60);
	/**
	 * Members-only events, through the plugin's token. Kept at least 5 minutes
	 * to keep the load on the site low.
	 */
	public static final Endpoint<EventsResponse> EVENTS =
		new Endpoint<>("/plugin/events", EventsResponse.class, true, 300);
	public static final Endpoint<MeResponse> ME =
		new Endpoint<>("/plugin/me", MeResponse.class, true, 300);
	/**
	 * DRM Live: consent text and the show's state. Polled at most every 30 s
	 * while the section is on screen, and fetched once on start-up, sign-in and
	 * turning Join DRM Live on; the site caches the show's state for 30 s too.
	 */
	public static final Endpoint<LiveResponse> LIVE =
		new Endpoint<>("/plugin/live", LiveResponse.class, true, 30);
	/**
	 * The featured member on the clan splash. The site edge-caches it for
	 * an hour and the pick changes once a day, so it's kept an hour here too.
	 */
	public static final Endpoint<FeaturedResponse> FEATURED =
		new Endpoint<>("/featured", FeaturedResponse.class, false, 3600);
	/**
	 * The member's own splash entry and why a model was rejected. Kept five
	 * minutes, not an hour, so "You're in the pool" shows soon after the
	 * render. At most 12 GETs an hour per open panel, opted-in members only.
	 */
	public static final Endpoint<ModelStatusResponse> MODEL =
		new Endpoint<>("/plugin/model", ModelStatusResponse.class, true, 300);

	/**
	 * The clan's tile packs. Public and edge-cached for 10 minutes at the
	 * site; packs change rarely, so the panel keeps them an hour.
	 */
	public static final Endpoint<TilesResponse> TILES =
		new Endpoint<>("/plugin/tiles", TilesResponse.class, false, 3600);

	String path;
	Class<T> type;
	boolean authenticated;
	int minMaxAgeSeconds;
}
