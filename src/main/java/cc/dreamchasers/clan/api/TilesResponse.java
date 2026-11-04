/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import com.google.gson.JsonArray;
import java.util.List;
import lombok.Data;

/**
 * {@code GET /plugin/tiles}: the clan's ground-marker packs, published by
 * officers on the site. {@code markers} is core Ground Markers' own export
 * format, checked strictly by the site; the panel only copies it.
 */
@Data
public class TilesResponse
{
	private List<Pack> packs;

	@Data
	public static class Pack
	{
		private String id;
		private String name;
		private String description;
		private int count;
		private String by;
		private String at;
		private JsonArray markers;
	}
}
