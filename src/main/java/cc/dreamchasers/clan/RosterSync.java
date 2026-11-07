/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan;

import cc.dreamchasers.clan.api.ApiResult;
import cc.dreamchasers.clan.api.RosterResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.Value;
import net.runelite.api.clan.ClanMember;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;
import net.runelite.client.util.Text;

/**
 * Officer roster sync: when an officer closes the clan Members screen, the
 * clan's member list (names and rank titles only) goes to dream-chasers.cc,
 * which updates the Wise Old Man group. This mirrors the Wise Old Man plugin's
 * manual group sync, which reads the same {@code getMembers()} list.
 *
 * <p>Off by default, officers and up only, never locations, gear or stats. At most one
 * attempt a minute, and nothing when the list is the same as the last one the
 * site took. A 403 (the site doesn't see an officer) stops it for the session.
 */
public final class RosterSync
{
	public static final String PATH = "/plugin/roster";
	static final int API_VERSION = 1;
	/**
	 * The site allows one sync a minute, counted from when a send reaches it;
	 * the plugin counts from when it sends, so it waits a little longer.
	 */
	static final Duration MIN_INTERVAL = Duration.ofSeconds(65);
	static final int MAX_MEMBERS = 600;
	/** A rank with no title (the plain clan member). */
	static final String NO_TITLE = "member";
	/** The site's longest rank key (gate.js RANK_RE). */
	static final int MAX_RANK_KEY = 24;
	/**
	 * Officer-or-higher rank titles (the hats, the capes, Administrator, Deputy
	 * Owner, Owner), as the site has them (gate.js rankLevel 1 and up): only they
	 * may sync, and the site only takes ranks below the sender's own tier (so an
	 * officer manages the members). The site checks again; this only keeps
	 * everyone else from sending at all.
	 */
	static final Set<String> SYNC_RANKS = Set.of("summoner", "illusionist", "wizard", "warlock", "necromancer",
		"legend", "myth", "tztok", "tzkal", "administrator", "deputy_owner", "owner");
	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

	/** One member: the name as the game spells it, and the rank title key. */
	@Value
	public static class Member
	{
		String rsn;
		String rank;
	}

	public enum Decision
	{
		/** Send now (the attempt is already counted). */
		SEND,
		/** The same list the site last took: nothing to send. */
		UNCHANGED,
		/** Less than a minute since the last attempt. */
		TOO_SOON,
		/** The site said this account isn't an officer: off until the next login or a new tick of the setting. */
		STOPPED
	}

	/** What the panel shows after an answer, and whether the token has to go. */
	@Value
	public static class Outcome
	{
		/** The status line; null when there's nothing to show (signed out). */
		String line;
		boolean forgetToken;
		/** The send failed and the same list goes once more when the minute is up. */
		boolean retry;
	}

	private Instant lastAttempt;
	private String acceptedHash;
	/** The list of the last send, kept after its answer so a wait can say whether the list changed since. */
	private String lastSentHash;
	/** The list of the send still waiting for the site's answer; null when none is. */
	private String inFlightHash;
	private boolean stopped;
	/** A failed send already had its one retry; cleared when the site takes a list. */
	private boolean retried;

	// ---- pure helpers ----

	/**
	 * A rank title as the site keys it: lower case, spaces and hyphens as
	 * underscores (the Wise Old Man plugin's form), anything else outside
	 * a-z, 0-9 and _ dropped, at most {@link #MAX_RANK_KEY} characters, and
	 * "member" when untitled or nothing is left. The site turns away the whole
	 * list if one rank doesn't match {@code ^[a-z0-9_]{1,24}$} (gate.js RANK_RE).
	 */
	static String rankKey(String title)
	{
		if (title == null || title.trim().isEmpty())
		{
			return NO_TITLE;
		}
		String key = title.replace(' ', ' ').trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_')
			.replaceAll("[^a-z0-9_]", "");
		if (key.length() > MAX_RANK_KEY)
		{
			key = key.substring(0, MAX_RANK_KEY);
		}
		return key.isEmpty() ? NO_TITLE : key;
	}

	static boolean isOfficer(String rankKey)
	{
		return rankKey != null && SYNC_RANKS.contains(rankKey);
	}

	/**
	 * Whether a close of the Members screen may send: Connect and the setting
	 * on, signed in to the site, the player's own clan is Dream Chasers, and
	 * their own rank is officer or above.
	 */
	static boolean allowed(boolean connect, boolean rosterSync, boolean signedIn, String clanName, String ownRankKey)
	{
		return connect && rosterSync && signedIn && OwnClan.isDreamChasers(clanName) && isOfficer(ownRankKey);
	}

	/** The rank key of one member's title. */
	static String rankOf(ClanSettings settings, ClanMember member)
	{
		ClanTitle title = member.getRank() == null ? null : settings.titleForRank(member.getRank());
		return rankKey(title == null ? null : title.getName());
	}

	/** The local player's own rank key, or null when they aren't in the list. Client thread. */
	static String ownRank(ClanSettings settings, String ownName)
	{
		ClanMember me = ownName == null ? null : settings.findMember(ownName);
		return me == null ? null : rankOf(settings, me);
	}

	/**
	 * The full in-game member list: Jagex-form names and rank keys. Names
	 * starting "[#" (the game's placeholder for a name it hasn't loaded) are
	 * skipped. Client thread.
	 */
	static List<Member> read(ClanSettings settings)
	{
		List<Member> out = new ArrayList<>();
		List<ClanMember> members = settings.getMembers();
		if (members == null)
		{
			return out;
		}
		for (ClanMember m : members)
		{
			String name = m == null || m.getName() == null ? null : Text.toJagexName(m.getName());
			if (name == null || name.isEmpty() || name.startsWith("[#"))
			{
				continue;
			}
			out.add(new Member(name, rankOf(settings, m)));
		}
		return out;
	}

