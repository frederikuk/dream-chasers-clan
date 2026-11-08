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
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Connect from the panel: ask the site for a link request, open its
 * approval page in the browser, then poll until the member allows or denies
 * it there. Polls run every few seconds, only while a request is waiting, and
 * stop for good after {@link #MAX_WAIT}. The loop itself is
 * {@link BrowserApproval}, shared with {@link VerifyFlow}.
 */
public class ConnectFlow extends BrowserApproval<LinkPollResponse, ConnectFlow.Listener>
{
	static final Duration POLL_EVERY = Duration.ofSeconds(3);
	static final Duration MAX_WAIT = Duration.ofMinutes(10);
	/**
	 * A request still pending this long gets {@link Listener#stillWaiting}: Discord
	 * refuses some accounts on its own page (an unverified or throwaway one) and
	 * never sends the member back, so the site can't say anything (5 Nov 2026).
	 */
	static final Duration STILL_WAITING_AFTER = Duration.ofMinutes(2);

	public interface Listener extends BrowserApproval.Waiter
	{
		void connected(String token, String name);
	}

	private final DrmApiClient api;

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
		super(executor, clock, browser, pollEvery, maxWait, stillWaitingAfter);
		this.api = api;
	}

	/** Starts a new request, cancelling any one still waiting. {@code rsn} may be null. */
	public void start(String rsn, Listener listener)
	{
		begin(listener, callback -> api.linkStart("RuneLite", rsn, callback));
	}

	@Override
	void poll(LinkStartResponse start, Consumer<ApiResult<LinkPollResponse>> callback)
	{
		api.linkPoll(start, callback);
	}

	@Override
	Runnable answered(Listener listener, LinkStartResponse start, LinkPollResponse p)
	{
		if ("allowed".equals(p.getStatus()) && p.getToken() != null && !p.getToken().isEmpty())
		{
			return () -> listener.connected(p.getToken(), p.getName());
		}
		if ("denied".equals(p.getStatus()))
		{
			return () -> listener.failed("You pressed Deny in the browser, so nothing was linked.");
		}
		return null;
	}

	@Override
	String refused(ApiResult<LinkPollResponse> result)
	{
		return result.getMessage() == null ? "That request has expired. Press Connect again." : result.getMessage();
	}

	@Override
	String timedOut()
	{
		return "No answer from the browser within 10 minutes. Press Connect to try again.";
	}

	@Override
	String badLink()
	{
		return "That link didn't look right. Try Connect again later.";
	}
}
