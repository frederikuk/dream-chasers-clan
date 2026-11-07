/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan;

import cc.dreamchasers.clan.api.ApiResult;
import cc.dreamchasers.clan.api.WitnessResponse;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;
import net.runelite.client.util.Text;

/**
 * Verify pickup, part of the officer roster sync setting: when a clan chat
 * line or a private message the officer receives holds a witness code from
 * dream-chasers.cc/join ({@code DRM-XXXXXX}), the code and the sender's name
 * (which the game sets, so it proves the account) go to the site, which
 * verifies that member with no typing. Only a line that looks like a code
 * ("DRM-" and six characters of the site's alphabet, or "DRM" and six with a
 * digit among them) leaves the client, and a PM only from a name in the
 * officer's own clan list. Nothing else from chat is sent.
 *
 * <p>While it's on, the plugin also tells the site every 5 minutes that an
 * officer's plugin is online, so the /join page can say whether saying the
 * code now will work. It says it's gone on logout or when the setting goes off.
 */
public final class WitnessPickup
{
	public static final String PATH = "/plugin/witness";
	static final int API_VERSION = 1;
	static final Duration HERE_EVERY = Duration.ofMinutes(5);
	/** Pickups sent per {@link #WINDOW}, so a chat flood can't run the site's limit down. */
	static final int MAX_SENDS = 20;
	/** Different codes one sender may use up per {@link #WINDOW}: one member can't spend everyone's budget. */
	static final int MAX_PER_SENDER = 2;
	static final Duration WINDOW = Duration.ofMinutes(10);
	/** Sender and code pairs already sent this session (a repeated line goes once). */
	static final int MAX_SEEN = 200;
	/**
	 * "DRM", then a dash, a space or nothing, then six characters of the site's
	 * alphabet (2-9 and every letter but O; I, L and 1 are look-alikes the site
	 * maps), in any case, not inside a longer word. {@link #findCode} also
	 * wants the dash or a digit, so "drm events" stays chat.
	 */
	static final Pattern CODE = Pattern.compile("(?i)(?<![a-z0-9])drm(-|\\s?)([2-9a-np-z1]{6})(?![a-z0-9])");
	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

	/** Where a code was said. */
	public enum Via
	{
		CLAN("clan"),
		PM("pm");

		final String wire;

		Via(String wire)
		{
			this.wire = wire;
		}
	}

	/** What the panel shows after an answer, and whether the token has to go. */
	@Value
	public static class Outcome
	{
		/** The status line; null when there's nothing new to show. */
		String line;
		boolean forgetToken;
	}

	private Instant lastHere;
	private boolean hereSent;
	private boolean stopped;
	private final Deque<Instant> sends = new ArrayDeque<>();
	private final Deque<String> senders = new ArrayDeque<>();
	private final Set<String> seen = new LinkedHashSet<>();

	// ---- pure helpers ----

	/** The witness code in a chat line ("DRM-ABC234"), or null when there's none. */
	static String findCode(String message)
	{
		if (message == null)
		{
			return null;
		}
		Matcher m = CODE.matcher(Text.removeTags(message));
		while (m.find())
		{
			String c = m.group(2);
			if (m.group(1).equals("-") || c.matches(".*[2-9].*"))
			{
				return "DRM-" + c.toUpperCase(Locale.ROOT);
			}
		}
		return null;
	}

	/** A chat sender as the game names the account: icons and tags off, Jagex spaces. */
	static String senderName(String name)
	{
		if (name == null)
		{
			return null;
		}
		String n = Text.toJagexName(Text.removeTags(name)).trim();
		return n.isEmpty() ? null : n;
	}

	static JsonObject hereBody(String clanName, String sender, boolean here)
	{
		JsonObject body = new JsonObject();
		body.addProperty("apiVersion", API_VERSION);
		body.addProperty("clanName", clanName);
		body.addProperty("sender", sender);
		body.addProperty("here", here);
		return body;
	}

