/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan;

import static org.junit.Assert.assertEquals;
import cc.dreamchasers.clan.api.DrmApiClient;
import cc.dreamchasers.clan.api.FakeHttp;
import cc.dreamchasers.clan.loot.UnlockWatch;
import com.google.gson.Gson;
import java.time.Instant;
import org.junit.Test;

/** Blorva and Radiant oathplate go to the site's own route, {@code POST /plugin/unlock}, not {@code /plugin/loots}. */
public class UnlockPostTest
{
	private static final Instant AT = Instant.parse("2026-11-23T12:00:00Z");

	@Test
	public void anUnlockGoesToThePluginUnlockRoute() throws Exception
	{
		FakeHttp http = new FakeHttp();
		http.reply(200, "{\"ok\":true,\"stored\":true}", null);
		DrmApiClient api = new DrmApiClient(http.client, new Gson(), "https://dream-chasers.cc/api/v1", "UA", () -> "drm_pt_x",
			new FakeHttp.MutableClock(AT));
		DreamChasersClanPlugin p = new DreamChasersClanPlugin()
		{
			@Override
			DrmApiClient api()
			{
				return api;
			}
		};
		UnlockWatch.Unlock u = UnlockWatch.WATCHED.get(0);
		p.postUnlock(u, Uploads.withAcct(Uploads.unlock(u.getKey(), "Lomaru", AT, "dig"), 1));
		for (int i = 0; i < 250 && http.requests.isEmpty(); i++)
		{
			Thread.sleep(20);
		}
		assertEquals(1, http.requests.size());
		FakeHttp.Recorded post = http.requests.get(0);
		assertEquals("POST", post.request.method());
		assertEquals("https://dream-chasers.cc/api/v1/plugin/unlock", post.request.url().toString());
		assertEquals("Bearer drm_pt_x", post.request.header("Authorization"));
		assertEquals("{\"unlock\":\"blorva\",\"at\":\"2026-11-23T12:00:00Z\",\"rsn\":\"Lomaru\",\"digest\":\"dig\",\"acct\":1}", post.body);
	}
}
