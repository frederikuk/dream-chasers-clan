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
import cc.dreamchasers.clan.api.LinkPollResponse;
import cc.dreamchasers.clan.api.LinkStartResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Connect from the panel: ask the site for a link request, open its
 * approval page in the browser, then poll until the member allows or denies
 * it there. Polls run every few seconds, only while a request is waiting, and
 * stop for good after {@link #MAX_WAIT}.
 */
public class ConnectFlow
{
	static final Duration POLL_EVERY = Duration.ofSeconds(3);
	static final Duration MAX_WAIT = Duration.ofMinutes(10);
	/**
	 * A request still pending this long gets {@link Listener#stillWaiting}: Discord
	 * refuses some accounts on its own page (an unverified or throwaway one) and
	 * never sends the member back, so the site can't say anything (5 Nov 2026).
	 */
	static final Duration STILL_WAITING_AFTER = Duration.ofMinutes(2);

	public interface Listener
	{
		/** Waiting for the browser; {@code code} is what the site's page shows too. */
		void waiting(String code);

		/** Still pending after {@link #STILL_WAITING_AFTER}; called once per request. */
		default void stillWaiting(String code)
		{
		}

		void connected(String token, String name);

		void failed(String message);
	}

	private final DrmApiClient api;
	private final ScheduledExecutorService executor;
	private final Clock clock;
	/** Opens the approval page; false when the link was refused. */
	private final Predicate<String> browser;
	private final Duration pollEvery;
	private final Duration maxWait;
	private final Duration stillWaitingAfter;

	private Attempt current;

	private final class Attempt
	{
		final Listener listener;
		LinkStartResponse start;
		Instant deadline;
		Instant nudgeAt;
		boolean nudged;
		ScheduledFuture<?> next;
		boolean inFlight;
		boolean over;

		Attempt(Listener listener)
		{
			this.listener = listener;
		}
	}

	public ConnectFlow(DrmApiClient api, ScheduledExecutorService executor, Clock clock, Predicate<String> browser)
	{
		this(api, executor, clock, browser, POLL_EVERY, MAX_WAIT, STILL_WAITING_AFTER);
	}

	ConnectFlow(DrmApiClient api, ScheduledExecutorService executor, Clock clock, Predicate<String> browser,
		Duration pollEvery, Duration maxWait)
	{
		this(api, executor, clock, browser, pollEvery, maxWait, STILL_WAITING_AFTER);
	}

	ConnectFlow(DrmApiClient api, ScheduledExecutorService executor, Clock clock, Predicate<String> browser,
		Duration pollEvery, Duration maxWait, Duration stillWaitingAfter)
	{
		this.api = api;
		this.executor = executor;
		this.clock = clock;
		this.browser = browser;
		this.pollEvery = pollEvery;
		this.maxWait = maxWait;
		this.stillWaitingAfter = stillWaitingAfter;
	}

	/** Starts a new request, cancelling any one still waiting. {@code rsn} may be null. */
	public synchronized void start(String rsn, Listener listener)
	{
		cancel();
		Attempt a = new Attempt(listener);
		current = a;
		api.linkStart("RuneLite", rsn, result -> started(a, result));
	}

	public synchronized boolean isWaiting()
	{
		return current != null && !current.over;
	}

	/** Stops polling. The request then dies on the site after 10 minutes. */
	public synchronized void cancel()
	{
		if (current != null)
		{
			current.over = true;
			if (current.next != null)
			{
				current.next.cancel(false);
			}
			current = null;
		}
	}

	private synchronized void started(Attempt a, ApiResult<LinkStartResponse> result)
	{
		if (a.over)
		{
			return;
		}
		LinkStartResponse s = result.getValue();
		if (result.getStatus() != ApiResult.Status.OK || s == null || s.getRequest() == null
			|| s.getPollKey() == null || s.getUrl() == null)
		{
			finish(a, () -> a.listener.failed(result.getMessage() == null ? "The site sent something unexpected" : result.getMessage()));
			return;
		}
		if (!browser.test(s.getUrl()))
		{
			finish(a, () -> a.listener.failed("That link didn't look right. Try Connect again later."));
			return;
		}
		a.start = s;
		a.deadline = clock.instant().plus(maxWait);
		a.nudgeAt = clock.instant().plus(stillWaitingAfter);
		a.listener.waiting(s.getCode());
		schedule(a);
	}

	private void schedule(Attempt a)
	{
		a.next = executor.schedule(() -> poll(a), pollEvery.toMillis(), TimeUnit.MILLISECONDS);
	}

	private synchronized void poll(Attempt a)
	{
		if (a.over || a.inFlight)
		{
			return;
		}
		if (!clock.instant().isBefore(a.deadline))
		{
			finish(a, () -> a.listener.failed("No answer from the browser within 10 minutes. Press Connect to try again."));
			return;
		}
		a.inFlight = true;
		api.linkPoll(a.start, result -> polled(a, result));
	}

	private synchronized void polled(Attempt a, ApiResult<LinkPollResponse> result)
	{
		a.inFlight = false;
		if (a.over)
		{
			return;
		}
		LinkPollResponse p = result.getValue();
		if (result.getStatus() == ApiResult.Status.OK && p != null)
		{
			if ("allowed".equals(p.getStatus()) && p.getToken() != null && !p.getToken().isEmpty())
			{
				finish(a, () -> a.listener.connected(p.getToken(), p.getName()));
				return;
			}
			if ("denied".equals(p.getStatus()))
			{
				finish(a, () -> a.listener.failed("You pressed Deny in the browser, so nothing was linked."));
				return;
			}
			if (!a.nudged && !clock.instant().isBefore(a.nudgeAt))
			{
				a.nudged = true;
				a.listener.stillWaiting(a.start.getCode());
			}
			schedule(a);
			return;
		}
		if (result.getStatus() == ApiResult.Status.ERROR && result.getMessage() != null
			&& (result.getMessage().startsWith("Couldn't reach") || DrmApiClient.BUSY.equals(result.getMessage())))
		{
			schedule(a);               // a network blip, or the client backing off: keep waiting until the deadline
			return;
		}
		finish(a, () -> a.listener.failed(result.getMessage() == null ? "That request has expired. Press Connect again." : result.getMessage()));
	}

	private void finish(Attempt a, Runnable tell)
	{
		a.over = true;
		if (current == a)
		{
			current = null;
		}
		tell.run();
	}
}
