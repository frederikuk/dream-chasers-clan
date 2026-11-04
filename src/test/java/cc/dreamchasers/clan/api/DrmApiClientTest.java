/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;

public class DrmApiClientTest
{
	private static final String BASE = "https://example.test/api/v1";
	private static final String UA = "DreamChasersClanPlugin/test (RuneLite; Discord: frederikuk)";

	private FakeHttp http;
	private FakeHttp.MutableClock clock;
	private String token;
	private DrmApiClient api;

	@Before
	public void setUp()
	{
		http = new FakeHttp();
		clock = new FakeHttp.MutableClock(Instant.parse("2026-11-01T12:00:00Z"));
		token = null;
		api = new DrmApiClient(http.client, new Gson(), BASE + "/", UA, () -> token, clock);
	}

	private static <T> T await(Consumer<Consumer<T>> call) throws Exception
	{
		CompletableFuture<T> f = new CompletableFuture<>();
		call.accept(f::complete);
		return f.get(5, TimeUnit.SECONDS);
	}

	private <T> ApiResult<T> fetch(Endpoint<T> ep, boolean force) throws Exception
	{
		return await(cb -> api.fetch(ep, force, cb));
	}

	// ---- public GETs, parsing and headers ----

	@Test
	public void competitionParsesRaidWeekAndIgnoresUnknownFields() throws Exception
	{
		http.reply(200, FakeHttp.fixture("competition-raid.json"), "public, max-age=300");

		ApiResult<CompetitionResponse> r = fetch(Endpoint.COMPETITION, false);

		assertEquals(ApiResult.Status.OK, r.getStatus());
		CompetitionResponse cr = r.getValue();
		assertEquals("finished", cr.getStatus());
		Competition c = cr.getCompetition();
		assertEquals(100002, c.getId());
		assertEquals("Raid week - Chambers of Xeric", c.getTitle());
		assertEquals(9, c.getParticipants());
		assertEquals(2, c.getParts().size());
		assertEquals("CM", c.getParts().get(1).getLabel());
		assertEquals(4, c.getStandings().size());
		Standing first = c.getStandings().get(0);
		assertEquals(1, first.getRank());
		assertEquals("Zokairox Lam", first.getRsn());
		assertEquals(12, first.getGained());
		assertEquals(Arrays.asList(2L, 10L), first.getParts());
		assertEquals("https://wiseoldman.net/competitions/100002", c.getWom());
	}

	@Test
	public void requestsUseBaseUrlUserAgentAndNoAuthOnPublicRoutes() throws Exception
	{
		http.reply(200, FakeHttp.fixture("links.json"), "public, max-age=3600");

		fetch(Endpoint.LINKS, false);

		assertEquals(1, http.requests.size());
		okhttp3.Request req = http.requests.get(0).request;
		assertEquals(BASE + "/links", req.url().toString());
		assertEquals("GET", req.method());
		assertEquals(UA, req.header("User-Agent"));
		assertEquals("application/json", req.header("Accept"));
		assertNull(req.header("Authorization"));
	}

	@Test
	public void upcomingCompetitionWithoutStandingsOrParts() throws Exception
	{
		http.reply(200, FakeHttp.fixture("competition.json"), "public, max-age=300");

		Competition c = fetch(Endpoint.COMPETITION, false).getValue().getCompetition();

		assertEquals("Skill of the Month - Fishing", c.getTitle());
		assertTrue(c.getStandings().isEmpty());
		assertNull(c.getParts());
	}

	@Test
	public void competitionNoneHasNullCompetition() throws Exception
	{
		http.reply(200, "{\"apiVersion\":1,\"status\":\"none\",\"competition\":null}", "public, max-age=300");

		CompetitionResponse cr = fetch(Endpoint.COMPETITION, false).getValue();

		assertEquals("none", cr.getStatus());
		assertNull(cr.getCompetition());
	}

	@Test
	public void hallOfFameParsesFixture() throws Exception
	{
		http.reply(200, FakeHttp.fixture("hall-of-fame.json"), "public, max-age=300");

		HallOfFameResponse hof = fetch(Endpoint.HALL_OF_FAME, false).getValue();

		assertEquals("2026-01-15T12:00:00+00:00", hof.getUpdatedAt());
		assertFalse(hof.getFirsts().isEmpty());
		HallOfFameResponse.HallOfFameFirst hydra = hof.getFirsts().get(0);
		assertEquals("Alchemical Hydra", hydra.getLabel());
		assertEquals("boss", hydra.getCategory());
		assertEquals(500, hydra.getTiers().get(0).getThreshold());
		assertEquals("Larkspire", hydra.getTiers().get(0).getHolders().get(0).getName());
		assertNull(hydra.getTiers().get(0).getHolders().get(0).getAt());
		assertEquals("2022-10-15", hydra.getTiers().get(0).getHolders().get(1).getAt());
	}

