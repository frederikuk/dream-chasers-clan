/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import com.google.gson.Gson;
import java.time.LocalDate;
import java.util.List;
import org.junit.Test;

public class ParsingTest
{
	@Test
	public void pbBatchReplyCountsAreNumbers()
	{
		// the site answers {ok, stored: <count>, skipped: <count>}: counts, not
		// SubmitResponse's boolean
		BatchResponse r = new com.google.gson.Gson().fromJson("{\"apiVersion\":1,\"ok\":true,\"stored\":60,\"skipped\":4}", BatchResponse.class);
		assertEquals(60, r.getStored());
		assertEquals(4, r.getSkipped());
	}

	@Test
	public void cacheControlMaxAge()
	{
		assertEquals(300, CacheControl.maxAge("public, max-age=300", 7));
		assertEquals(60, CacheControl.maxAge("max-age=60,public", 7));
		assertEquals(0, CacheControl.maxAge("private, max-age=0", 7));
		assertEquals(7, CacheControl.maxAge(null, 7));
		assertEquals(7, CacheControl.maxAge("public", 7));
		assertEquals(7, CacheControl.maxAge("no-store", 7));
		assertEquals(7, CacheControl.maxAge("max-age=abc", 7));
		assertEquals(120, CacheControl.maxAge("Public, Max-Age=120", 7));
	}

	@Test
	public void codesAreNormalised()
	{
		assertEquals("DRM-7KQ4-P2XM", DrmApiClient.normalizeCode(" drm-7kq4-p2xm "));
		assertEquals("DRM-7KQ4-P2XM", DrmApiClient.normalizeCode("drm 7kq4 p2xm"));
		assertEquals("DRM-7KQ4-P2XM", DrmApiClient.normalizeCode("7KQ4P2XM"));
		assertEquals("DRM-7KQ4-P2XM", DrmApiClient.normalizeCode("DRM—7KQ4—P2XM"));
		assertEquals("ABC", DrmApiClient.normalizeCode(" abc "));
		assertEquals("", DrmApiClient.normalizeCode(null));
	}

	@Test
	public void timesTolerateJunk()
	{
		assertNull(ApiTimes.parse(null));
		assertNull(ApiTimes.parse(""));
		assertNull(ApiTimes.parse("next tuesday"));
		assertEquals(LocalDate.of(2025, 1, 2), ApiTimes.parseDate("2025-01-02"));
		assertNull(ApiTimes.parseDate("2025-13-02"));
	}

	@Test
	public void eventWithBadDatesIsNeverLive()
	{
		EventInfo e = new Gson().fromJson("{\"title\":\"x\",\"startsAt\":\"soon\"}", EventInfo.class);
		assertNull(e.startInstant());
		assertEquals(false, e.isLive(java.time.Instant.now()));
	}

	@Test
	public void recentFirstsFromFixture()
	{
		HallOfFameResponse hof = new Gson().fromJson(FakeHttp.fixture("hall-of-fame.json"), HallOfFameResponse.class);

		List<HallOfFameDigest.RecentFirst> firsts = HallOfFameDigest.recentFirsts(hof, 8);

		assertEquals(8, firsts.size());
		for (int i = 1; i < firsts.size(); i++)
		{
			// newest first
			assertEquals(true, !firsts.get(i).getDate().isAfter(firsts.get(i - 1).getDate()));
		}
	}

	@Test
	public void recentFirstsOnlyCountPositionOneWithADate()
	{
		String json = "{\"firsts\":["
			+ "{\"label\":\"Zulrah\",\"category\":\"boss\",\"tiers\":["
			+ "  {\"threshold\":500,\"label\":\"500\",\"holders\":["
			+ "    {\"pos\":1,\"name\":\"Alice\",\"at\":\"2026-10-01\"},"
			+ "    {\"pos\":2,\"name\":\"Bob\",\"at\":\"2026-10-30\"}]},"
			+ "  {\"threshold\":1000,\"label\":\"1000\",\"holders\":["
			+ "    {\"pos\":1,\"name\":\"Old\",\"at\":null}]}]},"
			+ "{\"label\":\"Slayer\",\"category\":\"skill\",\"tiers\":["
			+ "  {\"threshold\":13034431,\"label\":\"99\",\"holders\":[{\"pos\":1,\"name\":\"Cara\",\"at\":\"2026-10-20\"}]},"
			+ "  {\"threshold\":200000000,\"label\":\"200M\",\"holders\":[{\"pos\":1,\"name\":\"Dan\",\"at\":\"2026-10-25\"}]}]},"
			+ "{\"label\":\"Quest cape\",\"category\":\"achievement\",\"tiers\":["
			+ "  {\"threshold\":1,\"label\":\"1\",\"holders\":[{\"pos\":1,\"name\":\"Eve\",\"at\":\"2026-09-01\"}]}]},"
			+ "{\"label\":\"Broken\",\"category\":\"boss\",\"tiers\":null}"
			+ "]}";
		HallOfFameResponse hof = new Gson().fromJson(json, HallOfFameResponse.class);

		List<HallOfFameDigest.RecentFirst> firsts = HallOfFameDigest.recentFirsts(hof, 10);

		assertEquals(4, firsts.size());
		assertEquals("Dan", firsts.get(0).getName());
		assertEquals("Slayer 200M XP", firsts.get(0).getAchievement());
		assertEquals("Slayer 99", firsts.get(1).getAchievement());
		assertEquals("Zulrah 500 KC", firsts.get(2).getAchievement());
		assertEquals("Alice", firsts.get(2).getName());
		assertEquals("Quest cape", firsts.get(3).getAchievement());

		assertEquals(2, HallOfFameDigest.recentFirsts(hof, 2).size());
		assertEquals(0, HallOfFameDigest.recentFirsts(null, 5).size());
	}
}
