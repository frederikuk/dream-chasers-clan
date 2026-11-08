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
import cc.dreamchasers.clan.api.LinkStartResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The loop behind every "approve in the browser" step (Connect, and Verify in
 * browser): ask the site for a request, open its page, then poll until the
 * member answers there. Polls run every few seconds, only while a request is
 * waiting, ride out network blips, the site's 429 and 5xx answers and the
 * client's back-off, nudge once after {@code stillWaitingAfter}, and stop for
 * good after {@code maxWait}, once a last poll past it still finds nothing
 * (so an answer given just before the end isn't missed). Giving up (Cancel,
 * the deadline) tells the site through {@link #revoke}. All timing is on the
 * injected executor and clock; nothing sleeps.
 *
 * @param <P> what one poll answers
 * @param <L> who hears about one attempt
 */
abstract class BrowserApproval<P, L extends BrowserApproval.Waiter>
{
	/** What every browser-approve attempt tells its panel. */
	public interface Waiter
	{
		/** Waiting for the browser; {@code code} is what the site's page shows too. */
		void waiting(String code);

		/** Still pending after the nudge time; called once per request. */
		default void stillWaiting(String code)
		{
		}

		/** Over without a token. */
		void failed(String message);
	}

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
		final L listener;
		LinkStartResponse start;
		Instant deadline;
		Instant nudgeAt;
		boolean nudged;
		ScheduledFuture<?> next;
		boolean inFlight;
		boolean over;
		/** The deadline has passed: this poll is the last. */
		boolean last;

		Attempt(L listener)
		{
			this.listener = listener;
		}
	}

	BrowserApproval(ScheduledExecutorService executor, Clock clock, Predicate<String> browser,
		Duration pollEvery, Duration maxWait, Duration stillWaitingAfter)
	{
		this.executor = executor;
		this.clock = clock;
		this.browser = browser;
		this.pollEvery = pollEvery;
		this.maxWait = maxWait;
		this.stillWaitingAfter = stillWaitingAfter;
	}

	/** One poll of a request the site started. */
	abstract void poll(LinkStartResponse start, Consumer<ApiResult<P>> callback);

	/**
	 * A poll the site answered (200 or 202): what ends the attempt, or null to
	 * keep waiting. Runs under this object's lock; the returned step is run once
	 * the attempt is marked over.
	 */
	abstract Runnable answered(L listener, LinkStartResponse start, P answer);

	/**
	 * Tells the site this request is given up, so nobody can approve it from
	 * here on. Called without waiting for an answer; nothing by default.
	 */
	void revoke(LinkStartResponse start)
	{
	}

	/** The line when the site refused a poll (an expired or used request, say). */
	abstract String refused(ApiResult<P> result);

	/** The line when nobody answered within {@code maxWait}. */
	abstract String timedOut();

	/** The line when the site's link failed {@link cc.dreamchasers.clan.ui.SafeLinks}. */
	abstract String badLink();

	/** Starts a new request through {@code start}, cancelling any one still waiting. */
	synchronized void begin(L listener, Consumer<Consumer<ApiResult<LinkStartResponse>>> start)
	{
		cancel();
		Attempt a = new Attempt(listener);
		current = a;
		start.accept(result -> started(a, result));
	}

	public synchronized boolean isWaiting()
	{
		return current != null && !current.over;
	}

	/** Stops polling, and tells the site (see {@link #revoke}) so the request can't be approved any more. */
	public synchronized void cancel()
	{
		if (current != null)
		{
			current.over = true;
			if (current.next != null)
			{
				current.next.cancel(false);
			}
			if (current.start != null)
			{
				revoke(current.start);
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
			finish(a, () -> a.listener.failed(badLink()));
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
		a.next = executor.schedule(() -> pollOnce(a), pollEvery.toMillis(), TimeUnit.MILLISECONDS);
	}

	private synchronized void pollOnce(Attempt a)
	{
		if (a.over || a.inFlight)
		{
			return;
		}
		if (!clock.instant().isBefore(a.deadline))
		{
			a.last = true;             // one more look: an Approve just before the end still counts
		}
		a.inFlight = true;
		poll(a.start, result -> polled(a, result));
	}

	private synchronized void polled(Attempt a, ApiResult<P> result)
	{
		a.inFlight = false;
		if (a.over)
		{
			return;
		}
		P p = result.getValue();
		if (result.getStatus() == ApiResult.Status.OK && p != null)
		{
			Runnable end = answered(a.listener, a.start, p);
			if (end != null)
			{
				finish(a, end);
				return;
			}
			if (!a.nudged && !clock.instant().isBefore(a.nudgeAt))
			{
				a.nudged = true;
				a.listener.stillWaiting(a.start.getCode());
			}
			again(a);
			return;
		}
		if (isBlip(result))
		{
			again(a);                  // a blip, or the site or client asking for a pause: keep waiting until the deadline
			return;
		}
		String line = refused(result);
		finish(a, () -> a.listener.failed(line));
	}

	/**
	 * A poll that says nothing about the request: the network failed, the
	 * client is backing off, or the site answered 429 or 5xx (a Gate restart,
	 * a failed mint that the next poll retries).
	 */
	static boolean isBlip(ApiResult<?> result)
	{
		if (result.getStatus() == ApiResult.Status.OK)
		{
			return false;
		}
		int code = result.getCode();
		if (code == 429 || code >= 500)
		{
			return true;
		}
		String m = result.getMessage();
		return code == 0 && m != null && (m.startsWith("Couldn't reach") || DrmApiClient.BUSY.equals(m));
	}

	/** The next poll, or the end once the last one past the deadline found nothing. */
	private void again(Attempt a)
	{
		if (a.last)
		{
			revoke(a.start);
			finish(a, () -> a.listener.failed(timedOut()));
			return;
		}
		schedule(a);
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