	@Test
	public void linksDiscordIsNullUntilThereIsAnInvite() throws Exception
	{
		http.reply(200, FakeHttp.fixture("links.json"), "public, max-age=3600");

		LinksResponse l = fetch(Endpoint.LINKS, false).getValue();

		assertEquals("https://dream-chasers.cc", l.getSite());
		assertEquals("https://dream-chasers.cc/hall-of-fame", l.getHallOfFame());
		assertNull(l.getDiscord());
	}

	// ---- caching ----

	@Test
	public void servesFromCacheWithinMaxAgeThenRefetches() throws Exception
	{
		http.reply(200, FakeHttp.fixture("competition.json"), "public, max-age=300");
		http.reply(200, FakeHttp.fixture("competition-raid.json"), "public, max-age=300");

		assertEquals(ApiResult.Status.OK, fetch(Endpoint.COMPETITION, false).getStatus());
		clock.advanceSeconds(299);
		ApiResult<CompetitionResponse> cached = fetch(Endpoint.COMPETITION, false);
		assertEquals(ApiResult.Status.CACHED, cached.getStatus());
		assertEquals(Instant.parse("2026-11-01T12:00:00Z"), cached.getFetchedAt());
		assertEquals(1, http.requests.size());

		clock.advanceSeconds(2);
		ApiResult<CompetitionResponse> fresh = fetch(Endpoint.COMPETITION, false);
		assertEquals(ApiResult.Status.OK, fresh.getStatus());
		assertEquals(100002, fresh.getValue().getCompetition().getId());
		assertEquals(2, http.requests.size());
	}

	@Test
	public void shortMaxAgeIsRaisedToTheEndpointFloor() throws Exception
	{
		http.reply(200, FakeHttp.fixture("competition.json"), "public, max-age=5");

		fetch(Endpoint.COMPETITION, false);
		clock.advanceSeconds(59);

		assertEquals(ApiResult.Status.CACHED, fetch(Endpoint.COMPETITION, false).getStatus());
		assertEquals(1, http.requests.size());
	}

	@Test
	public void missingCacheControlUsesTheFloor() throws Exception
	{
		http.reply(200, FakeHttp.fixture("links.json"), null);

		fetch(Endpoint.LINKS, false);
		clock.advanceSeconds(30);

		assertEquals(ApiResult.Status.CACHED, fetch(Endpoint.LINKS, false).getStatus());
	}

	@Test
	public void forcedRefreshIsRateLimited() throws Exception
	{
		http.reply(200, FakeHttp.fixture("competition.json"), "public, max-age=300");
		http.reply(200, FakeHttp.fixture("competition-raid.json"), "public, max-age=300");

		fetch(Endpoint.COMPETITION, false);
		clock.advanceSeconds(5);
		assertEquals(ApiResult.Status.CACHED, fetch(Endpoint.COMPETITION, true).getStatus());
		assertEquals(1, http.requests.size());

		clock.advanceSeconds(11);
		assertEquals(ApiResult.Status.OK, fetch(Endpoint.COMPETITION, true).getStatus());
		assertEquals(2, http.requests.size());
	}

	// ---- errors keep the last good copy ----

	@Test
	public void serverErrorKeepsLastGoodCopy() throws Exception
	{
		http.reply(200, FakeHttp.fixture("competition-raid.json"), "public, max-age=300");
		http.reply(502, "{\"apiVersion\":1,\"status\":\"error\"}", "public, max-age=30");

		fetch(Endpoint.COMPETITION, false);
		clock.advanceSeconds(301);
		ApiResult<CompetitionResponse> r = fetch(Endpoint.COMPETITION, false);

		assertEquals(ApiResult.Status.ERROR, r.getStatus());
		assertNotNull(r.getValue());
		assertEquals(100002, r.getValue().getCompetition().getId());
		assertEquals(Instant.parse("2026-11-01T12:00:00Z"), r.getFetchedAt());
		assertTrue(r.getMessage().contains("502"));
	}

