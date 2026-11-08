/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Client for the Dream Chasers site API (v1). All network calls are enqueued
 * on OkHttp's thread pool; callbacks run on that pool, or on the caller's
 * thread when the answer comes straight from the cache.
 *
 * <p>Each GET endpoint's last good response is kept for the server's
 * Cache-Control max-age (with the endpoint's floor). A failed refresh hands
 * back the last good copy with status ERROR, as the API docs ask.
 */
@Slf4j
public class DrmApiClient
{
	public static final String DEFAULT_BASE_URL = "https://dream-chasers.cc/api/v1";
	/** A forced refresh (the panel's button) still waits this long between requests. */
	static final Duration MIN_FORCE_INTERVAL = Duration.ofSeconds(15);
	/**
	 * After a failure a path is left alone this long, doubling per failure up
	 * to {@link #BACKOFF_MAX} (or the site's Retry-After on 429/503, up to an
	 * hour). The back-off only suppresses calls; it never schedules one.
	 */
	static final Duration BACKOFF_START = Duration.ofSeconds(60);
	static final Duration BACKOFF_MAX = Duration.ofMinutes(15);
	private static final long RETRY_AFTER_MAX_SECONDS = 3600;
	/** What a call held back by the back-off (or by one already in flight) gets. */
	public static final String BUSY = "dream-chasers.cc is busy; try again shortly";

	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final MediaType TEXT = MediaType.parse("text/plain; charset=utf-8");
	/** The panel splash is about 150 KB. */
	static final int MAX_IMAGE_BYTES = 1024 * 1024;
	/** The biggest JSON answer read; Hall of Fame is ~38 KB today. */
	static final int MAX_JSON_BYTES = 512 * 1024;

	private final OkHttpClient http;
	private final Gson gson;
	private final String baseUrl;
	private final String userAgent;
	private final Supplier<String> tokenSupplier;
	private final Clock clock;

	private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
	private final Map<String, Instant> lastAttempt = new ConcurrentHashMap<>();
	// back-off per path, and one GET in flight per path
	private final Map<String, Instant> notBefore = new ConcurrentHashMap<>();
	private final Map<String, Integer> failures = new ConcurrentHashMap<>();
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
	// the site's last 403 text per path, handed back while that 403 is backed off
	private final Map<String, String> refusals = new ConcurrentHashMap<>();

	private static final class CacheEntry
	{
		final Object value;
		final Instant fetchedAt;
		final Instant expiresAt;

		CacheEntry(Object value, Instant fetchedAt, Instant expiresAt)
		{
			this.value = value;
			this.fetchedAt = fetchedAt;
			this.expiresAt = expiresAt;
		}
	}

	public DrmApiClient(OkHttpClient http, Gson gson, String baseUrl, String userAgent,
		Supplier<String> tokenSupplier, Clock clock)
	{
		// never follow a 3xx: a redirect would take the client (and its IP, and a 307/308's POST body) off the API host the IP warning names
		this.http = http.newBuilder().followRedirects(false).followSslRedirects(false).build();
		this.gson = gson;
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.userAgent = userAgent;
		this.tokenSupplier = tokenSupplier;
		this.clock = clock;
	}

	/** The client actually used, for tests. */
	OkHttpClient httpClient()
	{
		return http;
	}

	/**
	 * Fetches an endpoint unless the cached copy is still fresh. With
	 * {@code force}, a fresh copy is refetched too, but never more often than
	 * {@link #MIN_FORCE_INTERVAL}. A stale or missing entry is fetched only
	 * outside the path's back-off and when no fetch of it is in flight;
	 * otherwise the last good copy comes back as ERROR with {@link #BUSY}.
	 */
	public <T> void fetch(Endpoint<T> endpoint, boolean force, Consumer<ApiResult<T>> callback)
	{
		Instant now = clock.instant();
		CacheEntry entry = cache.get(endpoint.getPath());
		if (entry != null)
		{
			boolean fresh = now.isBefore(entry.expiresAt);
			Instant last = lastAttempt.get(endpoint.getPath());
			boolean tooSoon = last != null && now.isBefore(last.plus(MIN_FORCE_INTERVAL));
			if (fresh && (!force || tooSoon))
			{
				callback.accept(new ApiResult<>(ApiResult.Status.CACHED,
					endpoint.getType().cast(entry.value), entry.fetchedAt, null));
				return;
			}
		}

		Request.Builder request = newRequest(endpoint.getPath()).get();
		if (endpoint.isAuthenticated())
		{
			String token = token();
			if (token == null)
			{
				callback.accept(new ApiResult<>(ApiResult.Status.UNAUTHORIZED, null, null, "Not signed in"));
				return;
			}
			request.header("Authorization", "Bearer " + token);
		}

		String path = endpoint.getPath();
		if (backingOff(path, now) || !inFlight.add(path))
		{
			// a forced refresh of a fresh copy just gets that copy; a held-back 403 stays a 403
			String refused = refusals.get(path);
			callback.accept(entry != null && now.isBefore(entry.expiresAt)
				? new ApiResult<>(ApiResult.Status.CACHED, endpoint.getType().cast(entry.value), entry.fetchedAt, null)
				: refused != null ? new ApiResult<>(ApiResult.Status.FORBIDDEN, null, null, refused)
				: lastGood(endpoint, BUSY));
			return;
		}
		lastAttempt.put(path, now);
		http.newCall(request.build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("DRM API {} failed", path, e);
				inFlight.remove(path);
				noteFailure(path, null);
				callback.accept(lastGood(endpoint, "Couldn't reach dream-chasers.cc"));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				ApiResult<T> result;
				try (ResponseBody body = response.body())
				{
					result = handle(endpoint, response, readText(body));
				}
				catch (IOException e)
				{
					log.debug("DRM API {} read failed", path, e);
					noteFailure(path, null);
					result = lastGood(endpoint, "Couldn't reach dream-chasers.cc");
				}
				finally
				{
					inFlight.remove(path);
				}
				callback.accept(result);
			}
		});
	}

	/** {@code text} is null when the body was over {@link #MAX_JSON_BYTES}. */
	private <T> ApiResult<T> handle(Endpoint<T> endpoint, Response response, String text)
	{
		int code = response.code();
		if (code == 200)
		{
			T value;
			try
			{
				value = text == null ? null : gson.fromJson(text, endpoint.getType());
			}
			catch (JsonParseException e)
			{
				log.debug("DRM API {} sent unreadable JSON", endpoint.getPath(), e);
				value = null;
			}
			if (value == null)
			{
				noteFailure(endpoint.getPath(), null);
				return lastGood(endpoint, "The site sent something unexpected");
			}
			noteSuccess(endpoint.getPath());
			Instant now = clock.instant();
			int maxAge = Math.max(endpoint.getMinMaxAgeSeconds(),
				CacheControl.maxAge(response.header("Cache-Control"), 0));
			cache.put(endpoint.getPath(), new CacheEntry(value, now, now.plusSeconds(maxAge)));
			return new ApiResult<>(ApiResult.Status.OK, value, now, null);
		}
		if (endpoint.isAuthenticated() && (code == 401 || code == 403))
		{
			// A 401 needs no back-off: the caller forgets the token, which stops the calls.
			// A 403 keeps the token (and a WAF or challenge answers 403), so it backs off.
			// Never keep showing members-only data once the site says no.
			cache.remove(endpoint.getPath());
			ApiResult.Status status = code == 401 ? ApiResult.Status.UNAUTHORIZED : ApiResult.Status.FORBIDDEN;
			String fallback = code == 401 ? "Sign in again" : "The site doesn't see you as a clan member";
			String message = errorText(text == null ? "" : text, fallback);
			if (code == 403)
			{
				noteFailure(endpoint.getPath(), response);
				refusals.put(endpoint.getPath(), message);
			}
			return new ApiResult<>(status, null, null, message);
		}
		noteFailure(endpoint.getPath(), response);
		return lastGood(endpoint, "dream-chasers.cc answered " + code);
	}

	private boolean backingOff(String path, Instant now)
	{
		Instant until = notBefore.get(path);
		return until != null && now.isBefore(until);
	}

	private void noteSuccess(String path)
	{
		failures.remove(path);
		notBefore.remove(path);
		refusals.remove(path);
	}

	/** Backs a path off after a failure; {@code response} is null when nothing came back. */
	private void noteFailure(String path, Response response)
	{
		refusals.remove(path);
		int n = failures.merge(path, 1, Integer::sum);
		Duration wait = BACKOFF_START.multipliedBy(1L << Math.min(n - 1, 10));
		if (wait.compareTo(BACKOFF_MAX) > 0)
		{
			wait = BACKOFF_MAX;
		}
		if (response != null && (response.code() == 429 || response.code() == 503))
		{
			long retryAfter = retryAfterSeconds(response.header("Retry-After"));
			if (retryAfter > wait.getSeconds())
			{
				wait = Duration.ofSeconds(Math.min(retryAfter, RETRY_AFTER_MAX_SECONDS));
			}
		}
		notBefore.put(path, clock.instant().plus(wait));
	}

	/** Retry-After in whole seconds, or 0 when absent or in the date form. */
	static long retryAfterSeconds(String header)
	{
		if (header == null)
		{
			return 0;
		}
		try
		{
			return Math.max(0, Long.parseLong(header.trim()));
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	private <T> ApiResult<T> lastGood(Endpoint<T> endpoint, String message)
	{
		CacheEntry entry = cache.get(endpoint.getPath());
		return entry == null
			? new ApiResult<>(ApiResult.Status.ERROR, null, null, message)
			: new ApiResult<>(ApiResult.Status.ERROR, endpoint.getType().cast(entry.value), entry.fetchedAt, message);
	}

	/**
	 * Swaps a link code from dream-chasers.cc/plugin for a long-lived token.
	 * The caller stores {@link LinkResponse#getToken()}.
	 */
	public void link(String code, String device, Consumer<ApiResult<LinkResponse>> callback)
	{
		String normalized = normalizeCode(code);
		if (normalized.isEmpty())
		{
			callback.accept(new ApiResult<>(ApiResult.Status.ERROR, null, null, "Paste the code from the site first"));
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("code", normalized);
		payload.addProperty("device", device);
		Request request = newRequest("/plugin/link")
			.post(RequestBody.create(JSON, gson.toJson(payload)))
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("DRM API link failed", e);
				callback.accept(new ApiResult<>(ApiResult.Status.ERROR, null, null, "Couldn't reach dream-chasers.cc"));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					String capped = readText(body);
					String text = capped == null ? "" : capped;
					if (response.code() == 200)
					{
						LinkResponse value = null;
						try
						{
							value = capped == null ? null : gson.fromJson(text, LinkResponse.class);
						}
						catch (JsonParseException e)
						{
							log.debug("DRM API link sent unreadable JSON", e);
						}
						if (value == null || value.getToken() == null || value.getToken().isEmpty())
						{
							callback.accept(new ApiResult<>(ApiResult.Status.ERROR, null, null, "The site sent something unexpected"));
							return;
						}
						callback.accept(new ApiResult<>(ApiResult.Status.OK, value, clock.instant(), null));
						return;
					}
					String fallback = response.code() == 404
						? "Sign-in isn't switched on at the site yet"
						: "That code didn't work. Codes last 10 minutes and work once";
					callback.accept(new ApiResult<>(ApiResult.Status.ERROR, null, null,
						response.code() == 404 ? fallback : errorText(text, fallback)));
				}
				catch (IOException e)
				{
					callback.accept(new ApiResult<>(ApiResult.Status.ERROR, null, null, "Couldn't reach dream-chasers.cc"));
				}
			}
		});
	}

	/**
	 * Revokes the current token at the site (best effort) and forgets every
	 * members-only response. The caller clears its stored token.
	 */
	public void unlink()
	{
		String token = token();
		clearMembersOnly();
		if (token == null)
		{
			return;
		}
		Request request = newRequest("/plugin/unlink")
			.header("Authorization", "Bearer " + token)
			.post(RequestBody.create(JSON, "{}"))
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("DRM API unlink failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				response.close();
			}
		});
	}

	/** Asks the site for a browser-approval link request. {@code rsn} may be null. */
	public void linkStart(String device, String rsn, Consumer<ApiResult<LinkStartResponse>> callback)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("device", device);
		if (rsn != null)
		{
			payload.addProperty("rsn", rsn);
		}
		send(newRequest("/plugin/link/start").post(RequestBody.create(JSON, gson.toJson(payload))),
			LinkStartResponse.class, callback);
	}

	/** One poll of a link request: pending, denied, or allowed with a token. */
	public void linkPoll(LinkStartResponse start, Consumer<ApiResult<LinkPollResponse>> callback)
	{
		send(newRequest("/plugin/link/poll?req=" + start.getRequest())
			.header("Authorization", "Poll " + start.getPollKey()).get(), LinkPollResponse.class, callback);
	}

	/**
	 * Verified join: whose Discord account a join code from dream-chasers.cc/join
	 * belongs to. Doesn't use the code up. No token is sent; the code is the
	 * credential. The route sits at {@code /api/join/peek} on the API's host.
	 */
	public void joinPeek(String code, Consumer<ApiResult<JoinPeekResponse>> callback)
	{
		String normalized = normalizeJoinCode(code);
		if (normalized.isEmpty())
		{
			callback.accept(new ApiResult<>(ApiResult.Status.ERROR, null, null, "Type the code from dream-chasers.cc/join first"));
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("code", normalized);
		Request.Builder request = joinRequest("/api/join/peek", payload);
		if (request == null)
		{
			callback.accept(new ApiResult<>(ApiResult.Status.ERROR, null, null, "The site address isn't right"));
			return;
		}
		send(request, JoinPeekResponse.class, callback);
	}

	/**
	 * Verified join: links this account to the Discord account behind the
	 * code. Sends exactly the code, the RSN, the account digest (never the raw
	 * account hash), the clan name as the game gave it, the plugin version and
	 * the device label: no rank, no join date, nothing about other players.
	 * 200 {@code verified} carries a token; 202 {@code awaiting_roster} or
	 * {@code held} a message.
	 */
	public void joinAttest(String code, String rsn, String digest, String clanName, String pluginVersion,
		Consumer<ApiResult<JoinAttestResponse>> callback)
	{
		String normalized = normalizeJoinCode(code);
		if (normalized.isEmpty() || rsn == null || digest == null || clanName == null)
		{
			callback.accept(new ApiResult<>(ApiResult.Status.ERROR, null, null, "Type the code from dream-chasers.cc/join first"));
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("code", normalized);
		payload.addProperty("rsn", rsn);
		payload.addProperty("digest", digest);
		payload.addProperty("clanName", clanName);
		payload.addProperty("pluginVersion", pluginVersion);
		payload.addProperty("device", "RuneLite");
		Request.Builder request = joinRequest("/api/join/attest", payload);
		if (request == null)
		{
			callback.accept(new ApiResult<>(ApiResult.Status.ERROR, null, null, "The site address isn't right"));
			return;
		}
		send(request, JoinAttestResponse.class, callback);
	}

	/**
	 * Verify in browser: asks the site for a pending verify that the member
	 * approves on the page at {@code url}. Sends exactly what an attest sends,
	 * less the code: the RSN, the account digest (never the raw account hash),
	 * the clan name as the game gave it, the plugin version and the device
	 * label. A relative {@code url} comes back resolved against the API's host.
	 */
	public void joinStart(String rsn, String digest, String clanName, String pluginVersion,
		Consumer<ApiResult<LinkStartResponse>> callback)
	{
		if (rsn == null || digest == null || clanName == null)
		{
			callback.accept(new ApiResult<>(ApiResult.Status.ERROR, null, null, "Log in to the game to verify"));
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("rsn", rsn);
		payload.addProperty("digest", digest);
		payload.addProperty("clanName", clanName);
		payload.addProperty("pluginVersion", pluginVersion);
		payload.addProperty("device", "RuneLite");
		Request.Builder request = joinRequest("/api/join/start", payload);
		if (request == null)
		{
			callback.accept(new ApiResult<>(ApiResult.Status.ERROR, null, null, "The site address isn't right"));
			return;
		}
		send(request, LinkStartResponse.class, result ->
		{
			LinkStartResponse s = result.getValue();
			if (s != null && s.getUrl() != null)
			{
				s.setUrl(siteUrl(s.getUrl()));
			}
			callback.accept(result);
		});
	}

	/**
	 * One poll of a Verify in browser request: {@code pending}, {@code denied},
	 * or the attest-shaped outcome (a token at most once). 404 means the request
	 * has expired, 409 that its answer was already collected, 429 and 5xx that
	 * the site wants the next poll later (keep polling).
	 */
	public void joinPoll(LinkStartResponse start, Consumer<ApiResult<JoinAttestResponse>> callback)
	{
		HttpUrl url = joinUrl("/api/join/poll");
		if (url == null || start.getRequest() == null || start.getPollKey() == null)
		{
			callback.accept(new ApiResult<>(ApiResult.Status.ERROR, null, null, "The site address isn't right"));
			return;
		}
		send(new Request.Builder()
			.url(url.newBuilder().addQueryParameter("req", start.getRequest()).build())
			.header("User-Agent", userAgent)
			.header("Accept", "application/json")
			.header("Authorization", "Poll " + start.getPollKey())
			.get(), JoinAttestResponse.class, callback);
	}

	/**
	 * Gives up a Verify in browser request (Cancel, shutdown, Connect off, a
	 * changed account): the site then refuses its Approve and its collect, and
	 * drops a token already handed over for it. One request, sent even while
	 * the poll route is backing off, so a blip just before Cancel can't leave
	 * the request open. {@code done} hears true when the site answered 200 or
	 * 404 (nothing left to approve), false otherwise.
	 */
	public void joinCancel(LinkStartResponse start, Consumer<Boolean> done)
	{
		HttpUrl url = joinUrl("/api/join/poll");
		if (url == null || start == null || start.getRequest() == null || start.getPollKey() == null)
		{
			done.accept(false);
			return;
		}
		Request request = new Request.Builder()
			.url(url.newBuilder().addQueryParameter("req", start.getRequest()).build())
			.header("User-Agent", userAgent)
			.header("Accept", "application/json")
			.header("Authorization", "Poll " + start.getPollKey())
			.delete()
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("DRM API join cancel failed", e);
				done.accept(false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody ignored = response.body())
				{
					done.accept(response.code() == 200 || response.code() == 404);
				}
			}
		});
	}

	/** {@code url} resolved against the API's host; null when it can't be read. Absolute links stay as they are. */
	String siteUrl(String url)
	{
		HttpUrl base = HttpUrl.parse(baseUrl);
		HttpUrl resolved = base == null || url == null ? null : base.resolve(url);
		return resolved == null ? null : resolved.toString();
	}

	private HttpUrl joinUrl(String absolutePath)
	{
		HttpUrl base = HttpUrl.parse(baseUrl);
		return base == null ? null : base.resolve(absolutePath);
	}

	/** A POST to a join route: an absolute path on the API's own host (the join routes aren't under /api/v1). */
	private Request.Builder joinRequest(String absolutePath, JsonObject payload)
	{
		HttpUrl url = joinUrl(absolutePath);
		if (url == null)
		{
			return null;
		}
		return new Request.Builder()
			.url(url)
			.header("User-Agent", userAgent)
			.header("Accept", "application/json")
			.post(RequestBody.create(JSON, gson.toJson(payload)));
	}

	/**
	 * A POST to a token-authenticated route with a JSON body; the answer is
	 * never cached. 401/403 come back as UNAUTHORIZED/FORBIDDEN, anything else
	 * that isn't 200 as ERROR with the site's own words.
	 */
	public <T> void postAuthed(String path, JsonObject payload, Class<T> type, Consumer<ApiResult<T>> callback)
	{
		String token = token();
		if (token == null)
		{
			callback.accept(new ApiResult<>(ApiResult.Status.UNAUTHORIZED, null, null, "Not signed in"));
			return;
		}
		send(newRequest(path).header("Authorization", "Bearer " + token)
			.post(RequestBody.create(JSON, gson.toJson(payload == null ? new JsonObject() : payload))), type, callback);
	}

	/** Uploads the member's model as {@code text/plain}. */
	public void uploadModel(String obj, Consumer<ApiResult<ModelStatusResponse>> callback)
	{
		String token = token();
		if (token == null)
		{
			callback.accept(new ApiResult<>(ApiResult.Status.UNAUTHORIZED, null, null, "Not signed in"));
			return;
		}
		send(newRequest("/plugin/model").header("Authorization", "Bearer " + token)
			.post(RequestBody.create(TEXT, obj)), ModelStatusResponse.class, callback);
	}

	/** Takes the member out of the splash pool and deletes their model and splash. */
	public void deleteModel(Consumer<ApiResult<ModelStatusResponse>> callback)
	{
		String token = token();
		if (token == null)
		{
			callback.accept(new ApiResult<>(ApiResult.Status.UNAUTHORIZED, null, null, "Not signed in"));
			return;
		}
		send(newRequest("/plugin/model").header("Authorization", "Bearer " + token).delete(),
			ModelStatusResponse.class, callback);
	}

	/**
	 * Downloads a splash image the site pointed at. Only https, at most
	 * {@link #MAX_IMAGE_BYTES}; no token is sent. The callback gets null on any
	 * failure, so the caller keeps its last good copy.
	 */
	public void fetchImage(String url, Consumer<BufferedImage> callback)
	{
		// The site gives its own images as paths ("/api/v1/featured/..."): resolve those against the API's host.
		HttpUrl base = HttpUrl.parse(baseUrl);
		HttpUrl parsed = url == null ? null
			: url.startsWith("/") && !url.startsWith("//") && base != null ? base.resolve(url) : HttpUrl.parse(url);
		// only the API's own host: the site can't send the client anywhere the IP warning doesn't name;
		// redirects are off (constructor), so this check also covers the final hop
		if (parsed == null || !parsed.isHttps() || base == null || !parsed.host().equalsIgnoreCase(base.host()))
		{
			callback.accept(null);
			return;
		}
		Request request = new Request.Builder().url(parsed).header("User-Agent", userAgent).get().build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("DRM splash image failed", e);
				callback.accept(null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					if (response.code() != 200 || body == null || body.contentLength() > MAX_IMAGE_BYTES)
					{
						callback.accept(null);
						return;
					}
					byte[] bytes = readCapped(body.byteStream(), MAX_IMAGE_BYTES);
					callback.accept(bytes == null ? null : ImageIO.read(new ByteArrayInputStream(bytes)));
				}
				catch (IOException | RuntimeException e)
				{
					log.debug("DRM splash image unreadable", e);
					callback.accept(null);
				}
			}
		});
	}

	/**
	 * One call to a POST-style route. A network failure, 429 or 5xx backs the
	 * path off like a GET; other refusals don't, as they are about that one
	 * request. The result carries the site's HTTP status when it answered.
	 */
	private <T> void send(Request.Builder request, Class<T> type, Consumer<ApiResult<T>> callback)
	{
		Request built = request.build();
		String path = built.url().encodedPath();
		if (backingOff(path, clock.instant()))
		{
			callback.accept(new ApiResult<>(ApiResult.Status.ERROR, null, null, BUSY));
			return;
		}
		http.newCall(built).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("DRM API {} failed", path, e);
				noteFailure(path, null);
				callback.accept(new ApiResult<>(ApiResult.Status.ERROR, null, null, "Couldn't reach dream-chasers.cc"));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					String capped = readText(body);
					String text = capped == null ? "" : capped;
					int code = response.code();
					if (code == 200 || code == 202 || code == 204)
					{
						T value = null;
						try
						{
							value = code == 204 || capped == null ? null : gson.fromJson(text, type);
						}
						catch (JsonParseException e)
						{
							log.debug("DRM API {} sent unreadable JSON", path, e);
						}
						noteSuccess(path);
						if (value == null && code != 204)
						{
							callback.accept(new ApiResult<>(ApiResult.Status.ERROR, null, null, "The site sent something unexpected", code));
							return;
						}
						callback.accept(new ApiResult<>(ApiResult.Status.OK, value, clock.instant(), null, code));
						return;
					}
					if (code == 429 || code >= 500)
					{
						noteFailure(path, response);
					}
					ApiResult.Status status = code == 401 ? ApiResult.Status.UNAUTHORIZED
						: code == 403 ? ApiResult.Status.FORBIDDEN : ApiResult.Status.ERROR;
					String fallback = code == 404 ? "That isn't switched on at the site yet"
						: code == 401 ? "Sign in again" : "dream-chasers.cc answered " + code;
					callback.accept(new ApiResult<>(status, null, null, errorText(text, fallback), code));
				}
				catch (IOException e)
				{
					noteFailure(path, null);
					callback.accept(new ApiResult<>(ApiResult.Status.ERROR, null, null, "Couldn't reach dream-chasers.cc"));
				}
			}
		});
	}

	/** The body as text, or null when it is longer than {@link #MAX_JSON_BYTES}. */
	static String readText(ResponseBody body) throws IOException
	{
		if (body == null)
		{
			return "";
		}
		if (body.contentLength() > MAX_JSON_BYTES)
		{
			return null;
		}
		byte[] bytes = readCapped(body.byteStream(), MAX_JSON_BYTES);
		if (bytes == null)
		{
			return null;
		}
		MediaType type = body.contentType();
		return new String(bytes, type == null ? StandardCharsets.UTF_8 : type.charset(StandardCharsets.UTF_8));
	}

	/** The whole stream, or null when it is longer than {@code max} bytes. */
	static byte[] readCapped(InputStream in, int max) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[16 * 1024];
		int n;
		while ((n = in.read(buf)) != -1)
		{
			if (out.size() + n > max)
			{
				return null;
			}
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}

	/** Drops cached members-only responses, e.g. after signing out. */
	public void clearMembersOnly()
	{
		for (Endpoint<?> e : new Endpoint<?>[]{Endpoint.EVENTS, Endpoint.ME, Endpoint.LIVE, Endpoint.MODEL})
		{
			cache.remove(e.getPath());
			lastAttempt.remove(e.getPath());
		}
	}

	/** Forgets one cached endpoint, so the next fetch goes to the site (outside any back-off). */
	public void invalidate(Endpoint<?> endpoint)
	{
		cache.remove(endpoint.getPath());
		lastAttempt.remove(endpoint.getPath());
	}

	private Request.Builder newRequest(String path)
	{
		return new Request.Builder()
			.url(baseUrl + path)
			.header("User-Agent", userAgent)
			.header("Accept", "application/json");
	}

	private String token()
	{
		String token = tokenSupplier.get();
		return token == null || token.trim().isEmpty() ? null : token.trim();
	}

	private String errorText(String body, String fallback)
	{
		try
		{
			ErrorResponse error = gson.fromJson(body, ErrorResponse.class);
			if (error != null && error.getError() != null && !error.getError().isEmpty())
			{
				return capitalize(error.getError());
			}
		}
		catch (JsonParseException e)
		{
			// not JSON: use the fallback
		}
		return fallback;
	}

	private static String capitalize(String s)
	{
		return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
	}

	/**
	 * Tidies a typed join code: spaces and dashes go, letters are upper-cased,
	 * and 8 characters are sent as the site shows them, {@code XXXX-XXXX}.
	 * Anything else is sent compact and the site decides.
	 */
	static String normalizeJoinCode(String code)
	{
		if (code == null)
		{
			return "";
		}
		String body = code.replaceAll("[\\s\\u00A0-]+", "").toUpperCase(Locale.ROOT);
		return body.length() == 8 ? body.substring(0, 4) + "-" + body.substring(4) : body;
	}

	/**
	 * Tidies a pasted code into the site's {@code DRM-XXXX-XXXX} form, so
	 * "drm 7kq4 p2xm" and "7KQ4P2XM" both work. Anything that isn't 8
	 * characters after the prefix is sent as typed (upper-cased, no spaces)
	 * and the site decides.
	 */
	static String normalizeCode(String code)
	{
		if (code == null)
		{
			return "";
		}
		String compact = code.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
		String body = compact.replaceAll("[^A-Z0-9]", "");
		if (body.startsWith("DRM"))
		{
			body = body.substring(3);
		}
		return body.length() == 8 ? "DRM-" + body.substring(0, 4) + "-" + body.substring(4) : compact;
	}
}
