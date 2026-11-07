/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import lombok.Value;

/**
 * Verified join: what the local player's OWN clan record says, and the hashed
 * account id. The plugin reads only {@code ClanSettings.getName()} and
 * {@code findMember(<own name>)}; never the member list, never anyone else.
 * The raw account hash stays on this PC: only {@link #digest(long)} is sent.
 */
public final class OwnClan
{
	/**
	 * The clan's in-game name, compared ignoring case and spacing. Not the
	 * Wise Old Man group's display name "DREAM Chasers": WOM lists the clan
	 * chat as "DRM Chasers".
	 */
	public static final String CLAN_NAME = "DRM Chasers";
	/** Salt and version of the account digest; the site pins the same form. */
	static final String DIGEST_PREFIX = "drm-clan-v1|";
	/** The client's account hash before login. */
	static final long NO_ACCOUNT = -1L;

	private OwnClan()
	{
	}

	public enum State
	{
		/** At the login screen (or hopping): there's no clan record to read. */
		LOGGED_OUT,
		/** Logged in, but no clan record yet: it hasn't loaded, or the player has no clan. */
		UNKNOWN,
		/** The record loaded, and it isn't Dream Chasers (or this player isn't in it). */
		NOT_IN_CLAN,
		/** The player's own record says Dream Chasers. */
		IN_CLAN
	}

	/** One reading of the player's own record. {@code rsn} and {@code digest} are set only when {@code IN_CLAN}. */
	@Value
	public static class Reading
	{
		public static final Reading UNKNOWN = new Reading(State.UNKNOWN, null, null, null);
		public static final Reading LOGGED_OUT = new Reading(State.LOGGED_OUT, null, null, null);

		State state;
		/** The clan's name as the game gave it. */
		String clanName;
		String rsn;
		String digest;
	}

	/**
	 * Judges one reading. {@code settingsLoaded} is whether
	 * {@code getClanSettings(ClanID.CLAN)} was non-null; {@code clanName} its
	 * {@code getName()}; {@code ownMemberFound} whether
	 * {@code findMember(localPlayer.getName())} found the player.
	 */
	public static Reading judge(boolean settingsLoaded, String clanName, String rsn, boolean ownMemberFound, long accountHash)
	{
		if (!settingsLoaded || rsn == null || rsn.isEmpty())
		{
			return Reading.UNKNOWN;
		}
		if (!isDreamChasers(clanName) || !ownMemberFound)
		{
			return new Reading(State.NOT_IN_CLAN, clanName, null, null);
		}
		String digest = digest(accountHash);
		if (digest == null)
		{
			return Reading.UNKNOWN;     // the account hash isn't known yet: try again next tick
		}
		return new Reading(State.IN_CLAN, clanName, rsn, digest);
	}

	public static boolean isDreamChasers(String clanName)
	{
		if (clanName == null)
		{
			return false;
		}
		// the game writes spaces in names as non-breaking spaces
		String n = clanName.replace('\u00A0', ' ').trim().replaceAll("\\s+", " ");
		return n.toLowerCase(Locale.ROOT).equals(CLAN_NAME.toLowerCase(Locale.ROOT));
	}

	/**
	 * Lower-case hex SHA-256 of {@code "drm-clan-v1|" + accountHash}, or null
	 * before login ({@code -1}). One-way: the site can match it, not reverse it.
	 */
	public static String digest(long accountHash)
	{
		if (accountHash == NO_ACCOUNT)
		{
			return null;
		}
		try
		{
			byte[] sum = MessageDigest.getInstance("SHA-256")
				.digest((DIGEST_PREFIX + accountHash).getBytes(StandardCharsets.UTF_8));
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

	/** The panel's line when verifying isn't possible yet; null when it is. */
	public static String blocker(Reading reading)
	{
		switch (reading.getState())
		{
			case IN_CLAN:
				return null;
			case LOGGED_OUT:
				return "Log in to the game to verify";
			case NOT_IN_CLAN:
				return reading.getClanName() != null && !isDreamChasers(reading.getClanName())
					? "Your clan in game isn't DRM Chasers. Join DRM Chasers first"
					: "Join the clan in game first";
			default:
				return "Join DRM Chasers in game first, or open your clan tab and wait a few seconds";
		}
	}
}
