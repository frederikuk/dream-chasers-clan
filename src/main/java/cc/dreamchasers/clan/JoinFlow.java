/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan;

import cc.dreamchasers.clan.api.ApiResult;
import cc.dreamchasers.clan.api.DrmApiClient;
import cc.dreamchasers.clan.api.JoinAttestResponse;
import cc.dreamchasers.clan.api.JoinPeekResponse;
import java.util.function.Supplier;

/**
 * "Verify for Discord" (verified join): the member types the code the site
 * showed them after signing in with Discord; the plugin peeks to learn whose
 * Discord account it belongs to, shows that with the anti-phishing line, and
 * only on Confirm attests. Nothing is sent unless the player's own clan record
 * says Dream Chasers. Two calls per verification, both on OkHttp's threads.
 */
public class JoinFlow
{
	public interface Listener
	{
		/** Peek worked: ask the member to confirm this RSN and Discord name. */
		void confirm(String rsn, String discordName);

		/** Linked: store {@code token} as the sign-in, exactly like Connect. */
		void verified(String token, String name, String message);

		/** Over without a token; {@code isError} false for awaiting-roster and held answers. */
		void finished(String message, boolean isError);
	}

	private final DrmApiClient api;
	private final Supplier<OwnClan.Reading> ownClan;
	private final String pluginVersion;

	private Attempt current;

	private static final class Attempt
	{
		final String code;
		final OwnClan.Reading reading;
		final Listener listener;
		String discordName;
		boolean attesting;

		Attempt(String code, OwnClan.Reading reading, Listener listener)
		{
			this.code = code;
			this.reading = reading;
			this.listener = listener;
		}
	}

	public JoinFlow(DrmApiClient api, Supplier<OwnClan.Reading> ownClan, String pluginVersion)
	{
		this.api = api;
		this.ownClan = ownClan;
		this.pluginVersion = pluginVersion;
	}

	/** Step 1: the member typed a code. Refused here, with no call, unless the own clan record says Dream Chasers. */
	public synchronized void peek(String code, Listener listener)
	{
		current = null;
		OwnClan.Reading reading = ownClan.get();
		String blocker = OwnClan.blocker(reading);
		if (blocker != null)
		{
			listener.finished(blocker + ".", true);
			return;
		}
		Attempt a = new Attempt(code, reading, listener);
		current = a;
		api.joinPeek(code, result -> peeked(a, result));
	}

	private synchronized void peeked(Attempt a, ApiResult<JoinPeekResponse> result)
	{
		if (current != a)
		{
			return;
		}
		JoinPeekResponse p = result.getValue();
		if (result.getStatus() != ApiResult.Status.OK || p == null || p.getDiscordName() == null || p.getDiscordName().trim().isEmpty())
		{
			current = null;
			a.listener.finished(message(result.getMessage(), "That code didn't work. Get a new one at dream-chasers.cc/join."), true);
			return;
		}
		a.discordName = p.getDiscordName().trim();
		a.listener.confirm(a.reading.getRsn(), a.discordName);
	}

	/** Step 2: the member pressed Confirm. The clan record must still be the one shown. */
	public synchronized void confirm()
	{
		Attempt a = current;
		if (a == null || a.discordName == null || a.attesting)
		{
			return;
		}
		OwnClan.Reading now = ownClan.get();
		if (!a.reading.equals(now))
		{
			current = null;
			a.listener.finished("You changed account or clan since. Type the code again.", true);
			return;
		}
		a.attesting = true;
		api.joinAttest(a.code, now.getRsn(), now.getDigest(), now.getClanName(), pluginVersion, result -> attested(a, result));
	}

	/** Cancel: nothing more is sent. The code then dies on the site after 10 minutes. */
	public synchronized void cancel()
	{
		current = null;
	}

	public synchronized boolean isBusy()
	{
		return current != null;
	}

	private synchronized void attested(Attempt a, ApiResult<JoinAttestResponse> result)
	{
		if (current != a)
		{
			return;
		}
		current = null;
		JoinAttestResponse r = result.getValue();
		if (result.getStatus() != ApiResult.Status.OK || r == null)
		{
			a.listener.finished(message(result.getMessage(), "That didn't work. Get a new code at dream-chasers.cc/join."), true);
			return;
		}
		if (JoinAttestResponse.VERIFIED.equals(r.getStatus()) && r.getToken() != null && !r.getToken().isEmpty())
		{
			String rsn = r.getRsn() == null ? a.reading.getRsn() : r.getRsn();
			String moved = r.getRenamedFrom() == null || r.getRenamedFrom().isEmpty() ? ""
				: " Your link moved from " + r.getRenamedFrom() + " to " + rsn + ".";
			a.listener.verified(r.getToken(), r.getName(), "Verified: " + rsn + " is linked to your Discord."
				+ moved + " Your Discord role arrives within about a minute once you're in the Dream Chasers Discord.");
			return;
		}
		if (JoinAttestResponse.AWAITING_ROSTER.equals(r.getStatus()))
		{
			a.listener.finished(message(r.getMessage(), "Your name isn't on the clan roster yet. Watch dream-chasers.cc/join."), false);
			return;
		}
		if (JoinAttestResponse.HELD.equals(r.getStatus()))
		{
			a.listener.finished(message(r.getMessage(), "An officer has to check this one. Open dream-chasers.cc/join for your witness code."), false);
			return;
		}
		a.listener.finished("The site sent something unexpected", true);
	}

	private static String message(String m, String fallback)
	{
		return m == null || m.trim().isEmpty() ? fallback : m;
	}
}
