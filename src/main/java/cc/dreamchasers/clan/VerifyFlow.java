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
import cc.dreamchasers.clan.api.LinkStartResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * "Sign in with Discord" in the player menu (one-click verified join, formerly "Verify in browser"): the plugin starts a pending
 * verify with the player's own clan facts, opens the site's page, and polls
 * until the member approves or denies it there; the site then hands this
 * plugin its own token. Nothing is sent unless the player's own clan record
 * says Dream Chasers, and a token is kept only if the account and clan are
 * still the ones the verify started with. The typed code ({@link JoinFlow})
 * stays as the fallback.
 */
public class VerifyFlow extends BrowserApproval<JoinAttestResponse, VerifyFlow.Job>
{
	public interface Listener extends BrowserApproval.Waiter, JoinFlow.Outcome
	{
		@Override
		default void failed(String message)
		{
			finished(message, true);
		}
	}

	/** One attempt: who to tell, and the clan record it started with. */
	static final class Job implements BrowserApproval.Waiter
	{
		final Listener listener;
		final OwnClan.Reading reading;

		Job(Listener listener, OwnClan.Reading reading)
		{
			this.listener = listener;
			this.reading = reading;
		}

		@Override
		public void waiting(String code)
		{
			listener.waiting(code);
		}

		@Override
		public void stillWaiting(String code)
		{
			listener.stillWaiting(code);
		}

		@Override
		public void failed(String message)
		{
			listener.finished(message, true);
		}
	}

	static final Duration POLL_EVERY = Duration.ofSeconds(3);
	static final Duration MAX_WAIT = Duration.ofMinutes(10);
	/**
	 * A request still pending this long gets {@link BrowserApproval.Waiter#stillWaiting}: Discord
	 * refuses some accounts on its own page (an unverified or throwaway one) and
	 * never sends the member back, so the site can't say anything (5 Nov 2026).
	 */
	static final Duration STILL_WAITING_AFTER = Duration.ofMinutes(2);

	static final String EXPIRED = "That request has expired. Try again from the player menu at the top right.";
	static final String USED = "That request was already used. If you approved it, try again from the player menu at the top right to sign this PC in.";
	/**
	 * After the verified line: a one-click approval shows the Discord account
	 * only now, so someone who saw the link and approved it with their own
	 * Discord doesn't go unnoticed.
	 */
	static final String NOT_YOU = " Not your Discord account? Press Sign out in the player menu (top right) and tell an officer.";
	static final String CHANGED = "You changed account or clan since you started signing in, so this PC didn't keep the sign-in. Try again from the player menu at the top right.";

	private final DrmApiClient api;
	private final Supplier<OwnClan.Reading> ownClan;
	private final String pluginVersion;

	public VerifyFlow(DrmApiClient api, ScheduledExecutorService executor, Clock clock, Predicate<String> browser,
		Supplier<OwnClan.Reading> ownClan, String pluginVersion)
	{
		this(api, executor, clock, browser, ownClan, pluginVersion,
			POLL_EVERY, MAX_WAIT, STILL_WAITING_AFTER);
	}

	VerifyFlow(DrmApiClient api, ScheduledExecutorService executor, Clock clock, Predicate<String> browser,
		Supplier<OwnClan.Reading> ownClan, String pluginVersion, Duration pollEvery, Duration maxWait, Duration stillWaitingAfter)
	{
		super(executor, clock, browser, pollEvery, maxWait, stillWaitingAfter);
		this.api = api;
		this.ownClan = ownClan;
		this.pluginVersion = pluginVersion;
	}

	/**
	 * Starts a new verify, cancelling any one still waiting. Refused here, with
	 * no call, unless the own clan record says Dream Chasers.
	 */
	public synchronized void start(Listener listener)
	{
		OwnClan.Reading reading = ownClan.get();
		String blocker = OwnClan.blocker(reading);
		if (blocker != null)
		{
			cancel();
			listener.finished(blocker + ".", true);
			return;
		}
		begin(new Job(listener, reading), callback ->
			api.joinStart(reading.getRsn(), reading.getDigest(), reading.getClanName(), pluginVersion, callback));
	}

	@Override
	void poll(LinkStartResponse start, Consumer<ApiResult<JoinAttestResponse>> callback)
	{
		api.joinPoll(start, callback);
	}

	@Override
	Runnable answered(Job job, LinkStartResponse start, JoinAttestResponse r)
	{
		if (JoinAttestResponse.PENDING.equals(r.getStatus()))
		{
			return null;
		}
		if (JoinAttestResponse.DENIED.equals(r.getStatus()))
		{
			return () -> job.failed("You pressed Deny in the browser, so nothing was linked.");
		}
		if (JoinAttestResponse.VERIFIED.equals(r.getStatus()) && !job.reading.equals(ownClan.get()))
		{
			return () ->
			{
				revoke(start);                     // the site drops the token it handed over
				job.failed(CHANGED);               // and it is never stored here
			};
		}
		return () -> JoinFlow.tell(r, job.reading.getRsn(), job.listener, NOT_YOU);
	}

	@Override
	void revoke(LinkStartResponse start)
	{
		api.joinCancel(start, ignored ->
		{
		});
	}

	@Override
	String refused(ApiResult<JoinAttestResponse> result)
	{
		if (result.getCode() == 404)
		{
			return EXPIRED;
		}
		if (result.getCode() == 409)
		{
			return USED;
		}
		if (result.getCode() == 401)
		{
			return EXPIRED;                        // the site no longer knows the poll key
		}
		return JoinFlow.message(result.getMessage(), EXPIRED);
	}

	@Override
	String timedOut()
	{
		return "No answer from the browser within 10 minutes. Try again from the player menu at the top right.";
	}

	@Override
	String badLink()
	{
		return "That link didn't look right. Try again later, from the player menu at the top right.";
	}
}
