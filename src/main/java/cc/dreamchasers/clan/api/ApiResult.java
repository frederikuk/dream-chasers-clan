/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import java.time.Instant;
import lombok.Value;

/**
 * The outcome of a fetch. {@code value} is the freshest copy we have, which on
 * an error is the last good one (or null if there never was one).
 */
@Value
public class ApiResult<T>
{
	public enum Status
	{
		/** Fresh from the network. */
		OK,
		/** Served from the cache without a request, still within max-age. */
		CACHED,
		/** The request failed; {@code value} is the last good copy, if any. */
		ERROR,
		/** No token, or the site rejected it (401). Sign in again. */
		UNAUTHORIZED,
		/** Signed in, but the site says this account isn't a clan member (403). */
		FORBIDDEN,
	}

	Status status;
	T value;
	/** When {@code value} was fetched, or null. */
	Instant fetchedAt;
	/** A short human-readable reason for ERROR, UNAUTHORIZED and FORBIDDEN. */
	String message;
	/** A POST's HTTP status when the site answered; 0 for a GET, or when nothing came back. */
	int code;

	public ApiResult(Status status, T value, Instant fetchedAt, String message)
	{
		this(status, value, fetchedAt, message, 0);
	}

	public ApiResult(Status status, T value, Instant fetchedAt, String message, int code)
	{
		this.status = status;
		this.value = value;
		this.fetchedAt = fetchedAt;
		this.message = message;
		this.code = code;
	}

	public boolean isFresh()
	{
		return status == Status.OK || status == Status.CACHED;
	}
}
