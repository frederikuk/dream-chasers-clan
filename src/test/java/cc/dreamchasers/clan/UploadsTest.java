/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.google.gson.JsonObject;
import cc.dreamchasers.clan.loot.ClogTracker;
import cc.dreamchasers.clan.loot.LootPicker;
import cc.dreamchasers.clan.pb.PbRecord;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class UploadsTest
{
	private static final Instant AT = Instant.parse("2026-11-02T21:15:00Z");

	@Test
	public void pbBodyMatchesTheSiteRoute()
	{
		assertEquals("{\"boss\":\"Chambers of Xeric\",\"timeSeconds\":1500.2,\"teamSize\":\"3\",\"kc\":51,"
				+ "\"isPb\":true,\"at\":\"2026-11-02T21:15:00Z\",\"rsn\":\"Maho z h\"}",
			Uploads.pb(new PbRecord("Chambers of Xeric", 1500.2, "3", 51, true), "Maho z h", AT, null).toString());
		assertEquals("{\"boss\":\"Vorkath\",\"timeSeconds\":62.4,\"isPb\":false,\"at\":\"2026-11-02T21:15:00Z\",\"rsn\":\"Lomaru\"}",
			Uploads.pb(new PbRecord("Vorkath", 62.4, null, null, false), "Lomaru", AT, null).toString());
	}

	@Test
	public void lootBodyMatchesTheSiteRoute()
	{
		assertEquals("{\"kind\":\"drop\",\"source\":\"Theatre of Blood\",\"items\":[{\"id\":22486,\"name\":\"Scythe of vitur (uncharged)\","
				+ "\"qty\":1,\"priceEach\":1200000000}],\"at\":\"2026-11-02T21:15:00Z\",\"rsn\":\"Maho z h\"}",
			Uploads.drop("Theatre of Blood", Arrays.asList(new LootPicker.Picked(22486, "Scythe of vitur (uncharged)", 1, 1_200_000_000L)),
				"Maho z h", AT, null).toString());
	}

	@Test
	public void clogBodyMatchesTheSiteRoute()
	{
		assertEquals("{\"kind\":\"clog\",\"source\":\"Zulrah\",\"items\":[{\"name\":\"Pet snakeling\",\"id\":12921,"
				+ "\"qty\":1,\"priceEach\":5}],\"at\":\"2026-11-02T21:15:00Z\",\"rsn\":\"Lomaru\"}",
			Uploads.clog(new ClogTracker.Clog("Pet snakeling", "Zulrah", 12921, 1, 5L), "Lomaru", AT, null).toString());
		assertEquals("{\"kind\":\"clog\",\"items\":[{\"name\":\"Scroll sack\",\"qty\":1}],"
				+ "\"at\":\"2026-11-02T21:15:00Z\",\"rsn\":\"Lomaru\"}",
			Uploads.clog(new ClogTracker.Clog("Scroll sack", null, null, 1, null), "Lomaru", AT, null).toString());
	}

	private static final long HASH = 8_765_432_109_876L;
	private static final String DIGEST = OwnClan.digest(HASH);

	private static void carriesDigestNotHash(JsonObject body)
	{
		assertEquals(DIGEST, body.get("digest").getAsString());
		assertFalse(body.toString(), body.toString().contains(Long.toString(HASH)));
	}

	@Test
	public void everyFeedPostCarriesTheDigestAndNeverTheRawHash()
	{
		carriesDigestNotHash(Uploads.pb(new PbRecord("Vorkath", 62.4, null, null, false), "Lomaru", AT, DIGEST));
		carriesDigestNotHash(Uploads.drop("Zulrah", Arrays.asList(new LootPicker.Picked(12922, "Tanzanite fang", 1, 1_000_000L)),
			"Lomaru", AT, DIGEST));
		carriesDigestNotHash(Uploads.clog(new ClogTracker.Clog("Pet snakeling", "Zulrah", 12921, 1, 5L), "Lomaru", AT, DIGEST));
		carriesDigestNotHash(Uploads.batch(Collections.singletonList(new PbRecord("Vorkath", 62.4, null, null, false)), "Lomaru", DIGEST));
		// the digest is the top-level field the site reads
		assertTrue(Uploads.pb(new PbRecord("Vorkath", 62.4, null, null, false), "Lomaru", AT, DIGEST).toString()
			.endsWith(",\"rsn\":\"Lomaru\",\"digest\":\"" + DIGEST + "\"}"));
	}

	@Test
	public void batchBodyMatchesTheSiteRoute()
	{
		assertEquals("{\"rsn\":\"Lomaru\",\"pbs\":[{\"boss\":\"Chambers of Xeric\",\"timeSeconds\":1500.2,\"teamSize\":\"3\"},"
				+ "{\"boss\":\"Vorkath\",\"timeSeconds\":62.4}]}",
			Uploads.batch(Arrays.asList(new PbRecord("Chambers of Xeric", 1500.2, "3", null, true),
				new PbRecord("Vorkath", 62.4, null, null, true)), "Lomaru", null).toString());
	}
}