	@Test
	public void networkFailureKeepsLastGoodCopy() throws Exception
	{
		http.reply(200, FakeHttp.fixture("links.json"), "public, max-age=3600");
		http.fail();

		fetch(Endpoint.LINKS, false);
		clock.advanceSeconds(3601);
		ApiResult<LinksResponse> r = fetch(Endpoint.LINKS, false);

		assertEquals(ApiResult.Status.ERROR, r.getStatus());
		assertEquals("https://dream-chasers.cc", r.getValue().getSite());
		assertEquals("Couldn't reach dream-chasers.cc", r.getMessage());
	}

	@Test
	public void errorWithNothingCachedHasNoValue() throws Exception
	{
		http.fail();

		ApiResult<HallOfFameResponse> r = fetch(Endpoint.HALL_OF_FAME, false);

		assertEquals(ApiResult.Status.ERROR, r.getStatus());
		assertNull(r.getValue());
		assertFalse(r.isFresh());
	}

	@Test
	public void malformedJsonIsAnErrorNotACrash() throws Exception
	{
		http.reply(200, FakeHttp.fixture("links.json"), "public, max-age=60");
		http.reply(200, "<html>an error page</html>", "no-store");

		fetch(Endpoint.LINKS, false);
		clock.advanceSeconds(61);
		ApiResult<LinksResponse> r = fetch(Endpoint.LINKS, false);

		assertEquals(ApiResult.Status.ERROR, r.getStatus());
		assertEquals("https://dream-chasers.cc", r.getValue().getSite());
	}

	// ---- back-off: a struggling site is left alone ----

	@Test
	public void failureBacksOffThenRecovers() throws Exception
	{
		http.reply(500, "", null);
		assertEquals(ApiResult.Status.ERROR, fetch(Endpoint.LINKS, false).getStatus());
		ApiResult<LinksResponse> held = fetch(Endpoint.LINKS, false);
		assertEquals(DrmApiClient.BUSY, held.getMessage());
		assertEquals("held back without a request", 1, http.requests.size());

		clock.advanceSeconds(61);
		http.reply(500, "", null);
		fetch(Endpoint.LINKS, false);
		assertEquals(2, http.requests.size());

		clock.advanceSeconds(61);
		fetch(Endpoint.LINKS, false);
		assertEquals("the second failure doubles the wait to 120 s", 2, http.requests.size());

		clock.advanceSeconds(60);
		http.reply(200, FakeHttp.fixture("links.json"), "public, max-age=60");
		assertEquals(ApiResult.Status.OK, fetch(Endpoint.LINKS, false).getStatus());
		assertEquals(3, http.requests.size());

		// a success resets it: the next failure waits 60 s again
		clock.advanceSeconds(61);
		http.reply(500, "", null);
		fetch(Endpoint.LINKS, false);
		clock.advanceSeconds(61);
		http.reply(200, FakeHttp.fixture("links.json"), "public, max-age=60");
		assertEquals(ApiResult.Status.OK, fetch(Endpoint.LINKS, false).getStatus());
		assertEquals(5, http.requests.size());
	}

	@Test
	public void backoffIsCappedAtFifteenMinutes() throws Exception
	{
		for (int i = 0; i < 8; i++)
		{
			http.reply(503, "", null);
			fetch(Endpoint.LINKS, false);
			clock.advanceSeconds(DrmApiClient.BACKOFF_MAX.getSeconds() + 1);
		}
		assertEquals("every failure waited at most the cap", 8, http.requests.size());
	}