	static JsonObject witnessBody(String clanName, String sender, String rsn, String code, Via via)
	{
		JsonObject body = new JsonObject();
		body.addProperty("apiVersion", API_VERSION);
		body.addProperty("clanName", clanName);
		body.addProperty("sender", sender);
		JsonObject w = new JsonObject();
		w.addProperty("rsn", rsn);
		w.addProperty("code", code);
		w.addProperty("via", via.wire);
		body.add("witness", w);
		return body;
	}

	// ---- the session's state (any thread) ----

	/** Whether the 5-minute "here" is due. Counts it at once when it is. */
	synchronized boolean hereDue(Instant now)
	{
		if (stopped || (lastHere != null && now.isBefore(lastHere.plus(HERE_EVERY))))
		{
			return false;
		}
		lastHere = now;
		hereSent = true;
		return true;
	}

	/**
	 * Whether a "gone" is owed (a "here" went this session). Clears it, so
	 * the next "here" goes at once.
	 */
	synchronized boolean goneDue()
	{
		boolean due = hereSent;
		hereSent = false;
		lastHere = null;
		return due;
	}

	/**
	 * Whether this sender's code goes to the site: not stopped, not sent
	 * already this session, and under {@link #MAX_SENDS} in the last
	 * {@link #WINDOW}. Counts it at once when it goes.
	 */
	synchronized boolean take(Instant now, String rsn, String code)
	{
		if (stopped)
		{
			return false;
		}
		String who = rsn.toLowerCase(Locale.ROOT);
		String key = who + '\t' + code;
		if (seen.contains(key))
		{
			return false;
		}
		while (!sends.isEmpty() && !now.isBefore(sends.peekFirst().plus(WINDOW)))
		{
			sends.removeFirst();
			senders.removeFirst();
		}
		if (sends.size() >= MAX_SENDS || senders.stream().filter(who::equals).count() >= MAX_PER_SENDER)
		{
			return false;
		}
		sends.addLast(now);
		senders.addLast(who);
		seen.add(key);
		if (seen.size() > MAX_SEEN)
		{
			seen.remove(seen.iterator().next());
		}
		return true;
	}

	/** The send never got an answer (network, local backoff, the site busy): the same line may go again. */
	synchronized void release(String rsn, String code)
	{
		seen.remove(rsn.toLowerCase(Locale.ROOT) + '\t' + code);
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
	 * The site's answer to a "here" or a pickup. 403 stops the pickup for the
	 * session; 401 means the token has to go. A pickup the site verified, or
	 * left for an officer to look at, gets a line; a code that matched nothing
	 * stays quiet.
	 */
	synchronized Outcome answered(ApiResult<WitnessResponse> result, LocalTime at)
	{
		switch (result.getStatus())
		{
			case OK:
				WitnessResponse r = result.getValue();
				WitnessResponse.Witness w = r == null ? null : r.getWitness();
				if (w == null || w.getStatus() == null)
				{
					return new Outcome(null, false);
				}
				switch (w.getStatus())
				{
					case WitnessResponse.VERIFIED:
						return new Outcome("Verified " + w.getRsn() + " " + HH_MM.format(at), false);
					case WitnessResponse.NEEDS_OFFICER:
					case WitnessResponse.REFUSED:
						return new Outcome(w.getRsn() + "'s code: " + reason(w.getMessage()), false);
					default:
						return new Outcome(null, false);
				}
			case UNAUTHORIZED:
				return new Outcome(null, true);
			case FORBIDDEN:
				stopped = true;
				hereSent = false;     // the site refused the here, so no gone is owed
				lastHere = null;
				return new Outcome("Verify pickup stopped: " + reason(result.getMessage()), false);
			default:
				return new Outcome(null, false);
		}
	}

	private static String reason(String message)
	{
		return message == null || message.isEmpty() ? "the site didn't take it" : message;
	}
}
