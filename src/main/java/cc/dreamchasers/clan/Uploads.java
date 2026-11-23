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
 * The JSON bodies of {@code POST /plugin/pbs}, {@code /plugin/pbs/batch},
 * {@code /plugin/loots}, {@code /plugin/kc}, {@code /plugin/xp} and {@code /plugin/logout}. Each carries {@code digest}, the salted
 * SHA-256 of the account hash ({@link OwnClan#digest(long)}), so the site can
 * check the post comes from the account that verified; it is left out only
 * before login (null). The raw account hash is never sent. Each also carries
 * {@code acct}, the player's own account type, when known ({@link #withAcct}).
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

	/** Live competition KC: {rsn, digest?, boss, kc, acct?} */
	static JsonObject kc(String boss, int kc, String rsn, String digest, Integer acct)
	{
		JsonObject o = new JsonObject();
		o.addProperty("rsn", rsn);
		addDigest(o, digest);
		o.addProperty("boss", boss);
		o.addProperty("kc", kc);
		return withAcct(o, acct);
	}

	/** {rsn, digest?, acct?}: a logout or hop, so the site can ask Wise Old Man for the new hiscores. */
	static JsonObject logout(String rsn, String digest, Integer acct)
	{
		return logout(rsn, digest, acct, null, null);
	}

	/**
	 * {rsn, digest?, acct?, skill?, xp?}: a logout or hop, with the player's XP
	 * in the running competition's skill when known (both or neither), which
	 * the site stores like a {@link #xp} send.
	 */
	static JsonObject logout(String rsn, String digest, Integer acct, String skill, Long xp)
	{
		JsonObject o = new JsonObject();
		o.addProperty("rsn", rsn);
		addDigest(o, digest);
		if (skill != null && xp != null && xp >= 0)
		{
			o.addProperty("skill", skill);
			o.addProperty("xp", xp);
		}
		return withAcct(o, acct);
	}

	/** Live competition XP: {rsn, digest?, skill, xp, acct?} */
	static JsonObject xp(String skill, long xp, String rsn, String digest, Integer acct)
	{
		JsonObject o = new JsonObject();
		o.addProperty("rsn", rsn);
		addDigest(o, digest);
		o.addProperty("skill", skill);
		o.addProperty("xp", xp);
		return withAcct(o, acct);
	}

	/**
	 * A listed first that isn't a collection log slot (Blorva, Radiant
	 * oathplate), seen arriving in the player's own inventory:
	 * {unlock, at, rsn, digest?}, to {@code POST /plugin/unlock}. The site
	 * takes only the unlock keys it knows.
	 */
	static JsonObject unlock(String key, String rsn, Instant at, String digest)
	{
		JsonObject o = new JsonObject();
		o.addProperty("unlock", key);
		o.addProperty("at", at.toString());
		o.addProperty("rsn", rsn);
		addDigest(o, digest);
		return o;
	}

	/**
	 * Adds {@code acct}, the game's account-type value for the player's own
	 * account (0 normal, 1 ironman, 2 ultimate, 3 hardcore, 4 group, 5 hardcore
	 * group, 6 unranked group), to any body; left out when unknown or out of range.
	 */
	static JsonObject withAcct(JsonObject o, Integer acct)
	{
		if (acct != null && acct >= 0 && acct <= 6)
		{
			o.addProperty("acct", acct);
		}
		return o;
	}

	private static void addDigest(JsonObject o, String digest)
	{
		if (digest != null)
		{
			o.addProperty("digest", digest);
		}
	}
}