	/** SHA-256 of the list in name order, so the same roster read in another order matches. */
	static String hash(List<Member> members)
	{
		List<Member> sorted = new ArrayList<>(members);
		sorted.sort(Comparator.comparing((Member m) -> m.getRsn().toLowerCase(Locale.ROOT)).thenComparing(Member::getRank));
		StringBuilder sb = new StringBuilder();
		for (Member m : sorted)
		{
			sb.append(m.getRsn()).append('\t').append(m.getRank()).append('\n');
		}
		try
		{
			byte[] sum = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(64);
			for (byte b : sum)
			{
				hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
		}
	}

	/** The request body (the wire contract with the site). */
	static JsonObject body(String clanName, String sender, List<Member> members)
	{
		JsonObject body = new JsonObject();
		body.addProperty("apiVersion", API_VERSION);
		body.addProperty("clanName", clanName);
		body.addProperty("sender", sender);
		JsonArray list = new JsonArray();
		for (Member m : members)
		{
			JsonObject o = new JsonObject();
			o.addProperty("rsn", m.getRsn());
			o.addProperty("rank", m.getRank());
			list.add(o);
		}
		body.add("members", list);
		return body;
	}

	/** "Roster sent 12:34: 2 added, 1 rank changed". */
	static String sentLine(LocalTime at, int added, int changed)
	{
		List<String> parts = new ArrayList<>();
		if (added > 0)
		{
			parts.add(added + " added");
		}
		if (changed > 0)
		{
			parts.add(changed + (changed == 1 ? " rank changed" : " ranks changed"));
		}
		return "Roster sent " + HH_MM.format(at) + ": " + (parts.isEmpty() ? "no changes" : String.join(", ", parts));
	}

	/** The line before anything is sent this session. */
	static String idleLine()
	{
		return "Roster sync is on: close the clan Members screen to send it";
	}

	/**
	 * A close inside the minute after the last attempt: the plugin sends by
	 * itself at {@code at}. {@code changed} says the list differs from the
	 * last one sent; otherwise the last send didn't go through.
	 */
	static String waitingLine(LocalTime at, boolean changed)
	{
		return (changed ? "Changed again: sending at " : "Not sent yet: trying again at ") + HH_MM.format(at);
	}

	/** The member list isn't one the plugin sends. */
	static String notReadLine(int size)
	{
		return size > MAX_MEMBERS
			? "Roster not sent: the member list is over " + MAX_MEMBERS + " names"
			: "Roster not sent: the member list isn't loaded yet";
	}

	// ---- the session's state (any thread) ----

	/**
	 * Decides whether this close sends. On {@link Decision#SEND} the attempt
	 * is counted at once, so a second close inside the minute waits.
	 */
	synchronized Decision check(Instant now, String hash)
	{
		if (stopped)
		{
			return Decision.STOPPED;
		}
		if (hash.equals(acceptedHash) || hash.equals(inFlightHash))
		{
			// taken already, or on its way: its answer sets the line
			return Decision.UNCHANGED;
		}
		if (lastAttempt != null && now.isBefore(lastAttempt.plus(MIN_INTERVAL)))
		{
			return Decision.TOO_SOON;
		}
		lastAttempt = now;
		lastSentHash = hash;
		inFlightHash = hash;
		return Decision.SEND;
	}

	/** When the next attempt may go; null before the first. */
	synchronized Instant nextAllowed()
	{
		return lastAttempt == null ? null : lastAttempt.plus(MIN_INTERVAL);
	}

	/** Whether {@code hash} differs from the list of the last send (true before any send). */
	synchronized boolean changedSinceLastSend(String hash)
	{
		return !hash.equals(lastSentHash);
	}

	synchronized boolean isStopped()
	{
		return stopped;
	}

	/** A new login or a fresh tick of the setting: a 403 from before no longer holds. */
	synchronized void resume()
	{
		stopped = false;
	}

	/**
	 * The site's answer to one send of the list with {@code hash}, at the
	 * local time {@code at}. 200 marks the list as taken; 403 stops the sync
	 * for the session; 401 means the token has to go (as on every route).
	 */
	synchronized Outcome answered(ApiResult<RosterResponse> result, String hash, LocalTime at)
	{
		RosterResponse r = result.getValue();
		if (hash.equals(inFlightHash))
		{
			inFlightHash = null;
		}
		switch (result.getStatus())
		{
			case OK:
				if (r == null || !r.isOk())
				{
					return new Outcome("Roster not sent: the site sent something unexpected", false, false);
				}
				acceptedHash = hash;
				retried = false;
				return new Outcome(sentLine(at, r.getAdded(), r.getChanged()), false, false);
			case UNAUTHORIZED:
				return new Outcome(null, true, false);
			case FORBIDDEN:
				stopped = true;
				return new Outcome("Roster sync stopped: " + reason(result.getMessage()), false, false);
			default:
				// busy (429), a 5xx or no answer at all: one more try once the minute is up, then the next close
				int code = result.getCode();
				boolean again = !retried && (code == 0 || code == 429 || code >= 500);
				retried |= again;
				String line = "Roster not sent: " + reason(result.getMessage());
				return new Outcome(again ? line.replaceAll("[.]$", "") + ". Trying again at " + HH_MM.format(at.plus(MIN_INTERVAL)) : line,
					false, again);
		}
	}

	private static String reason(String message)
	{
		return message == null || message.isEmpty() ? "the site didn't take it" : message;
	}

	/** For tests: the list the site last took. */
	synchronized String acceptedHash()
	{
		return acceptedHash;
	}
}
