/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/** A fake server: an OkHttp interceptor that answers from a queue and records requests. */
public final class FakeHttp
{
	static final class Reply
	{
		final int code;
		public final String body;
		final String cacheControl;
		final boolean fail;
		final Map<String, String> headers = new LinkedHashMap<>();

		Reply(int code, String body, String cacheControl, boolean fail)
		{
			this.code = code;
			this.body = body;
			this.cacheControl = cacheControl;
			this.fail = fail;
		}
	}

	public static final class Recorded
	{
		public final Request request;
		public final String body;

		Recorded(Request request, String body)
		{
			this.request = request;
			this.body = body;
		}
	}

	final Deque<Reply> replies = new ArrayDeque<>();
	public final List<Recorded> requests = new ArrayList<>();
	/** While set, every request waits here before it is answered. */
	volatile CountDownLatch gate;

	public final OkHttpClient client = new OkHttpClient.Builder()
		.addInterceptor(chain ->
		{
			Request req = chain.request();
			String body = null;
			if (req.body() != null)
			{
				Buffer buf = new Buffer();
				req.body().writeTo(buf);
				body = buf.readUtf8();
			}
			synchronized (this)
			{
				requests.add(new Recorded(req, body));
			}
			CountDownLatch g = gate;
			try
			{
				if (g != null && !g.await(5, TimeUnit.SECONDS))
				{
					throw new IOException("gate never opened");
				}
			}
			catch (InterruptedException e)
			{
				throw new java.io.InterruptedIOException();
			}
			Reply r;
			synchronized (this)
			{
				r = replies.poll();
			}
			if (r == null)
			{
				throw new IOException("no reply queued for " + req.url());
			}
			if (r.fail)
			{
				throw new IOException("simulated network failure");
			}
			Response.Builder b = new Response.Builder()
				.request(req)
				.protocol(Protocol.HTTP_1_1)
				.code(r.code)
				.message("fake")
				.body(ResponseBody.create(MediaType.parse("application/json"), r.body));
			if (r.cacheControl != null)
			{
				b.header("Cache-Control", r.cacheControl);
			}
			r.headers.forEach(b::header);
			return b.build();
		})
		.build();

	public FakeHttp reply(int code, String body, String cacheControl)
	{
		replies.add(new Reply(code, body, cacheControl, false));
		return this;
	}

	/** A reply with one extra header. */
	public FakeHttp reply(int code, String body, String header, String value)
	{
		Reply r = new Reply(code, body, null, false);
		r.headers.put(header, value);
		replies.add(r);
		return this;
	}

	/** Holds every request until {@link #release()}. */
	public FakeHttp hold()
	{
		gate = new CountDownLatch(1);
		return this;
	}

	public void release()
	{
		CountDownLatch g = gate;
		gate = null;
		if (g != null)
		{
			g.countDown();
		}
	}

	public FakeHttp fail()
	{
		replies.add(new Reply(0, "", null, true));
		return this;
	}

	static String fixture(String name)
	{
		try (InputStream in = FakeHttp.class.getResourceAsStream(name))
		{
			if (in == null)
			{
				throw new IllegalStateException("missing fixture " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
	}

	/** A clock the test moves by hand. */
	public static final class MutableClock extends Clock
	{
		Instant now;

		public MutableClock(Instant now)
		{
			this.now = now;
		}

		public void advanceSeconds(long s)
		{
			now = now.plusSeconds(s);
		}

		@Override
		public ZoneId getZone()
		{
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone)
		{
			return this;
		}

		@Override
		public Instant instant()
		{
			return now;
		}
	}
}
