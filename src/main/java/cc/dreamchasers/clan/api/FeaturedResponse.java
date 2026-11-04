/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import lombok.Data;

/**
 * {@code GET /featured}: today's featured member on the clan splash.
 * {@code panelUrl} is the 450×253 image the panel draws at half size.
 * Every field is null when nobody is featured yet.
 */
@Data
public class FeaturedResponse
{
	private String rsn;
	private String panelUrl;
	private String imageUrl;
	private String since;
}
