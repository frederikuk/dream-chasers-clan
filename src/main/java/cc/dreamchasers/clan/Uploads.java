/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan;

import cc.dreamchasers.clan.loot.ClogTracker;
import cc.dreamchasers.clan.loot.LootPicker;
import cc.dreamchasers.clan.pb.PbRecord;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.List;

/**
 * The JSON bodies of {@code POST /plugin/pbs} and {@code /plugin/pbs/batch}
 * and {@code /plugin/loots}. Each carries {@code digest}, the salted
 * SHA-256 of the account hash ({@link OwnClan#digest(long)}), so the site can
 * check the post comes from the account that verified; it is left out only
 * before login (null). The raw account hash is never sent.
 */
final class Uploads
{
	private Uploads()
	{
	}

	/** {boss, timeSeconds, teamSize?, kc?, isPb, at, rsn, digest?} */
	static JsonObject pb(PbRecord r, String rsn, Instant at, String digest)
	{
		JsonObject o = new JsonObject();
		o.addProperty("boss", r.getBoss());
		o.addProperty("timeSeconds", r.getSeconds());
		if (r.getTeamSize() != null)
		{
			o.addProperty("teamSize", r.getTeamSize());
		}
		if (r.getKc() != null)
		{
			o.addProperty("kc", r.getKc());
		}
		o.addProperty("isPb", r.isPb());
		o.addProperty("at", at.toString());
		o.addProperty("rsn", rsn);
		addDigest(o, digest);
		return o;
	}

	/** A big drop: {kind: "drop", source, items: [{id, name, qty, priceEach}], at, rsn, digest?} */
	static JsonObject drop(String source, List<LootPicker.Picked> items, String rsn, Instant at, String digest)
	{
		JsonObject o = new JsonObject();
		o.addProperty("kind", "drop");
		o.addProperty("source", source);
		JsonArray list = new JsonArray();
		for (LootPicker.Picked p : items)
		{
			JsonObject i = new JsonObject();
			i.addProperty("id", p.getId());
			i.addProperty("name", p.getName());
			i.addProperty("qty", p.getQty());
			i.addProperty("priceEach", p.getPriceEach());
			list.add(i);
		}
		o.add("items", list);
		o.addProperty("at", at.toString());
		o.addProperty("rsn", rsn);
		addDigest(o, digest);
		return o;
	}

	/** A new collection log slot: {kind: "clog", source?, items: [{name, id?, qty, priceEach?}], at, rsn, digest?} */
	static JsonObject clog(ClogTracker.Clog c, String rsn, Instant at, String digest)
	{
		JsonObject o = new JsonObject();
		o.addProperty("kind", "clog");
		if (c.getSource() != null)
		{
			o.addProperty("source", c.getSource());
		}
		JsonObject i = new JsonObject();
		i.addProperty("name", c.getName());
		if (c.getId() != null)
		{
			i.addProperty("id", c.getId());
		}
		i.addProperty("qty", c.getQty());
		if (c.getGuidePrice() != null)
		{
			i.addProperty("priceEach", c.getGuidePrice());
		}
		JsonArray list = new JsonArray();
		list.add(i);
		o.add("items", list);
		o.addProperty("at", at.toString());
		o.addProperty("rsn", rsn);
		addDigest(o, digest);
		return o;
	}

	/** Old PBs in one batch: {rsn, pbs: [{boss, timeSeconds, teamSize?}], digest?} */
	static JsonObject batch(List<PbRecord> records, String rsn, String digest)
	{
		JsonObject body = new JsonObject();
		body.addProperty("rsn", rsn);
		JsonArray list = new JsonArray();
		for (PbRecord r : records)
		{
			JsonObject o = new JsonObject();
			o.addProperty("boss", r.getBoss());
			o.addProperty("timeSeconds", r.getSeconds());
			if (r.getTeamSize() != null)
			{
				o.addProperty("teamSize", r.getTeamSize());
			}
			list.add(o);
		}
		body.add("pbs", list);
		addDigest(body, digest);
		return body;
	}

	private static void addDigest(JsonObject o, String digest)
	{
		if (digest != null)
		{
			o.addProperty("digest", digest);
		}
	}
}