	@Test
	public void retryAfterIsHonoured() throws Exception
	{
		http.reply(429, "", "Retry-After", "300");
		fetch(Endpoint.COMPETITION, false);

		clock.advanceSeconds(200);
		assertEquals(DrmApiClient.BUSY, fetch(Endpoint.COMPETITION, false).getMessage());
		assertEquals(1, http.requests.size());

		clock.advanceSeconds(101);
		http.reply(200, FakeHttp.fixture("competition.json"), "public, max-age=300");
		assertEquals(ApiResult.Status.OK, fetch(Endpoint.COMPETITION, false).getStatus());
		assertEquals(2, http.requests.size());

		assertEquals(0, DrmApiClient.retryAfterSeconds(null));
		assertEquals(0, DrmApiClient.retryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"));
		assertEquals(120, DrmApiClient.retryAfterSeconds(" 120 "));
	}

	@Test
	public void oneRequestInFlightPerPath() throws Exception
	{
		http.hold().reply(200, FakeHttp.fixture("links.json"), "public, max-age=60");
		CompletableFuture<ApiResult<LinksResponse>> first = new CompletableFuture<>();
		api.fetch(Endpoint.LINKS, false, first::complete);
		for (int i = 0; i < 250 && http.requests.isEmpty(); i++)
		{
			Thread.sleep(20);
		}

		ApiResult<LinksResponse> second = fetch(Endpoint.LINKS, false);
		assertEquals(DrmApiClient.BUSY, second.getMessage());
		http.release();

		assertEquals(ApiResult.Status.OK, first.get(5, TimeUnit.SECONDS).getStatus());
		assertEquals(1, http.requests.size());
	}

	@Test
	public void backoffDoesNotBlockFreshCache() throws Exception
	{
		http.reply(200, FakeHttp.fixture("competition.json"), "public, max-age=300");
		http.reply(500, "", null);
		fetch(Endpoint.COMPETITION, false);
		clock.advanceSeconds(20);
		assertEquals(ApiResult.Status.ERROR, fetch(Endpoint.COMPETITION, true).getStatus());

		clock.advanceSeconds(20);
		assertEquals(ApiResult.Status.CACHED, fetch(Endpoint.COMPETITION, false).getStatus());
		assertEquals("a forced refresh during the back-off gets the fresh copy",
			ApiResult.Status.CACHED, fetch(Endpoint.COMPETITION, true).getStatus());
		assertEquals(2, http.requests.size());
	}

	@Test
	public void postsBackOffOnServerErrorsButNotOnRefusals() throws Exception
	{
		token = "tok123";
		http.reply(500, "", null);
		ApiResult<ModelStatusResponse> r = await(cb -> api.uploadModel("x", cb));
		assertEquals(500, r.getCode());
		assertEquals(DrmApiClient.BUSY, DrmApiClientTest.<ApiResult<ModelStatusResponse>>await(cb -> api.uploadModel("x", cb)).getMessage());
		assertEquals(1, http.requests.size());

		// a refusal is about that one request: the next one still goes
		http.reply(400, "{\"error\":\"no\"}", null).reply(400, "{\"error\":\"no\"}", null);
		JsonObject body = new JsonObject();
		assertEquals(400, DrmApiClientTest.<ApiResult<BatchResponse>>await(cb -> api.postAuthed("/plugin/pbs/batch", body, BatchResponse.class, cb)).getCode());
		assertEquals(400, DrmApiClientTest.<ApiResult<BatchResponse>>await(cb -> api.postAuthed("/plugin/pbs/batch", body, BatchResponse.class, cb)).getCode());
		assertEquals(3, http.requests.size());
	}

	// ---- oversized bodies are not read ----

	private static String bigJson(int bytes)
	{
		return "{\"x\":\"" + "a".repeat(bytes) + "\"}";
	}

	@Test
	public void oversizedJsonKeepsLastGoodCopy() throws Exception
	{
		http.reply(200, FakeHttp.fixture("hall-of-fame.json"), "public, max-age=300");
		http.reply(200, bigJson(600 * 1024), "public, max-age=300");

		fetch(Endpoint.HALL_OF_FAME, false);
		clock.advanceSeconds(301);
		ApiResult<HallOfFameResponse> r = fetch(Endpoint.HALL_OF_FAME, false);

		assertEquals(ApiResult.Status.ERROR, r.getStatus());
		assertEquals("The site sent something unexpected", r.getMessage());
		assertEquals("2026-01-15T12:00:00+00:00", r.getValue().getUpdatedAt());
	}

	@Test
	public void oversizedJsonWithNothingCachedHasNoValue() throws Exception
	{
		http.reply(200, bigJson(600 * 1024), "public, max-age=300");

		ApiResult<LinksResponse> r = fetch(Endpoint.LINKS, false);

		assertEquals(ApiResult.Status.ERROR, r.getStatus());
		assertNull(r.getValue());
	}

	@Test
	public void linkWithOversizedBodyIsAnError() throws Exception
	{
		http.reply(200, bigJson(600 * 1024), "no-store");

		ApiResult<LinkResponse> r = await(cb -> api.link("DRM-AAAA-BBBB", "RuneLite", cb));

		assertEquals(ApiResult.Status.ERROR, r.getStatus());
		assertEquals("The site sent something unexpected", r.getMessage());
	}

	@Test
	public void postWithOversizedBodyIsAnError() throws Exception
	{
		token = "tok123";
		http.reply(200, bigJson(600 * 1024), null);

		ApiResult<ModelStatusResponse> r = await(cb -> api.uploadModel("x", cb));

		assertEquals(ApiResult.Status.ERROR, r.getStatus());
		assertEquals("The site sent something unexpected", r.getMessage());
	}

	@Test
	public void oversizedRevocationStillDropsCachedEvents() throws Exception
	{
		token = "drm_pt_abc";
		http.reply(200, FakeHttp.fixture("events.json"), "private, max-age=60");
		http.reply(401, bigJson(600 * 1024), "private, max-age=0");

		fetch(Endpoint.EVENTS, false);
		clock.advanceSeconds(301);
		ApiResult<EventsResponse> r = fetch(Endpoint.EVENTS, false);

		assertEquals(ApiResult.Status.UNAUTHORIZED, r.getStatus());
		assertNull(r.getValue());
	}

	@Test
	public void readTextHonoursTheCap() throws Exception
	{
		okhttp3.MediaType json = okhttp3.MediaType.parse("application/json");
		String under = "a".repeat(DrmApiClient.MAX_JSON_BYTES);
		String over = under + "a";
		assertEquals(under, DrmApiClient.readText(okhttp3.ResponseBody.create(json, under)));
		assertNull(DrmApiClient.readText(okhttp3.ResponseBody.create(json, over)));
		// no Content-Length (chunked): the stream itself is capped
		assertEquals(under, DrmApiClient.readText(okhttp3.ResponseBody.create(json, -1, new okio.Buffer().writeUtf8(under))));
		assertNull(DrmApiClient.readText(okhttp3.ResponseBody.create(json, -1, new okio.Buffer().writeUtf8(over))));
		assertEquals("", DrmApiClient.readText(null));
	}

	// ---- members-only events ----

	@Test
	public void eventsWithoutATokenMakeNoRequest() throws Exception
	{
		ApiResult<EventsResponse> r = fetch(Endpoint.EVENTS, false);

		assertEquals(ApiResult.Status.UNAUTHORIZED, r.getStatus());
		assertTrue(http.requests.isEmpty());
	}

	@Test
	public void eventsSendTheBearerTokenAndParse() throws Exception
	{
		token = " drm_pt_abc123 ";
		http.reply(200, FakeHttp.fixture("events.json"), "private, max-age=60");

		ApiResult<EventsResponse> r = fetch(Endpoint.EVENTS, false);

		assertEquals(ApiResult.Status.OK, r.getStatus());
		okhttp3.Request req = http.requests.get(0).request;
		assertEquals(BASE + "/plugin/events", req.url().toString());
		assertEquals("Bearer drm_pt_abc123", req.header("Authorization"));

		EventInfo zulrah = r.getValue().getEvents().get(0);
		assertEquals("Mass Zulrah", zulrah.getTitle());
		assertEquals("W330 clan hall", zulrah.getWhere());
		assertEquals("Bring anti-venom.\nMeet at the clan hall.", zulrah.getDetails());
		assertFalse(zulrah.isWeekly());
		assertEquals(Instant.parse("2026-11-02T19:00:00Z"), zulrah.startInstant());
		assertFalse(zulrah.isLive(Instant.parse("2026-11-02T18:59:59Z")));
		assertTrue(zulrah.isLive(Instant.parse("2026-11-02T19:00:00Z")));
		assertFalse(zulrah.isLive(Instant.parse("2026-11-02T21:00:00Z")));

		EventInfo wildy = r.getValue().getEvents().get(1);
		assertTrue(wildy.isWeekly());
		assertEquals("", wildy.getHost());
	}

	@Test
	public void eventsAreKeptAtLeastFiveMinutes() throws Exception
	{
		token = "drm_pt_abc";
		http.reply(200, FakeHttp.fixture("events.json"), "private, max-age=60");

		fetch(Endpoint.EVENTS, false);
		clock.advanceSeconds(299);

		assertEquals(ApiResult.Status.CACHED, fetch(Endpoint.EVENTS, false).getStatus());
		assertEquals(1, http.requests.size());
	}

	@Test
	public void revokedTokenDropsCachedEvents() throws Exception
	{
		token = "drm_pt_abc";
		http.reply(200, FakeHttp.fixture("events.json"), "private, max-age=60");
		http.reply(401, "{\"apiVersion\":1,\"error\":\"sign in\"}", "private, max-age=0");

		fetch(Endpoint.EVENTS, false);
		clock.advanceSeconds(301);
		ApiResult<EventsResponse> r = fetch(Endpoint.EVENTS, false);

		assertEquals(ApiResult.Status.UNAUTHORIZED, r.getStatus());
		assertNull("members-only data must not outlive the token", r.getValue());
		assertEquals("Sign in", r.getMessage());

		// and nothing stale is served from the cache afterwards
		http.fail();
		assertNull(fetch(Endpoint.EVENTS, false).getValue());
	}

	@Test
	public void nonMemberGetsForbidden() throws Exception
	{
		token = "drm_pt_abc";
		http.reply(403, "{\"apiVersion\":1,\"error\":\"not a clan member\"}", "private, max-age=0");

		ApiResult<EventsResponse> r = fetch(Endpoint.EVENTS, false);

		assertEquals(ApiResult.Status.FORBIDDEN, r.getStatus());
		assertEquals("Not a clan member", r.getMessage());
		assertNull(r.getValue());
	}

	@Test
	public void repeated403sBackOff() throws Exception
	{
		// a WAF or challenge page answers 403 and the token is kept, so the panel's 60 s refresh must not hammer it
		token = "drm_pt_abc";
		http.reply(403, "{\"apiVersion\":1,\"error\":\"not a clan member\"}", "private, max-age=0");
		assertEquals(ApiResult.Status.FORBIDDEN, fetch(Endpoint.EVENTS, false).getStatus());

		ApiResult<EventsResponse> held = fetch(Endpoint.EVENTS, true);
		assertEquals("still a 403 while held back", ApiResult.Status.FORBIDDEN, held.getStatus());
		assertEquals("Not a clan member", held.getMessage());
		assertNull(held.getValue());
		assertEquals("held back without a request", 1, http.requests.size());

		clock.advanceSeconds(61);
		http.reply(403, "", null);
		fetch(Endpoint.EVENTS, false);
		assertEquals(2, http.requests.size());
		clock.advanceSeconds(61);
		assertEquals(ApiResult.Status.FORBIDDEN, fetch(Endpoint.EVENTS, false).getStatus());
		assertEquals("the second 403 doubles the wait to 120 s", 2, http.requests.size());
	}

	@Test
	public void a401StillHasNoBackoff() throws Exception
	{
		token = "drm_pt_abc";
		http.reply(401, "", null);
		assertEquals(ApiResult.Status.UNAUTHORIZED, fetch(Endpoint.EVENTS, false).getStatus());
		http.reply(401, "", null);
		assertEquals(ApiResult.Status.UNAUTHORIZED, fetch(Endpoint.EVENTS, false).getStatus());
		assertEquals(2, http.requests.size());
	}

	@Test
	public void publicEventsRouteShapeStillParses() throws Exception
	{
		// The documented /events/next body, which the plugin route mirrors.
		EventsNext next = new Gson().fromJson(FakeHttp.fixture("events-next.json"), EventsNext.class);
		assertEquals("Mass Zulrah", next.event.getTitle());
		assertFalse(next.live);
		ErrorResponse err = new Gson().fromJson(FakeHttp.fixture("events-401.json"), ErrorResponse.class);
		assertEquals("members only", err.getError());
	}

	private static class EventsNext
	{
		EventInfo event;
		boolean live;
	}

	// ---- link code ----

	@Test
	public void linkPostsNormalisedCodeAndReturnsToken() throws Exception
	{
		http.reply(200, "{\"apiVersion\":1,\"token\":\"drm_pt_xyz\",\"name\":\"Maho z h\",\"expiresAt\":\"2027-04-30T00:00:00Z\"}", "no-store");

		ApiResult<LinkResponse> r = await(cb -> api.link("  drm-7kq4 p2xm\n", "RuneLite", cb));

		assertEquals(ApiResult.Status.OK, r.getStatus());
		assertEquals("drm_pt_xyz", r.getValue().getToken());
		assertEquals("Maho z h", r.getValue().getName());
		FakeHttp.Recorded rec = http.requests.get(0);
		assertEquals("POST", rec.request.method());
		assertEquals(BASE + "/plugin/link", rec.request.url().toString());
		assertNull("linking never sends an old token", rec.request.header("Authorization"));
		JsonObject body = new Gson().fromJson(rec.body, JsonObject.class);
		assertEquals("DRM-7KQ4-P2XM", body.get("code").getAsString());
		assertEquals("RuneLite", body.get("device").getAsString());
	}

	@Test
	public void linkWithBadCodeShowsTheSitesReason() throws Exception
	{
		http.reply(400, "{\"apiVersion\":1,\"error\":\"invalid or expired code\"}", "no-store");

		ApiResult<LinkResponse> r = await(cb -> api.link("DRM-AAAA-BBBB", "RuneLite", cb));

		assertEquals(ApiResult.Status.ERROR, r.getStatus());
		assertEquals("Invalid or expired code", r.getMessage());
	}

	@Test
	public void linkBeforeTheSiteHasTheRouteSaysSo() throws Exception
	{
		http.reply(404, "{\"error\":\"no such endpoint\",\"endpoints\":[]}", "no-store");

		ApiResult<LinkResponse> r = await(cb -> api.link("DRM-AAAA-BBBB", "RuneLite", cb));

		assertEquals("Sign-in isn't switched on at the site yet", r.getMessage());
	}

	@Test
	public void linkWithEmptyTokenIsAnError() throws Exception
	{
		http.reply(200, "{\"apiVersion\":1,\"token\":\"\"}", "no-store");

		ApiResult<LinkResponse> r = await(cb -> api.link("DRM-AAAA-BBBB", "RuneLite", cb));

		assertEquals(ApiResult.Status.ERROR, r.getStatus());
	}

	@Test
	public void emptyCodeMakesNoRequest() throws Exception
	{
		ApiResult<LinkResponse> r = await(cb -> api.link("   ", "RuneLite", cb));

		assertEquals(ApiResult.Status.ERROR, r.getStatus());
		assertTrue(http.requests.isEmpty());
	}

	@Test
	public void unlinkRevokesAtTheSiteAndForgetsEvents() throws Exception
	{
		token = "drm_pt_abc";
		http.reply(200, FakeHttp.fixture("events.json"), "private, max-age=60");
		fetch(Endpoint.EVENTS, false);

		http.reply(204, "", null);
		api.unlink();
		for (int i = 0; i < 50 && http.requests.size() < 2; i++)
		{
			Thread.sleep(20);
		}

		assertEquals(2, http.requests.size());
		okhttp3.Request req = http.requests.get(1).request;
		assertEquals(BASE + "/plugin/unlink", req.url().toString());
		assertEquals("POST", req.method());
		assertEquals("Bearer drm_pt_abc", req.header("Authorization"));

		token = null;
		assertEquals(ApiResult.Status.UNAUTHORIZED, fetch(Endpoint.EVENTS, false).getStatus());
	}

	// ---- clan splash ----

	@Test
	public void modelUploadIsPlainTextWithTheBearer() throws Exception
	{
		token = "tok123";
		http.reply(200, "{\"featured\":{\"rsn\":\"x\",\"status\":\"queued\",\"reason\":null}}", null);

		ApiResult<ModelStatusResponse> r = await(cb -> api.uploadModel("o x\nv 1 2 3\n", cb));

		assertEquals(ApiResult.Status.OK, r.getStatus());
		assertEquals("queued", r.getValue().getStatus());
		assertTrue("the site wraps the entry as featured", r.getValue().isOptedIn());
		FakeHttp.Recorded rec = http.requests.get(0);
		assertEquals(BASE + "/plugin/model", rec.request.url().toString());
		assertEquals("POST", rec.request.method());
		assertEquals("Bearer tok123", rec.request.header("Authorization"));
		assertEquals("text/plain; charset=utf-8", rec.request.body().contentType().toString());
		assertEquals("o x\nv 1 2 3\n", rec.body);
	}

	@Test
	public void modelUploadNeedsATokenAndPassesTheSitesRefusal() throws Exception
	{
		assertEquals(ApiResult.Status.UNAUTHORIZED, DrmApiClientTest.<ApiResult<ModelStatusResponse>>await(cb -> api.uploadModel("x", cb)).getStatus());
		assertTrue(http.requests.isEmpty());

		token = "tok123";
		http.reply(429, "{\"error\":\"one model every 10 minutes\"}", null);
		ApiResult<ModelStatusResponse> r = await(cb -> api.uploadModel("x", cb));
		assertEquals(ApiResult.Status.ERROR, r.getStatus());
		assertEquals("One model every 10 minutes", r.getMessage());
	}

	@Test
	public void removeIsADeleteWithTheBearer() throws Exception
	{
		token = "tok123";
		http.reply(204, "", null);

		ApiResult<ModelStatusResponse> r = await(cb -> api.deleteModel(cb));

		assertEquals(ApiResult.Status.OK, r.getStatus());
		assertEquals("DELETE", http.requests.get(0).request.method());
		assertEquals(BASE + "/plugin/model", http.requests.get(0).request.url().toString());
		assertEquals("Bearer tok123", http.requests.get(0).request.header("Authorization"));
	}

	@Test
	public void featuredIsPublicAndKeptAnHour() throws Exception
	{
		token = "tok123";
		http.reply(200, "{\"rsn\":\"Vorki\",\"panelUrl\":\"/api/v1/featured/Vorki/panel.png?v=1\",\"imageUrl\":\"https://x.test/i.png\",\"since\":\"2026-11-01\"}", "public, max-age=60");

		ApiResult<FeaturedResponse> r = fetch(Endpoint.FEATURED, false);
		assertEquals("Vorki", r.getValue().getRsn());
		assertNull("no token on the public route", http.requests.get(0).request.header("Authorization"));

		clock.advanceSeconds(3500);
		assertEquals(ApiResult.Status.CACHED, fetch(Endpoint.FEATURED, false).getStatus());
		assertEquals(1, http.requests.size());
	}

	@Test
	public void splashImagesMustBeHttpsAndSmall() throws Exception
	{
		assertNull(DrmApiClientTest.<java.awt.image.BufferedImage>await(cb -> api.fetchImage("http://x.test/p.png", cb)));
		assertNull(DrmApiClientTest.<java.awt.image.BufferedImage>await(cb -> api.fetchImage("not a url", cb)));
		assertNull(DrmApiClientTest.<java.awt.image.BufferedImage>await(cb -> api.fetchImage(null, cb)));
		assertNull("another host", DrmApiClientTest.<java.awt.image.BufferedImage>await(cb -> api.fetchImage("https://elsewhere.test/p.png", cb)));
		assertNull("protocol-relative", DrmApiClientTest.<java.awt.image.BufferedImage>await(cb -> api.fetchImage("//elsewhere.test/p.png", cb)));
		assertNull("a look-alike host", DrmApiClientTest.<java.awt.image.BufferedImage>await(cb -> api.fetchImage("https://example.test.evil.test/p.png", cb)));
		assertTrue("nothing was requested", http.requests.isEmpty());

		// the site's own form: a path, resolved against the API host
		http.reply(404, "", null);
		assertNull(DrmApiClientTest.<java.awt.image.BufferedImage>await(cb -> api.fetchImage("/api/v1/featured/Maho%20z%20h/panel.png?v=1", cb)));
		assertEquals("https://example.test/api/v1/featured/Maho%20z%20h/panel.png?v=1",
			http.requests.get(0).request.url().toString());
		assertNull("no token on images", http.requests.get(0).request.header("Authorization"));

		assertEquals(3, DrmApiClient.readCapped(new java.io.ByteArrayInputStream(new byte[3]), 3).length);
		assertNull(DrmApiClient.readCapped(new java.io.ByteArrayInputStream(new byte[4]), 3));
	}

	// ---- redirects are never followed ----

	@Test
	public void clientNeverFollowsRedirects()
	{
		assertTrue("RuneLite's client follows them by default", http.client.followRedirects());
		assertFalse(api.httpClient().followRedirects());
		assertFalse(api.httpClient().followSslRedirects());
	}

	@Test
	public void redirectIsAnErrorNotFollowed() throws Exception
	{
		http.reply(302, "", null);

		ApiResult<LinksResponse> r = fetch(Endpoint.LINKS, false);

		assertEquals(ApiResult.Status.ERROR, r.getStatus());
		assertNull(r.getValue());
		assertEquals(1, http.requests.size());
	}

	@Test
	public void imageRedirectGivesNull() throws Exception
	{
		http.reply(307, "", null);

		assertNull(DrmApiClientTest.<java.awt.image.BufferedImage>await(cb -> api.fetchImage("/api/v1/featured/x/splash.png", cb)));
		assertEquals(1, http.requests.size());
	}
}
