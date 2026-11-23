/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan;

import cc.dreamchasers.clan.api.ApiResult;
import cc.dreamchasers.clan.api.CompetitionResponse;
import cc.dreamchasers.clan.api.DrmApiClient;
import cc.dreamchasers.clan.api.Endpoint;
import cc.dreamchasers.clan.api.FeaturedResponse;
import cc.dreamchasers.clan.api.HallOfFameResponse;
import cc.dreamchasers.clan.api.KcResponse;
import cc.dreamchasers.clan.api.LiveActionResponse;
import cc.dreamchasers.clan.api.LiveJoinResponse;
import cc.dreamchasers.clan.api.LiveResponse;
import cc.dreamchasers.clan.api.LogoutResponse;
import cc.dreamchasers.clan.api.MeResponse;
import cc.dreamchasers.clan.api.ModelStatusResponse;
import cc.dreamchasers.clan.api.RaidResponse;
import cc.dreamchasers.clan.api.RosterResponse;
import cc.dreamchasers.clan.api.BatchResponse;
import cc.dreamchasers.clan.api.SubmitResponse;
import cc.dreamchasers.clan.api.XpResponse;
import cc.dreamchasers.clan.loot.ClogTracker;
import cc.dreamchasers.clan.loot.LootPicker;
import cc.dreamchasers.clan.loot.UnlockWatch;
import cc.dreamchasers.clan.pb.PbBackfill;
import cc.dreamchasers.clan.pb.PbParser;
import cc.dreamchasers.clan.pb.PbRecord;
import cc.dreamchasers.clan.pb.TrialTimer;
import cc.dreamchasers.clan.raid.RaidStats;
import cc.dreamchasers.clan.ui.DreamChasersPanel;
import cc.dreamchasers.clan.ui.SafeLinks;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Named;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.clan.ClanID;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.events.ClanChannelChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.widgets.Widget;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
	name = "Dream Chasers Clan",
	description = "Dream Chasers clan events, competition standings and Hall of Fame in the sidebar, with opt-in clan features: Verify for Discord, posting your PBs and collection log, DRM Live, the clan splash and tile packs",
	tags = {"clan", "dream chasers", "drm", "events", "competition", "hall of fame", "stream", "pb", "loot", "discord"}
)
public class DreamChasersClanPlugin extends Plugin implements DreamChasersPanel.Listener
{
	static final String VERSION = "0.5.5";
	/** RuneLite's HTTP client puts its own "RuneLite/x.y.z" in front of this. */
	static final String USER_AGENT = "DreamChasersClanPlugin/" + VERSION
		+ " (+https://dream-chasers.cc; Discord: frederikuk)";
	/**
	 * Dev only: point at staging with -Ddrmclan.api=..., honoured only when
	 * RuneLite runs in developer mode (not a config field on purpose). Every
	 * normal client uses production, https://dream-chasers.cc/api/v1.
	 */
	private static final String API_PROPERTY = "drmclan.api";
	private static final long TICK_SECONDS = 60;
	/** DRM Live's state is polled while its section is on screen (the site caches it for 30 s too). */
	private static final long LIVE_TICK_SECONDS = 30;
	/** One model upload per 10 minutes. */
	static final Duration MODEL_UPLOAD_INTERVAL = Duration.ofMinutes(10);
	private static final Duration SPLASH_IMAGE_RETRY = Duration.ofHours(1);

	@Inject
	private DreamChasersClanConfig config;

	@Inject
	@Named("developerMode")
	private boolean developerMode;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	private DrmApiClient api;
	private DreamChasersPanel panel;
	private NavigationButton navButton;
	private ScheduledFuture<?> tick;
	private ScheduledFuture<?> liveTick;
	private JoinFlow joinFlow;
	private VerifyFlow verifyFlow;
	// clan splash: the site enforces the upload limit too
	private volatile Instant lastModelUpload;
	private volatile String splashImageUrl;
	private volatile Instant splashImageTriedAt = Instant.EPOCH;
	private volatile boolean splashImageOk;
	// Pairs kill-count and time lines; client thread only
	private final PbParser pbParser = new PbParser();
	// Live competition KC: the newest count per boss waiting to go
	private final LiveKc liveKc = new LiveKc();
	// Live competition XP: the newest XP per skill and the slow send timer
	private final LiveXp liveXp = new LiveXp();
	// Blorva and Radiant oathplate: the items arriving in the inventory; client thread only
	private final UnlockWatch unlockWatch = new UnlockWatch();
	/** Who is logged in, for the one logout notice; null once sent or when nobody is. Client thread. */
	private LiveKc.Account liveAccount;
	/** A logout notice waiting for a {@code /competition} fetch that was already out; settled once. */
	private final AtomicReference<HeldNotice> heldLogout = new AtomicReference<>();
	/** The last logout notice sent, and when (client clock); guarded by {@code noticeLock}. */
	private LiveKc.Account lastNotice;
	private Instant lastNoticeAt;
	private final Object noticeLock = new Object();
	/**
	 * Game ticks since the last login or hop; the account-type varbit is only
	 * read once the game has had a tick or two to send it. Client thread.
	 */
	private int ticksSinceLogin;
	/** Ticks after a login before {@link #accountType()} trusts the varbit. */
	static final int ACCOUNT_TYPE_SETTLE_TICKS = 2;
	// Sailing's Barracuda Trials, timed from game state; client thread only
	private final TrialTimer trialTimer = new TrialTimer();
	// Old PBs from Chat Commands' store, checked on the first tick after login or sign-in
	private boolean backfillDue;
	// set by a login, a hop or the plugin starting; the LOGGED_IN after it is a real login,
	// any other LOGGED_IN ends a loading screen (LOADING can sit between the two)
	private boolean loginPending = true;
	// The "Collection log" popup: NOTIFICATION_START, then its text at NOTIFICATION_DELAY
	private boolean popupStarted;
	private Boolean clogHintShown;
	/** The time the site last took per boss and team size, so a standing PB is reported once a session. */
	private final Map<String, Double> pbSent = new ConcurrentHashMap<>();
	/** Counts forgetPbsSent calls; an answer from an earlier count marks nothing. */
	private volatile int pbSession;
	// New slots waiting a few ticks for their drop; client thread only
	private final ClogTracker clogTracker = new ClogTracker();
	/** The clan's big-drop amount, from the Hall of Fame feed; null until the site has said. */
	private volatile Long clanThreshold;
	// When the next raid-stats post is due; client thread, plus the answer on OkHttp's
	private volatile RaidStats raidStats;
	private boolean inRaid;
	/** From the last DRM Live answer: the member has agreed to the stream consent. */
	private volatile boolean liveConsented;
	/** Set when the member takes a stream slot, cleared when they leave or withdraw. */
	private volatile boolean liveOnStream;
	/** Names the site has verified to this member (GET /plugin/me); empty until known. */
	volatile List<String> verifiedNames = Collections.emptyList();
	/** Names verified in this session that a {@code /plugin/me} answer hasn't listed yet. */
	private final Set<String> justVerified = ConcurrentHashMap.newKeySet();
	/**
	 * The sign-in came from a held or awaiting-roster answer and {@code /plugin/me}
	 * hasn't said it's verified since: everything posts, Events waits. False
	 * until an answer says otherwise (older sites only had verified sign-ins).
	 */
	volatile boolean signInUnverified;
	/**
	 * The game name that sign-in waits on, for the "Verified as" line once it
	 * clears: the token's own name is the member's Discord name on a held
	 * sign-in, so it can't stand in. Null when none is known.
	 */
	volatile String waitedOn;
	/** Counts sign-ins and sign-outs; a {@code /plugin/me} answer asked under an earlier count is dropped. */
	volatile int signInGen;
	/**
	 * Set when the logged-in name is needed for the Verify fold but the game
	 * hasn't given it yet (it has none at the LOGGED_IN event itself): checked
	 * again on each game tick until it has. Client thread only.
	 */
	private boolean verifiedDue;
	/**
	 * Connect was ticked in the settings while signed out: the next game tick
	 * that can (logged in, own clan record says Dream Chasers) opens the
	 * browser to sign in. Set on whichever thread changed the config, read on
	 * the client thread. Never set at start-up, cleared by shutDown and by a
	 * profile switch (its ConfigChanged events are not the member's tick).
	 */
	private volatile boolean signInDue;
	/** Logged-in ticks in a row that signInDue has waited with no clan record (Reading.UNKNOWN). Client thread. */
	private int signInUnknownTicks;
	/**
	 * After this many (30 s), a player with no clan record at all is taken as
	 * blocked: the wait ends and the status line says why, so a much later
	 * clan join doesn't open the browser out of nowhere.
	 */
	static final int SIGN_IN_UNKNOWN_LIMIT = 50;
	/** Raid-stat answers in a row saying the member isn't on a stream slot. */
	private int raidNotShown;
	/** After this many, the member is taken as off the stream and raid stats stop until they join again. */
	static final int RAID_NOT_SHOWN_LIMIT = 3;
	// verified join: the player's own clan record, re-read on game ticks until it has loaded; client thread
	private volatile boolean clanCheckDue = true;
	private volatile OwnClan.Reading ownClan = OwnClan.Reading.UNKNOWN;
	// the last account hash digested, so a feed post doesn't hash again; client thread only
	private long digestedHash = OwnClan.NO_ACCOUNT;
	private String digestOfHash;
	// officer roster sync: this session's throttle and last answer; the own rank is read with the own record
	private volatile RosterSync roster;
	private volatile boolean officer;
	private volatile String rosterLine;
	/** Ticks between closing the Members screen and reading the list (the clan-settings update can lag the close). */
	private static final int ROSTER_READ_DELAY_TICKS = 2;
	// the last line an answer from the site gave, shown again when a wait ends with nothing to send
	private volatile String rosterAnswer;
	// a close of the Members screen is read two ticks later, once the server's clan-settings update is in; client thread only
	private int rosterDueTick = -1;
	// a close inside the minute: the list goes by itself once the minute is up; set on the client thread
	private volatile boolean rosterPending;

	@Override
	protected void startUp()
	{
		signInDue = false;     // RuneLite reuses the instance: an earlier session's tick of Connect doesn't carry over
		signInUnknownTicks = 0;
		String apiBase = developerMode ? System.getProperty(API_PROPERTY, DrmApiClient.DEFAULT_BASE_URL) : DrmApiClient.DEFAULT_BASE_URL;
		api = new DrmApiClient(okHttpClient, gson, apiBase, USER_AGENT, config::siteToken, Clock.systemUTC());
		// every site-given link is checked before the browser opens it
		SafeLinks safeLinks = new SafeLinks(apiBase);

		BufferedImage mark = ImageUtil.loadImageResource(getClass(), "wordmark.png");   // the site's wordmark
		panel = new DreamChasersPanel(this, mark, Clock.systemUTC(), ZoneId.systemDefault(), safeLinks);
		navButton = NavigationButton.builder()
			.tooltip("Dream Chasers")
			.icon(ImageUtil.loadImageResource(getClass(), "nav_icon.png"))
			.priority(7)
			.panel(panel)
			.build();
		if (config.showSidebar())
		{
			clientToolbar.addNavigation(navButton);
		}

		raidStats = new RaidStats();
		roster = new RosterSync();
		panel.setCollapsed(config.collapsedSections());
		panel.setConnected(config.connect());
		panel.setLiveEnabled(config.joinLive());
		panel.setSplashEnabled(config.clanSplash());
		panel.setSplashOptedIn(config.splashOptedIn());
		panel.setTilesEnabled(config.tilePacks());
		panel.setSignedInAs(signedInName());
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			ownClan = OwnClan.Reading.LOGGED_OUT;
		}
		panel.setOwnClan(OwnClan.blocker(ownClan), ownClan.getRsn());
		joinFlow = new JoinFlow(api, () -> ownClan, VERSION);
		verifyFlow = new VerifyFlow(api, executor, Clock.systemUTC(), url -> safeLinks.open(url, safeLinks.site()),
			() -> ownClan, VERSION);
		panel.setAllOn(EnableAll.allOn(this::featureOn));
		// Only refreshes what is stale, and only while the panel is open. With Connect on, startUp
		// and turning Connect on fetch once, and a login with collection log posting on fetches
		// the Hall of Fame for the big-drop threshold.
		tick = executor.scheduleAtFixedRate(() ->
		{
			if (panel != null && panel.isShowing())
			{
				refresh(false);
			}
		}, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
		liveTick = executor.scheduleAtFixedRate(() ->
		{
			if (panel != null && panel.isLiveShown())
			{
				refreshLive(false);
			}
		}, LIVE_TICK_SECONDS, LIVE_TICK_SECONDS, TimeUnit.SECONDS);
		refresh(false);
	}

	@Override
	protected void shutDown()
	{
		signInDue = false;
		signInUnknownTicks = 0;
		for (ScheduledFuture<?> f : new ScheduledFuture<?>[]{tick, liveTick})
		{
			if (f != null)
			{
				f.cancel(false);
			}
		}
		tick = null;
		liveTick = null;
		if (joinFlow != null)
		{
			joinFlow.cancel();
			joinFlow = null;
		}
		if (verifyFlow != null)
		{
			verifyFlow.cancel();
			verifyFlow = null;
		}
		if (clientToolbar != null)     // injected; null only in tests that stop a bare instance
		{
			clientToolbar.removeNavigation(navButton);
		}
		if (panel != null)
		{
			panel.dispose();
		}
		panel = null;
		navButton = null;
		api = null;
		raidStats = null;
		roster = null;
		officer = false;
		rosterLine = null;
		rosterAnswer = null;
		rosterDueTick = -1;
		rosterPending = false;
		inRaid = false;
		liveConsented = false;
		liveOnStream = false;
		loginPending = true;
		clanCheckDue = true;
		ownClan = OwnClan.Reading.UNKNOWN;
		pbParser.reset();
		liveKc.reset();
		liveXp.reset();
		unlockWatch.reset();
		liveAccount = null;     // stopping the plugin isn't a logout: nothing is sent
		heldLogout.set(null);
		forgetPbsSent();
		clogTracker.reset();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			if (ownClan.getState() == OwnClan.State.LOGGED_OUT)
			{
				setOwnClan(OwnClan.Reading.UNKNOWN);
			}
			// LOGGED_IN also ends every loading screen (teleports, region changes): those fetch nothing
			if (!loginPending)
			{
				return;
			}
			loginPending = false;
			onRealLogin();
		}
		else if (state == GameState.LOGGING_IN || state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			loginPending = true;
			ticksSinceLogin = 0;
			if (state == GameState.LOGGING_IN)
			{
				return;
			}
			liveLogout();     // a real logout or a hop: hiscores move now, so the site may ask Wise Old Man
			setOwnClan(OwnClan.Reading.LOGGED_OUT);
			clanCheckDue = true;
			officer = false;
			rosterDueTick = -1;
			pbParser.reset();
			liveKc.reset();     // maybe another account next; KC is cumulative, so the next kill sends the newest
			liveXp.reset();     // the same for XP: the next session starts from the game's login values
			unlockWatch.reset();     // the next inventory load is a starting point, not an arrival
			trialTimer.reset();
			clogTracker.reset();
			if (state == GameState.LOGIN_SCREEN)
			{
				forgetPbsSent();     // maybe another account next
				liveOnStream = false;
				RosterSync r = roster;
				if (r != null)
				{
					r.resume();     // another account may be an officer
				}
				rosterLine = null;
				rosterAnswer = null;
				rosterPending = false;
			}
		}
	}

	/** A login or a world hop: the old-PB batch is due, and the panel (or just the loot threshold) refreshes. */
	void onRealLogin()
	{
		backfillDue = true;
		clanCheckDue = true;
		verifiedNeeded();     // another account may be verified, or not
		DreamChasersPanel panel = this.panel;
		if (panel != null && panel.isShowing())
		{
			refresh(false);
		}
		else
		{
			refreshThreshold();
		}
	}

	// ---- personal bests ----

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		ChatMessageType type = event.getType();
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM
			&& type != ChatMessageType.FRIENDSCHATNOTIFICATION)
		{
			return;
		}
		if (type == ChatMessageType.GAMEMESSAGE && clogOn())
		{
			String item = ClogTracker.clogItem(event.getMessage());
			if (item != null)
			{
				clogTracker.onClog(item, client.getTickCount());
				return;
			}
		}
		if (liveKcOn())
		{
			PbParser.KillCount k = PbParser.killCount(event.getMessage());
			Player me = client.getLocalPlayer();
			if (k != null && me != null && me.getName() != null)
			{
				liveKc(k, me.getName(), accountDigest(), accountType());
			}
		}
		if (!config.connect() || !config.postPbs() || signedInName() == null)
		{
			return;
		}
		PbRecord record = pbParser.onMessage(event.getMessage(), client.getTickCount());
		if (record == null || TrialTimer.isTrial(record.getBoss()))
		{
			return;     // trials are timed from game state (trialTick), so a chat line never posts one twice
		}
		if (record.needsPartySize())
		{
			int party = partySize(record.getBoss());
			if (party > 0)
			{
				record = record.withTeamSize(String.valueOf(party));
			}
		}
		Player me = client.getLocalPlayer();
		postPb(record, me == null ? null : me.getName());
	}

	/** Theatre of Blood and Tombs of Amascut keep the party in varbits; 0 when unknown. */
	private int partySize(String boss)
	{
		int[] slots = boss.startsWith("Theatre of Blood")
			? new int[]{VarbitID.TOB_CLIENT_P0, VarbitID.TOB_CLIENT_P1, VarbitID.TOB_CLIENT_P2,
				VarbitID.TOB_CLIENT_P3, VarbitID.TOB_CLIENT_P4}
			: new int[]{VarbitID.TOA_CLIENT_P0, VarbitID.TOA_CLIENT_P1, VarbitID.TOA_CLIENT_P2,
				VarbitID.TOA_CLIENT_P3, VarbitID.TOA_CLIENT_P4, VarbitID.TOA_CLIENT_P5,
				VarbitID.TOA_CLIENT_P6, VarbitID.TOA_CLIENT_P7};
		int n = 0;
		for (int v : slots)
		{
			n += client.getVarbitValue(v) > 0 ? 1 : 0;
		}
		return n;
	}

	private void postPb(PbRecord record, String rsn)
	{
		DrmApiClient api = this.api;
		if (api == null || rsn == null)
		{
			return;
		}
		if (!pbDue(record))
		{
			return;     // the site already took this standing PB this session
		}
		int session = pbSession;
		int tokenGen = signInGen;
		JsonObject body = Uploads.withAcct(Uploads.pb(record, rsn, Clock.systemUTC().instant(), accountDigest()), accountType());
		api.postAuthed("/plugin/pbs", body, SubmitResponse.class,
			result -> pbAnswered(record, session, tokenGen, result));
	}

	/** A new PB always goes; a standing one only until the site has taken it (or a better one) this session. */
	boolean pbDue(PbRecord record)
	{
		Double last = pbSent.get(record.key());
		return record.isPb() || last == null || last > record.getSeconds();
	}

	/**
	 * Only a 2xx marks a PB sent, so a refused or lost one goes again with the
	 * boss's next time line, with no relog. Nothing is scheduled: the next kill
	 * sets the pace, and the client's back-off (and the site's Retry-After on a
	 * 429) holds it back. {@code session} keeps an answer that lands after a
	 * logout from marking the next account's PB, and {@code tokenGen} a 401
	 * for an earlier sign-in's token from forgetting the new one.
	 */
	void pbAnswered(PbRecord record, int session, int tokenGen, ApiResult<SubmitResponse> result)
	{
		if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
		{
			forgetToken(tokenGen);
		}
		if (result.getStatus() != ApiResult.Status.OK)
		{
			log.debug("PB for {} not taken: {}", record.getBoss(), result.getMessage());
			return;
		}
		if (session == pbSession)
		{
			pbSent.merge(record.key(), record.getSeconds(), Math::min);
		}
	}

	// ---- live competition KC ----

	/** Live competition KC is on, Connect is on and the member is signed in. */
	boolean liveKcOn()
	{
		return config.connect() && config.liveCompetitionKc() && signedInName() != null;
	}

	/**
	 * One kill-count line of the player's own. Sent only when the boss is in
	 * the site's {@code kcBosses} for the running competition, read from the
	 * cached {@code /competition} (fetched only once that copy is stale; a
	 * failed fetch hands back the last good copy). {@code rsn}, {@code digest}
	 * and {@code acct} are read on the client thread before this is called.
	 */
	void liveKc(PbParser.KillCount k, String rsn, String digest, Integer acct)
	{
		DrmApiClient api = api();
		if (api == null || !mainGameWorld())
		{
			return;     // a Leagues or Deadman kill count isn't the main game's
		}
		int tokenGen = signInGen;
		int session = liveKc.session();
		LiveKc.Poster poster = (boss, kc, done) ->
			api.postAuthed("/plugin/kc", Uploads.kc(boss, kc, rsn, digest, acct), KcResponse.class, answer ->
			{
				if (answer.getStatus() == ApiResult.Status.UNAUTHORIZED)
				{
					forgetToken(tokenGen);
				}
				if (answer.getStatus() != ApiResult.Status.OK)
				{
					log.debug("KC for {} not taken: {}", boss, answer.getMessage());
				}
				done.accept(answer);
			});
		// no copy at all (none yet, and the fetch failed or another is out): kept unchecked, not dropped
		api.fetch(Endpoint.COMPETITION, false, result ->
		{
			liveKc.kill(session, result.getValue(), k.getBoss(), k.getKc(), poster);
			liveXp.heard(result.getValue());
			competitionLanded(result);
		});
	}

	/**
	 * Notes who is logged in, once the account-type varbit is trusted, so a
	 * logout can still say who it was. Only while Live competition KC is on.
	 * Client thread.
	 */
	void liveAccountTick()
	{
		if (!liveKcOn() || !loggedIn() || ticksSinceLogin < ACCOUNT_TYPE_SETTLE_TICKS)
		{
			return;
		}
		if (!mainGameWorld())
		{
			// Leagues, Deadman and the like: the main hiscores Wise Old Man reads don't move. This runs on
			// the world being left, so a hop from a main world to one of these still sends, the other way not.
			liveAccount = null;
			return;
		}
		LiveKc.Account now = liveAccountNow();
		LiveKc.Account was = liveAccount;
		if (now != null && (was == null || !was.same(now.rsn, now.digest, now.acct)))
		{
			liveAccount = now;
		}
	}

	/** The logged-in player's own name, hashed account id and account type; null without a name. Overridden in tests. */
	LiveKc.Account liveAccountNow()
	{
		String name = localName();
		return name == null ? null : new LiveKc.Account(name, accountDigest(), accountType());
	}

	// ---- live competition XP ----

	/**
	 * The player's own XP (StatChanged is only ever the local player's), noted
	 * for Live competition XP while Live competition KC is on, on a main-game
	 * world. The burst the game sends at login is each skill's starting point.
	 * Sailing comes in like any other skill. The overall total has no
	 * StatChanged of its own: {@link #liveXpTick} reads it.
	 */
	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() == null || !liveKcOn() || !mainGameWorld())
		{
			return;
		}
		liveXp.stat(LiveXp.metricOf(event.getSkill()), event.getXp());
	}

	/** The game's overall XP. Overridden in tests. Client thread. */
	long overallXp()
	{
		return client.getOverallExperience();
	}

	/**
	 * Each game tick: when {@link LiveXp#due} says a send may go (XP moved, the
	 * interval up, no cap), reads the cached {@code /competition} for its
	 * {@code xpSkill} and sends the newest XP in it to {@code POST /plugin/xp},
	 * unless the site already has that value. Own account, main-game worlds,
	 * signed in with Live competition KC on. Client thread.
	 */
	void liveXpTick()
	{
		if (!liveKcOn() || !loggedIn() || ticksSinceLogin < ACCOUNT_TYPE_SETTLE_TICKS || !mainGameWorld())
		{
			return;
		}
		// the total, once the login burst is in (its first value here is the starting point)
		liveXp.stat(LiveXp.OVERALL, overallXp());
		DrmApiClient api = api();
		if (api == null || !liveXp.due(liveNow()))
		{
			return;
		}
		int s = liveXp.session();
		LiveKc.Account who = liveAccountNow();
		if (who == null)
		{
			liveXp.settle(s);
			return;
		}
		int tokenGen = signInGen;
		// cache-first: the copy the panel, kills and logouts already use
		api.fetch(Endpoint.COMPETITION, false, result ->
		{
			LiveXp.Send send = liveXp.pick(s, result.getValue());
			competitionLanded(result);
			if (send == null)
			{
				return;
			}
			api.postAuthed("/plugin/xp", Uploads.xp(send.skill, send.xp, who.rsn, who.digest, who.acct), XpResponse.class, answer ->
			{
				if (answer.getStatus() == ApiResult.Status.UNAUTHORIZED)
				{
					forgetToken(tokenGen);
				}
				if (answer.getStatus() != ApiResult.Status.OK)
				{
					log.debug("XP for {} not taken: {}", send.skill, answer.getMessage());
				}
				liveXp.answered(s, send, answer, liveNow());
			});
		});
	}

	/** The XP timer, for tests. */
	LiveXp liveXp()
	{
		return liveXp;
	}

	/**
	 * A real logout (LOGIN_SCREEN) or a world hop: one {@code POST
	 * /plugin/logout} for the account that was logged in, when Live
	 * competition KC is on, the member is signed in and the cached
	 * {@code /competition} copy says {@code jitLogout} (any clan competition
	 * running, skills too) or lists any boss (all an older site says). The
	 * game's hiscores move on
	 * logout, so the site can ask Wise Old Man for them (it does so only for a
	 * participant, at most once per member per 10 minutes). Sent once: the
	 * account is forgotten here, so a failed login back to LOGIN_SCREEN sends
	 * nothing, and a failed send is never retried. Nothing goes from a
	 * Leagues, Deadman or other non-main world (the account isn't noted
	 * there), nor within {@link LiveKc#NOTICE_GAP_SECONDS} seconds of the last
	 * notice for the same account. With no copy yet and another fetch already
	 * out, the notice is held for that fetch's answer, once. Client thread.
	 */
	void liveLogout()
	{
		LiveKc.Account who = liveAccount;
		liveAccount = null;
		if (who == null || !liveKcOn())
		{
			return;
		}
		Map<String, Long> xp = liveXp.snapshot();     // read now: the hop or logout resets it next
		DrmApiClient api = api();
		if (api == null)
		{
			return;
		}
		if (noticeRecent(who, liveNow()))
		{
			return;     // one went for this account under two minutes ago: the site would do nothing more with it
		}
		int tokenGen = signInGen;
		// cache-first: the copy the panel and the kills already use; a GET only when it is stale
		api.fetch(Endpoint.COMPETITION, false, result ->
		{
			if (noAnswerYet(result))
			{
				// no copy yet and another fetch is out: its answer settles this, once
				heldLogout.set(new HeldNotice(who, tokenGen, xp));
				return;
			}
			liveXp.heard(result.getValue());
			logoutNotice(api, who, tokenGen, xp, result.getValue());
			competitionLanded(result);
		});
	}

	/**
	 * A logout notice kept until the {@code /competition} fetch already out has
	 * answered. A back-off refusal with no copy reads the same, so the notice
	 * then waits for the next answer (a kill or the panel), still sent once.
	 */
	private static final class HeldNotice
	{
		final LiveKc.Account who;
		final int tokenGen;
		final Map<String, Long> xp;

		HeldNotice(LiveKc.Account who, int tokenGen, Map<String, Long> xp)
		{
			this.who = who;
			this.tokenGen = tokenGen;
			this.xp = xp;
		}
	}

	/** No copy at all and no answer: the fetch was refused because another one is out. */
	static boolean noAnswerYet(ApiResult<?> result)
	{
		return result.getValue() == null && DrmApiClient.BUSY.equals(result.getMessage());
	}

	/**
	 * Any {@code /competition} answer but a busy refusal: settles a held
	 * logout notice once (sent when the answer wants logouts, else dropped).
	 */
	void competitionLanded(ApiResult<CompetitionResponse> result)
	{
		if (noAnswerYet(result))
		{
			return;
		}
		HeldNotice held = heldLogout.getAndSet(null);
		if (held == null)
		{
			return;
		}
		DrmApiClient api = api();
		if (api == null || !liveKcOn())
		{
			return;
		}
		logoutNotice(api, held.who, held.tokenGen, held.xp, result.getValue());
	}

	/**
	 * Sends the one logout notice when a competition is running and none went
	 * for the account lately, with the player's XP in the competition's skill
	 * ({@code xpSkill}) when this session saw it. That XP goes even when the
	 * site has capped the timed sends: the logout notice is what carries XP then.
	 */
	private void logoutNotice(DrmApiClient api, LiveKc.Account who, int tokenGen, Map<String, Long> xp, CompetitionResponse comp)
	{
		if (!LiveKc.logoutWanted(comp) || !takeNoticeSlot(who, liveNow()))
		{
			return;     // no clan competition running, or one just went
		}
		String skill = LiveXp.wanted(comp);
		Long skillXp = skill == null || xp == null ? null : xp.get(skill);
		JsonObject body = Uploads.logout(who.rsn, who.digest, who.acct, skillXp == null ? null : skill, skillXp);
		api.postAuthed("/plugin/logout", body, LogoutResponse.class, answer ->
		{
			if (answer.getStatus() == ApiResult.Status.UNAUTHORIZED)
			{
				forgetToken(tokenGen);
			}
			if (answer.getStatus() != ApiResult.Status.OK)
			{
				log.debug("Logout notice not taken: {}", answer.getMessage());
			}
		});
	}

	/** Whether a notice for this account went less than {@link LiveKc#NOTICE_GAP_SECONDS} ago. */
	boolean noticeRecent(LiveKc.Account who, Instant now)
	{
		synchronized (noticeLock)
		{
			return lastNotice != null && lastNotice.same(who.rsn, who.digest, who.acct)
				&& now.isBefore(lastNoticeAt.plusSeconds(LiveKc.NOTICE_GAP_SECONDS));
		}
	}

	/** Claims the send for this account now, unless one went within the gap. */
	private boolean takeNoticeSlot(LiveKc.Account who, Instant now)
	{
		synchronized (noticeLock)
		{
			if (noticeRecent(who, now))
			{
				return false;
			}
			lastNotice = who;
			lastNoticeAt = now;
			return true;
		}
	}

	/** The client clock for the notice gap. Overridden in tests. */
	Instant liveNow()
	{
		return Clock.systemUTC().instant();
	}

	/**
	 * Worlds whose progress isn't on the main-game hiscores Wise Old Man reads,
	 * or isn't kept at all: Leagues, Deadman, tournament, beta, fresh start,
	 * quest speedrunning and no-save worlds.
	 */
	static final Set<WorldType> NOT_MAIN_GAME = EnumSet.of(WorldType.SEASONAL, WorldType.DEADMAN,
		WorldType.TOURNAMENT_WORLD, WorldType.BETA_WORLD, WorldType.FRESH_START_WORLD,
		WorldType.QUEST_SPEEDRUNNING, WorldType.NOSAVE_MODE);

	/** Whether a world with these types counts for the main game; an unknown (null) set does. */
	static boolean mainGame(Set<WorldType> types)
	{
		if (types == null)
		{
			return true;
		}
		for (WorldType t : types)
		{
			if (NOT_MAIN_GAME.contains(t))
			{
				return false;
			}
		}
		return true;
	}

	/** Whether the current world counts for the main game. Client thread. Overridden in tests. */
	boolean mainGameWorld()
	{
		return mainGame(client.getWorldType());
	}

	/** The site client while the plugin runs; null otherwise. Overridden in tests. */
	DrmApiClient api()
	{
		return api;
	}

	/**
	 * The game's account type for the player's own account (varbit
	 * {@code IRONMAN}: 0 normal, 1 ironman, 2 ultimate, 3 hardcore, 4 group,
	 * 5 hardcore group, 6 unranked group). Null when logged out, in the first
	 * ticks after a login (the varbit may not be in yet, and a 0 would read as
	 * normal), or out of range. Never anyone else's. Client thread.
	 */
	Integer accountType()
	{
		if (!loggedIn() || ticksSinceLogin < ACCOUNT_TYPE_SETTLE_TICKS)
		{
			return null;
		}
		int v = accountTypeVarbit();
		return v >= 0 && v <= 6 ? v : null;
	}

	/** The own account's {@code IRONMAN} varbit, as the game has it now. Overridden in tests. Client thread. */
	int accountTypeVarbit()
	{
		return client.getVarbitValue(VarbitID.IRONMAN);
	}

	/** A logout to the login screen, or the plugin stopping: the next account starts with nothing sent. */
	void forgetPbsSent()
	{
		pbSession++;
		pbSent.clear();
	}

	// ---- live raid stats ----

	/**
	 * Inside CoX, ToB or ToA, while on the clan stream with the consent given
	 * (see raidShareAllowed): the player's own HP, prayer and inventory, on a
	 * change or every 30 s, never more than once every 5 s, and as slowly as
	 * the site asks.
	 */
	@Subscribe
	public void onGameTick(GameTick tick)
	{
		// RuneLite allows one handler per event, named after it: the collection
		// log tick runs here too (a second GameTick handler stops the plugin starting)
		if (ticksSinceLogin < ACCOUNT_TYPE_SETTLE_TICKS)
		{
			ticksSinceLogin++;
		}
		clientTicks();
		verifiedTick();
		signInTick();     // after clanTick (in clientTicks) has read the own clan record
	}

	/** The ticks that read the game itself; overridden (to nothing) in tests that drive onGameTick. Client thread. */
	void clientTicks()
	{
		clanTick();
		clogTick();
		raidTick();
		trialTick();
		backfillTick();
		rosterTick();
		liveAccountTick();
		liveXpTick();
	}

	/**
	 * CONNECT_KEY changed ({@code ConfigChanged} values: "true", "false" or
	 * null when unset). Only a change from off to on, while signed out, arms
	 * the sign-in on a later tick; off clears it.
	 */
	void connectChanged(String oldValue, String newValue)
	{
		boolean on = Boolean.parseBoolean(newValue);
		if (!on)
		{
			signInDue = false;
		}
		else if (!Boolean.parseBoolean(oldValue) && !isSignedIn())
		{
			signInDue = true;
			signInUnknownTicks = 0;
		}
	}

	/**
	 * A profile switch posts ConfigChanged for every setting that differs
	 * (before ProfileChanged, on the same thread): a Connect that comes on
	 * that way wasn't ticked by the member, so the sign-in it armed is dropped.
	 */
	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		signInDue = false;
	}

	/** Whether a tick still owes the sign-in that ticking Connect asked for. */
	boolean signInDue()
	{
		return signInDue;
	}

	/**
	 * Client thread, after clanTick has read the own clan record. Once Connect
	 * has been ticked while signed out, the first tick that is logged in with
	 * a Dream Chasers record starts the same browser approval as the player
	 * menu's Sign in with Discord. A record that isn't Dream Chasers ends the
	 * wait: the menu and the status line say why.
	 */
	void signInTick()
	{
		if (!signInDue)
		{
			return;
		}
		if (!connectOn() || isSignedIn())
		{
			signInDue = false;
			return;
		}
		OwnClan.Reading reading = ownClanReading();
		if (!loggedIn())
		{
			signInUnknownTicks = 0;
			return;     // the login screen: wait
		}
		boolean noRecord = reading.getState() == OwnClan.State.UNKNOWN || reading.getState() == OwnClan.State.LOGGED_OUT;
		if (noRecord && ++signInUnknownTicks < SIGN_IN_UNKNOWN_LIMIT)
		{
			return;     // the record hasn't loaded yet: wait, but not for ever (a player in no clan has none)
		}
		String blocker = noRecord ? OwnClan.blocker(OwnClan.Reading.UNKNOWN) : OwnClan.blocker(reading);
		if (blocker != null || reading.getRsn() == null)
		{
			signInDue = false;     // blocked: nothing to start; the menu and the status line say why
			signInBlocked(blocker != null ? blocker : OwnClan.blocker(OwnClan.Reading.UNKNOWN));
			return;
		}
		if (signInFlowRunning())
		{
			return;
		}
		signInDue = false;
		verifyStart();
	}

	/** Ticking Connect couldn't sign in: the status line says why (the menu already does). */
	void signInBlocked(String blocker)
	{
		DreamChasersPanel p = panel;
		if (p != null)
		{
			p.showSignInBlocked(blocker);
		}
	}

	/** Client thread or any; overridden in tests. */
	boolean connectOn()
	{
		return config.connect();
	}

	boolean isSignedIn()
	{
		return signedInName() != null;
	}

	OwnClan.Reading ownClanReading()
	{
		return ownClan;
	}

	/** A Sign in with Discord or a typed code is already under way. */
	boolean signInFlowRunning()
	{
		VerifyFlow v = verifyFlow;
		JoinFlow j = joinFlow;
		return (v != null && v.isWaiting()) || (j != null && j.isBusy());
	}

	/** The Verify fold needs the logged-in name; the game gives it a tick or so after LOGGED_IN. Client thread. */
	void verifiedNeeded()
	{
		verifiedDue = true;
	}

	/** Each tick until the game has the player's name, then the Verify fold is checked once. Client thread. */
	void verifiedTick()
	{
		if (!verifiedDue || !loggedIn())
		{
			return;
		}
		String name = localName();
		if (name != null)
		{
			verifiedDue = false;
			applyVerified(name);
		}
	}

	/** Client thread. */
	boolean loggedIn()
	{
		return client.getGameState() == GameState.LOGGED_IN;
	}

	/** The logged-in player's name, or null while the game hasn't sent it. Client thread. */
	String localName()
	{
		Player me = client.getLocalPlayer();
		return me == null ? null : me.getName();
	}

	// ---- Verified join: the player's own clan record ----

	/** The clan channel changed (joined, left, or loaded after login): read the own record again. */
	@Subscribe
	public void onClanChannelChanged(ClanChannelChanged event)
	{
		if (event.getClanId() == ClanID.CLAN)
		{
			clanCheckDue = true;
			// the channel also goes at a logout or a hop: logged out, the record waits for the next login
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				readOwnClan();
			}
		}
	}

	/** After a login the clan record can take a few ticks to arrive: one null check a tick until it has. */
	private void clanTick()
	{
		if (clanCheckDue && client.getGameState() == GameState.LOGGED_IN)
		{
			readOwnClan();
		}
	}

	/**
	 * Reads only the local player's own clan record: the clan's name and
	 * {@code findMember(<own name>)}. Never the member list, never another
	 * player. Only with Connect on; nothing is sent from here.
	 */
	private void readOwnClan()
	{
		if (!config.connect())
		{
			return;     // stays due: read when Connect is turned on
		}
		ClanSettings settings = client.getClanSettings(ClanID.CLAN);
		Player me = client.getLocalPlayer();
		String rsn = me == null ? null : me.getName();
		boolean found = settings != null && rsn != null && settings.findMember(rsn) != null;
		OwnClan.Reading reading = OwnClan.judge(settings != null, settings == null ? null : settings.getName(),
			rsn, found, client.getAccountHash());
		if (settings != null && reading.getState() != OwnClan.State.UNKNOWN)
		{
			clanCheckDue = false;
		}
		setOwnClan(reading);
		// the own record's rank title too: only officers and up see (and use) the roster sync
		officer = settings != null && reading.getState() == OwnClan.State.IN_CLAN
			&& RosterSync.isOfficer(RosterSync.ownRank(settings, rsn));
		updateRosterStatus();
	}

	private void setOwnClan(OwnClan.Reading reading)
	{
		if (reading.equals(ownClan))
		{
			return;
		}
		ownClan = reading;
		DreamChasersPanel panel = this.panel;
		if (panel != null)
		{
			panel.setOwnClan(OwnClan.blocker(reading), reading.getRsn());
		}
	}

	/**
	 * The salted SHA-256 of this account's hash, sent with feed posts so the
	 * site can tell the post comes from the account that verified; null before
	 * login. The raw hash is never sent. Client thread.
	 */
	private String accountDigest()
	{
		long hash = client.getAccountHash();
		if (hash != digestedHash)
		{
			digestedHash = hash;
			digestOfHash = OwnClan.digest(hash);
		}
		return digestOfHash;
	}

	/**
	 * Old PBs: the ones core Chat Commands keeps (RS-profile group "personalbest",
	 * read only), in one batch, at most once a day per account and only when they
	 * changed. Budget: 1 POST a day per account at most, usually none. Only a
	 * network failure, a 5xx or a sign-in problem leaves the day open, and then
	 * the batch waits for the next real login or sign-in (and the client's back-off).
	 */
	void backfillTick()
	{
		if (!backfillDue || !backfillOn())
		{
			return;
		}
		if (ticksSinceLogin < ACCOUNT_TYPE_SETTLE_TICKS)
		{
			return;     // the account-type varbit isn't in yet (the batch would go without acct): try next tick
		}
		if (sendBackfill(accountType()))
		{
			backfillDue = false;
		}
	}

	/** Connect and Post PBs are on and the member is signed in. Overridden in tests. */
	boolean backfillOn()
	{
		return config.connect() && config.postPbs() && signedInName() != null;
	}

	/**
	 * Sends the old-PB batch if it is due today, with the player's own account
	 * type. False when the game or the site client isn't ready yet (try again
	 * next tick); true once it is settled for this login, sent or not.
	 * Overridden in tests. Client thread.
	 */
	boolean sendBackfill(Integer acct)
	{
		Player me = client.getLocalPlayer();
		String rsn = me == null ? null : me.getName();
		String profile = configManager.getRSProfileKey();
		DrmApiClient api = this.api;
		if (rsn == null || profile == null || api == null)
		{
			return false;     // try again next tick
		}
		Map<String, Double> stored = new java.util.HashMap<>();
		for (String listed : configManager.getRSProfileConfigurationKeys(PbBackfill.GROUP, profile, ""))
		{
			String key = PbBackfill.keyName(listed);
			Double v = configManager.getRSProfileConfiguration(PbBackfill.GROUP, key, Double.class);
			if (v != null)
			{
				stored.put(key, v);
			}
		}
		if (stored.isEmpty())
		{
			return true;
		}
		String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
		String hash = PbBackfill.hash(stored);
		if (!PbBackfill.due(config.pbBackfillSent(), today, rsn, hash))
		{
			return true;
		}
		JsonObject body = Uploads.withAcct(Uploads.batch(PbBackfill.records(stored), rsn, accountDigest()), acct);
		int tokenGen = signInGen;
		api.postAuthed("/plugin/pbs/batch", body, BatchResponse.class, result ->
		{
			if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
			{
				forgetToken(tokenGen);
			}
			if (result.getStatus() != ApiResult.Status.OK)
			{
				log.debug("Old PBs not taken: {}", result.getMessage());
			}
			if (PbBackfill.settled(result.getCode()))
			{
				// the site answered for good (taken or refused): not again today
				configManager.setConfiguration(DreamChasersClanConfig.GROUP, DreamChasersClanConfig.PB_BACKFILL_KEY,
					PbBackfill.stamp(today, rsn, hash));
			}
		});
		return true;
	}

	/** The "Collection log" popup, for members who have it instead of (or with) the chat line. */
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() == ScriptID.NOTIFICATION_START)
		{
			popupStarted = true;
		}
		else if (event.getScriptId() == ScriptID.NOTIFICATION_DELAY)
		{
			boolean started = popupStarted;
			popupStarted = false;
			if (!started || !clogOn())
			{
				return;
			}
			String item = ClogTracker.popupItem(client.getVarcStrValue(VarClientID.NOTIFICATION_TITLE),
				client.getVarcStrValue(VarClientID.NOTIFICATION_MAIN));
			if (item != null)
			{
				clogTracker.onClog(item, client.getTickCount());
			}
		}
	}

	/** NPC drops from core (always on, the Loot Tracker isn't needed for these). */
	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (event.getNpc() != null)
		{
			lootSeen(event.getNpc().getName(), event.getItems());
		}
	}

	/** NPC drops the server reports (core, always on). */
	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		if (event.getComposition() != null)
		{
			lootSeen(event.getComposition().getName(), event.getItems());
		}
	}

	/** Sailing's Barracuda Trials: one record per finished run, read from the trial's varbits (see TrialTimer). */
	private void trialTick()
	{
		if (!config.connect() || !config.postPbs() || signedInName() == null)
		{
			trialTimer.reset();
			return;
		}
		PbRecord record = trialTimer.onTick(new TrialTimer.State(client.getTickCount(),
			client.getVarbitValue(VarbitID.SAILING_BT_IN_TRIAL),
			client.getVarpValue(VarPlayerID.SAILING_BT_TIME_START),
			new int[]{
				client.getVarpValue(VarPlayerID.SAILING_BT_TRIAL_TEMPOR_TANTRUM_COMPLETED),
				client.getVarpValue(VarPlayerID.SAILING_BT_TRIAL_JUBBLY_JIVE_COMPLETED),
				client.getVarpValue(VarPlayerID.SAILING_BT_TRIAL_GWENITH_GLIDE_COMPLETED)}));
		if (record != null)
		{
			Player me = client.getLocalPlayer();
			postPb(record, me == null ? null : me.getName());
		}
	}

	/**
	 * Raid stats go only to a member who is on the stream: Connect, the setting
	 * itself and Join DRM Live on, the stream consent given, and a slot taken.
	 * Otherwise nothing is sent at all.
	 */
	static boolean raidShareAllowed(boolean connect, boolean share, boolean joinLive, boolean consented, boolean onStream)
	{
		return connect && share && joinLive && consented && onStream;
	}

	private void raidTick()
	{
		RaidStats rs = raidStats;
		DrmApiClient api = this.api;
		if (rs == null || api == null || !raidShareAllowed(config.connect(), config.shareRaidStats(), config.joinLive(),
			liveConsented, liveOnStream) || signedInName() == null)
		{
			return;
		}
		Player me = client.getLocalPlayer();
		LocalPoint lp = me == null ? null : me.getLocalLocation();
		if (lp == null)
		{
			return;
		}
		String raid = RaidStats.raidOf(client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON),
			client.getVarbitValue(VarbitID.TOB_CLIENT_PARTYSTATUS), WorldPoint.fromLocalInstance(client, lp).getRegionID());
		if (raid == null)
		{
			if (inRaid)
			{
				rs.reset();
				inRaid = false;
			}
			return;
		}
		inRaid = true;
		int hp = client.getBoostedSkillLevel(Skill.HITPOINTS), maxHp = client.getRealSkillLevel(Skill.HITPOINTS);
		int prayer = client.getBoostedSkillLevel(Skill.PRAYER), maxPrayer = client.getRealSkillLevel(Skill.PRAYER);
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		Item[] items = inv == null ? new Item[0] : inv.getItems();
		StringBuilder key = new StringBuilder(raid).append('|').append(hp).append('/').append(maxHp)
			.append('|').append(prayer).append('/').append(maxPrayer);
		for (Item it : items)
		{
			key.append('|').append(it.getId()).append('x').append(it.getQuantity());
		}
		Instant now = Clock.systemUTC().instant();
		if (!rs.due(now, key.toString()))
		{
			return;
		}
		List<RaidStats.Item> list = new ArrayList<>();
		for (Item it : items)
		{
			if (it.getId() > 0 && it.getQuantity() > 0)
			{
				list.add(new RaidStats.Item(it.getId(), it.getQuantity(), itemManager.getItemComposition(it.getId()).getName()));
			}
		}
		rs.sending(now, key.toString());
		int tokenGen = signInGen;
		api.postAuthed("/plugin/raid", RaidStats.body(raid, hp, maxHp, prayer, maxPrayer, list), RaidResponse.class, result ->
		{
			if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
			{
				forgetToken(tokenGen);
			}
			boolean ok = result.getStatus() == ApiResult.Status.OK && result.getValue() != null;
			if (ok)
			{
				// not on a slot (the show ended, or a producer took the member off): stop after a few answers
				raidNotShown = result.getValue().isShown() ? 0 : raidNotShown + 1;
				if (raidNotShown >= RAID_NOT_SHOWN_LIMIT)
				{
					liveOnStream = false;
				}
			}
			// no consent, rate limited or the site unreachable: back off two minutes
			rs.answered(now, ok ? result.getValue().getNext() : 120);
		});
	}

	// ---- officer roster sync ----

	/**
	 * An officer closed the clan Members screen (where ranks are changed): the
	 * list is read two ticks later, so a rank picked just before the close is
	 * in it. Not while the interface tree is torn down (logout, world hop).
	 * Client thread, as every event.
	 */
	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.CLANS_MEMBERS && !event.isUnload()
			&& client.getGameState() == GameState.LOGGED_IN && roster != null)
		{
			rosterDueTick = client.getTickCount() + ROSTER_READ_DELAY_TICKS;
		}
	}

	/** The read after a close, and the one send a wait inside the minute leaves due. Client thread. */
	private void rosterTick()
	{
		RosterSync sync = roster;
		if (sync == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (rosterDueTick >= 0 && client.getTickCount() >= rosterDueTick)
		{
			rosterDueTick = -1;
			rosterPending = false;     // this read takes the waiting change along
			sendRoster(false);
			return;
		}
		Instant next = rosterPending ? sync.nextAllowed() : null;
		if (rosterPending && (next == null || !Clock.systemUTC().instant().isBefore(next)))
		{
			rosterPending = false;
			sendRoster(true);
		}
	}

	/**
	 * Reads the clan's member list (names and rank titles, nothing else) on the
	 * client thread and sends it on OkHttp's. Only with Connect and the setting
	 * on, signed in, in Dream Chasers, at an officer rank; at most once a
	 * minute (a change inside the minute goes by itself once it is up), and
	 * only when the list differs from the one the site last took.
	 * {@code waited} is the send a wait left due: if it ends with nothing to
	 * send, the line goes back to the site's last answer.
	 */
	private void sendRoster(boolean waited)
	{
		RosterSync sync = roster;
		DrmApiClient api = this.api;
		if (waited)
		{
			rosterLine = rosterAnswer;
		}
		if (sync == null || api == null || !config.connect() || !config.officerRosterSync() || signedInName() == null)
		{
			updateRosterStatus();
			return;
		}
		ClanSettings settings = client.getClanSettings(ClanID.CLAN);
		Player me = client.getLocalPlayer();
		String own = me == null ? null : me.getName();
		if (settings == null || own == null)
		{
			updateRosterStatus();
			return;
		}
		String ownRank = RosterSync.ownRank(settings, own);
		officer = OwnClan.isDreamChasers(settings.getName()) && RosterSync.isOfficer(ownRank);
		if (!RosterSync.allowed(config.connect(), config.officerRosterSync(), true, settings.getName(), ownRank))
		{
			updateRosterStatus();
			return;
		}
		List<RosterSync.Member> members = RosterSync.read(settings);
		if (members.isEmpty() || members.size() > RosterSync.MAX_MEMBERS)
		{
			log.debug("Roster not sent: {} members", members.size());
			rosterLine = RosterSync.notReadLine(members.size());
			updateRosterStatus();
			return;
		}
		String hash = RosterSync.hash(members);
		RosterSync.Decision decision = sync.check(Clock.systemUTC().instant(), hash);
		if (decision == RosterSync.Decision.TOO_SOON)
		{
			Instant next = sync.nextAllowed();
			rosterPending = true;
			rosterLine = RosterSync.waitingLine(LocalTime.ofInstant(next, ZoneId.systemDefault()),
				sync.changedSinceLastSend(hash));
			updateRosterStatus();
			return;
		}
		if (decision != RosterSync.Decision.SEND)
		{
			updateRosterStatus();
			return;
		}
		int tokenGen = signInGen;
		api.postAuthed(RosterSync.PATH, RosterSync.body(settings.getName(), Text.toJagexName(own), members), RosterResponse.class, result ->
		{
			RosterSync.Outcome out = sync.answered(result, hash, LocalTime.now(ZoneId.systemDefault()));
			if (out.isForgetToken())
			{
				forgetToken(tokenGen);
			}
			if (result.getStatus() != ApiResult.Status.OK)
			{
				log.debug("Roster not taken: {}", result.getMessage());
			}
			rosterAnswer = out.getLine();
			if (!rosterPending)     // a newer change waiting keeps its line until it goes
			{
				rosterLine = out.getLine();
			}
			if (out.isRetry())
			{
				rosterPending = true;     // the same list goes once more when the minute is up
			}
			updateRosterStatus();
		});
	}

	/** The panel's roster line: only for an officer, signed in, with Connect and the setting on. */
	private void updateRosterStatus()
	{
		DreamChasersPanel panel = this.panel;
		if (panel == null)
		{
			return;
		}
		boolean show = officer && config.connect() && config.officerRosterSync() && signedInName() != null;
		String line = rosterLine;
		if (show && line != null)
		{
			log.debug("Roster sync: {}", line);     // the details; the menu says only on or off
		}
		RosterSync rs = roster;
		boolean stopped = rs != null && rs.isStopped();
		panel.setRosterSync(show ? !stopped : null);
	}

	// ---- collection log and big drops ----

	/** True while collection log posting may run: its own toggle, plus Connect and a sign-in. */
	private boolean clogOn()
	{
		return config.connect() && config.postLoot() && signedInName() != null;
	}

	/**
	 * The Loot Tracker's drops: chests, raids, clues and events that core's NPC
	 * events don't cover. Its NPC drops repeat core's and are counted once.
	 */
	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		// own loot only; a PvP kill would name another player as the source
		if (event.getType() != LootRecordType.PLAYER)
		{
			lootSeen(event.getName(), event.getItems());
		}
	}

	private void lootSeen(String source, java.util.Collection<ItemStack> items)
	{
		if (!clogOn() || items == null || source == null)
		{
			return;
		}
		List<LootPicker.Stack> drop = new ArrayList<>();
		for (ItemStack is : items)
		{
			int id = itemManager.canonicalize(is.getId());
			ItemComposition item = itemManager.getItemComposition(id);
			// RuneLite's own (Wiki) price: no price calls of our own
			drop.add(new LootPicker.Stack(id, item.getName(), is.getQuantity(), itemManager.getItemPriceWithSource(id, true)));
		}
		clogTracker.onLoot(source, drop, client.getTickCount());
	}

	private void clogTick()
	{
		// the hint: new-item notifications off in the game's settings means no slot can be seen
		boolean hint = clogOn() && client.getGameState() == GameState.LOGGED_IN
			&& client.getVarbitValue(VarbitID.OPTION_COLLECTION_NEW_ITEM) == 0;
		DreamChasersPanel p = panel;
		if (p != null && !Boolean.valueOf(hint).equals(clogHintShown))
		{
			clogHintShown = hint;
			p.setClogHint(hint);
		}
		if (!clogOn())
		{
			clogTracker.reset();
			return;
		}
		ClogTracker.Released out = clogTracker.onTick(client.getTickCount());
		if (out.isEmpty())
		{
			return;
		}
		Player me = client.getLocalPlayer();
		String rsn = me == null ? null : me.getName();
		if (rsn == null)
		{
			return;
		}
		Instant at = Clock.systemUTC().instant();
		for (ClogTracker.Clog c : out.getClogs())
		{
			postLoot("clog " + c.getName(), Uploads.withAcct(Uploads.clog(c, rsn, at, accountDigest()), accountType()));
		}
		long threshold = LootPicker.clanThreshold(clanThreshold);
		for (ClogTracker.Drop d : out.getDrops())
		{
			postDrop(d.getSource(), LootPicker.pick(d.getStacks(), threshold), rsn, at);
		}
	}

	private void postDrop(String source, List<LootPicker.Picked> items, String rsn, Instant at)
	{
		if (!items.isEmpty())
		{
			postLoot(source, Uploads.withAcct(Uploads.drop(source, items, rsn, at, accountDigest()), accountType()));
		}
	}

	private void postLoot(String what, JsonObject body)
	{
		DrmApiClient api = this.api;
		if (api == null)
		{
			return;
		}
		int tokenGen = signInGen;
		api.postAuthed("/plugin/loots", body, SubmitResponse.class, result ->
		{
			if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
			{
				forgetToken(tokenGen);
			}
			if (result.getStatus() != ApiResult.Status.OK)
			{
				log.debug("{} not taken: {}", what, result.getMessage());
			}
		});
	}

	// ---- Blorva and Radiant oathplate ----

	/**
	 * The player's own inventory: an Ancient blood ornament kit (Blorva) or an
	 * assembled Purifying sigil (Radiant oathplate) arriving goes to the site
	 * as an unlock, behind Post collection log like the other listed firsts
	 * (those come in as collection log slots; these two aren't any). See
	 * {@link UnlockWatch} for what counts as arriving.
	 */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.INV)
		{
			return;
		}
		if (!clogOn())
		{
			unlockWatch.reset();
			return;
		}
		ItemContainer inv = event.getItemContainer();
		Item[] items = inv == null ? new Item[0] : inv.getItems();
		int[] ids = new int[items.length];
		int[] qtys = new int[items.length];
		for (int i = 0; i < items.length; i++)
		{
			ids[i] = items[i].getId();
			qtys[i] = items[i].getQuantity();
		}
		boolean settled = loggedIn() && ticksSinceLogin >= ACCOUNT_TYPE_SETTLE_TICKS;
		List<UnlockWatch.Unlock> got = unlockWatch.onInventory(ids, qtys, settled, storageOpen());
		if (got.isEmpty() || !mainGameWorld())
		{
			return;
		}
		String rsn = localName();
		if (rsn == null)
		{
			return;
		}
		Instant at = Clock.systemUTC().instant();
		for (UnlockWatch.Unlock u : got)
		{
			postUnlock(u, Uploads.withAcct(Uploads.unlock(u.getKey(), u.getItem(), u.getId(), rsn, at, accountDigest()), accountType()));
		}
	}

	/** Sends one unlock; the same route as the collection log. Overridden in tests. */
	void postUnlock(UnlockWatch.Unlock u, JsonObject body)
	{
		postLoot("unlock " + u.getKey(), body);
	}

	/** Whether the bank or the group storage is open: items coming out of it aren't new. Overridden in tests. Client thread. */
	boolean storageOpen()
	{
		return shown(client.getWidget(InterfaceID.Bankmain.UNIVERSE)) || shown(client.getWidget(InterfaceID.SharedBank.UNIVERSE));
	}

	private static boolean shown(Widget w)
	{
		return w != null && !w.isHidden();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!DreamChasersClanConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if (DreamChasersClanConfig.CONNECT_KEY.equals(event.getKey()))
		{
			connectChanged(event.getOldValue(), event.getNewValue());     // ticked while signed out: sign in on a later tick
		}
		if (panel == null)
		{
			return;
		}
		// "Enable everything": on switches the eight features on, off switches them
		// off; one turned off by hand unticks it. It only flips settings: it never
		// opens the browser. Not signed in? Ticking Connect (or the player menu) signs in.
		boolean signedIn = signedInName() != null;
		if (DreamChasersClanConfig.ENABLE_ALL_KEY.equals(event.getKey()) && featureOn(event.getKey()) && !signedIn)
		{
			panel.setEnableAllRefused(true);     // it unticks itself below: the panel's status line says why
		}
		EnableAll.onChanged(event.getKey(), this::featureOn, this::setFeature, signedIn);
		if (EnableAll.isFeatureKey(event.getKey()))
		{
			panel.setAllOn(EnableAll.allOn(this::featureOn));
		}
		switch (event.getKey())
		{
			case DreamChasersClanConfig.CONNECT_KEY:
				panel.setConnected(config.connect());
				clanCheckDue = true;
				updateRosterStatus();
				if (!config.connect() && (joinFlow != null || verifyFlow != null))
				{
					if (joinFlow != null)
					{
						joinFlow.cancel();
					}
					if (verifyFlow != null)
					{
						verifyFlow.cancel();
					}
					panel.verifyDone(null, false);
				}
				refresh(false);
				break;
			case DreamChasersClanConfig.LIVE_KEY:
				panel.setLiveEnabled(config.joinLive());
				refreshLive(true);
				break;
			case DreamChasersClanConfig.SPLASH_KEY:
				panel.setSplashEnabled(config.clanSplash());
				break;
			case DreamChasersClanConfig.ROSTER_KEY:
				if (config.officerRosterSync() && roster != null)
				{
					roster.resume();     // a fresh tick tries again after a refusal
					rosterLine = null;
					rosterAnswer = null;
				}
				clanCheckDue = true;     // re-reads the own rank on the next tick
				updateRosterStatus();
				break;
			case DreamChasersClanConfig.RAID_KEY:
				if (raidStats != null)
				{
					raidStats.reset();
				}
				break;
			case DreamChasersClanConfig.TILES_KEY:
				panel.setTilesEnabled(config.tilePacks());
				refreshTiles(false);
				break;
			case DreamChasersClanConfig.SPLASH_OPTED_IN_KEY:
				panel.setSplashOptedIn(config.splashOptedIn());
				break;
			case "showSidebar":
				if (config.showSidebar())
				{
					clientToolbar.addNavigation(navButton);
				}
				else
				{
					clientToolbar.removeNavigation(navButton);
				}
				break;
			default:
				break;
		}
	}

	// ---- Enable everything (a setting) ----

	private boolean featureOn(String key)
	{
		return Boolean.TRUE.equals(configManager.getConfiguration(DreamChasersClanConfig.GROUP, key, Boolean.class));
	}

	private void setFeature(String key, boolean on)
	{
		configManager.setConfiguration(DreamChasersClanConfig.GROUP, key, on);
	}

	/** The panel's "Enable everything" line (signed in only): ticks the same setting. */
	@Override
	public void enableAll()
	{
		if (signedInName() != null)
		{
			setFeature(DreamChasersClanConfig.ENABLE_ALL_KEY, true);
		}
	}

	// ---- DreamChasersPanel.Listener ----

	@Override
	public void refresh(boolean force)
	{
		DrmApiClient api = this.api;
		DreamChasersPanel panel = this.panel;
		if (api == null || panel == null || !config.connect())
		{
			return;
		}
		api.fetch(Endpoint.COMPETITION, force, result ->
		{
			panel.showCompetition(result);
			if (liveKcOn())
			{
				liveKc.checked(result.getValue());     // a kill kept unchecked goes once this lists its boss
				liveXp.heard(result.getValue());     // and the XP timer learns the competition's skill
			}
			competitionLanded(result);     // and a logout notice held for this answer
		});
		api.fetch(Endpoint.HALL_OF_FAME, force, result ->
		{
			onHallOfFame(result);
			panel.showHallOfFame(result);
		});
		api.fetch(Endpoint.LINKS, force, panel::showLinks);
		if (signedInName() != null)
		{
			int gen = signInGen;
			api.fetch(Endpoint.ME, force, result -> onMeAnswer(gen, result, wasUnverified -> fetchEvents(api, panel, force || wasUnverified)));
		}
		refreshLive(force);
		refreshSplash();
		refreshTiles(force);
	}

	private void fetchEvents(DrmApiClient api, DreamChasersPanel panel, boolean force)
	{
		int tokenGen = signInGen;
		api.fetch(Endpoint.EVENTS, force, result ->
		{
			if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
			{
				// The site no longer knows this token (revoked or expired). A 403 keeps it.
				forgetToken(tokenGen);
			}
			eventsAnswered(tokenGen, result);
			panel.showEvents(result);
		});
	}

	/**
	 * A 403 marked unverified: the site doesn't see this sign-in as verified
	 * though nothing here knew (a {@code /plugin/me} that failed at startup,
	 * say). Events stop asking until {@code /plugin/me} says it is, and that
	 * answer loads them at once. Package-private for tests.
	 */
	void eventsAnswered(int tokenGen, ApiResult<?> result)
	{
		if (tokenGen == signInGen && result.getStatus() == ApiResult.Status.FORBIDDEN && result.isUnverified())
		{
			signInUnverified = true;
		}
	}

	/**
	 * The answer to a {@code /plugin/me} asked while {@code gen} was the
	 * sign-in count. One for an earlier sign-in (a Verify handed over a new
	 * token while it was on its way) is dropped, so an old verified token's
	 * answer never marks a new held one verified. {@code loadEvents} gets
	 * whether the sign-in was waiting until this answer. Package-private for tests.
	 */
	void onMeAnswer(int gen, ApiResult<MeResponse> result, Consumer<Boolean> loadEvents)
	{
		if (gen != signInGen)
		{
			return;
		}
		boolean wasUnverified = signInUnverified;
		// Events only for a verified sign-in; a 401 still asks, so the panel hears why and the token goes
		if (onMe(result) || result.getStatus() == ApiResult.Status.UNAUTHORIZED)
		{
			loadEvents.accept(wasUnverified);
		}
	}

	/**
	 * A {@code /plugin/me} answer: the names verified to this member (for the
	 * Verify fold), and whether the sign-in itself is verified yet. Asked at
	 * the panel's usual cadence, so a held member's sign-in turns verified by
	 * itself once an officer approves (no second Connect). Returns whether
	 * Events may load.
	 */
	boolean onMe(ApiResult<MeResponse> result)
	{
		MeResponse me = result.getValue();
		if (result.getStatus() != ApiResult.Status.OK || me == null)
		{
			return !signInUnverified;     // a cached or failed answer changes nothing
		}
		boolean was = signInUnverified;
		String before = waitedOn;
		signInUnverified = me.notVerifiedYet();
		String waitingOn = me.waitingOn();
		if (waitingOn != null && !waitingOn.isEmpty())
		{
			waitedOn = waitingOn;
		}
		if (signInUnverified && waitingOn == null)
		{
			// nothing is with the officers (cleared, or the name went to someone else): say to ask
			// again, in the plugin's own words: the site's say "Press Connect", which is a setting here
			showNotVerified(NOT_VERIFIED);
		}
		else
		{
			showWaiting(waitingOn);
		}
		boolean beforeVerified = isListed(before, me.getVerified());
		if (!signInUnverified && beforeVerified)
		{
			// the name waited on is verified now (this answer, or a later one for a member already verified)
			waitedOn = null;
			showNowVerified(before);
		}
		else if (was && !signInUnverified)
		{
			// verified, but not as the name waited on: a verified member's held alt keeps Events and
			// stays with the officers, so the line names what is verified and the alt is still waited on
			showNowVerified(nowVerifiedName(me.getVerified()));
		}
		if (me.getVerified() != null)
		{
			verifiedNames = withJustVerified(me.getVerified());
			updateVerified();
		}
		return !signInUnverified;
	}

	/** Whether {@code name} is one of {@code names}, as game names compare. Static for tests. */
	static boolean isListed(String name, List<String> names)
	{
		String key = rsnKey(name);
		return key != null && names != null && names.stream().anyMatch(n -> key.equals(rsnKey(n)));
	}

	/**
	 * The game name for the "Verified as" line when none was waited on: the
	 * one name verified to this member, else null (the plain line). Never
	 * {@code /me}'s name, which is the member's Discord name on a sign-in
	 * that started held. Static for tests.
	 */
	static String nowVerifiedName(List<String> verified)
	{
		if (verified == null || verified.size() != 1 || verified.get(0) == null || verified.get(0).trim().isEmpty())
		{
			return null;
		}
		return verified.get(0).trim();
	}

	/** The name a sign-in that isn't verified yet waits on ("" when unnamed, null when it is verified); overridden in tests. */
	void showWaiting(String name)
	{
		DreamChasersPanel panel = this.panel;
		if (panel != null)
		{
			panel.setWaitingFor(name);
		}
	}

	/** Said when {@code /plugin/me} has no line of its own for a sign-in nothing is waiting on. */
	static final String NOT_VERIFIED = "This sign-in isn't verified to a game name. "
		+ "Press Verify for Discord in the menu at the top right to ask again.";

	/** The sign-in isn't verified and no request is waiting: the site's line saying to ask again; overridden in tests. */
	void showNotVerified(String message)
	{
		DreamChasersPanel panel = this.panel;
		if (panel != null)
		{
			panel.setNotVerified(message);
		}
	}

	/** The hold cleared while signed in: say so, through the fold to verified that follows; overridden in tests. */
	void showNowVerified(String name)
	{
		DreamChasersPanel panel = this.panel;
		if (panel != null)
		{
			panel.verifiedDone(name == null || name.isEmpty() ? "Verified. Events show now." : "Verified as " + name + ". Events show now.");
		}
	}

	/** With the panel shut and collection log posting on: only the Hall of Fame (usually from the cache), for the big-drop threshold. */
	private void refreshThreshold()
	{
		DrmApiClient api = this.api;
		if (api != null && clogOn())
		{
			api.fetch(Endpoint.HALL_OF_FAME, false, this::onHallOfFame);
		}
	}

	private void onHallOfFame(ApiResult<HallOfFameResponse> result)
	{
		HallOfFameResponse hof = result.getValue();
		if (hof != null && hof.getLoots() != null && hof.getLoots().getThreshold() != null)
		{
			clanThreshold = hof.getLoots().getThreshold();
		}
	}

	/** Clan tile packs: only while the panel is open and the setting is on; kept an hour. */
	private void refreshTiles(boolean force)
	{
		DrmApiClient api = this.api;
		DreamChasersPanel panel = this.panel;
		if (api == null || panel == null || !config.connect() || !config.tilePacks() || !panel.isShowing())
		{
			return;
		}
		api.fetch(Endpoint.TILES, force, panel::showTiles);
	}

	/**
	 * The featured splash and the member's own entry. Never forced, and
	 * only while the panel is open: both are kept an hour, so this is at most
	 * one call each an hour.
	 */
	private void refreshSplash()
	{
		DrmApiClient api = this.api;
		DreamChasersPanel panel = this.panel;
		if (api == null || panel == null || !config.connect() || !panel.isShowing())
		{
			return;
		}
		api.fetch(Endpoint.FEATURED, false, result ->
		{
			panel.showFeatured(result);
			FeaturedResponse f = result.getValue();
			String url = f == null ? null : f.getPanelUrl();
			Instant now = Clock.systemUTC().instant();
			// A new picture, or a retry of a failed one at most hourly. Keep the last good copy meanwhile.
			if (url == null || (url.equals(splashImageUrl) && (splashImageOk || now.isBefore(splashImageTriedAt.plus(SPLASH_IMAGE_RETRY)))))
			{
				return;
			}
			splashImageUrl = url;
			splashImageTriedAt = now;
			splashImageOk = false;
			String rsn = f.getRsn();
			api.fetchImage(url, image ->
			{
				if (image != null && url.equals(splashImageUrl))
				{
					splashImageOk = true;
					panel.showFeaturedImage(rsn, image);
				}
			});
		});
		if (signedInName() != null && config.splashOptedIn())
		{
			int tokenGen = signInGen;
			api.fetch(Endpoint.MODEL, false, result ->
			{
				if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
				{
					forgetToken(tokenGen);
				}
				if (result.getStatus() == ApiResult.Status.OK && result.getValue() != null && !result.getValue().isOptedIn())
				{
					// Removed at the site (or by an officer): match it here.
					configManager.unsetConfiguration(DreamChasersClanConfig.GROUP, DreamChasersClanConfig.SPLASH_OPTED_IN_KEY);
				}
				panel.showModelStatus(result);
			});
		}
	}

	private void refreshLive(boolean force)
	{
		DrmApiClient api = this.api;
		DreamChasersPanel panel = this.panel;
		if (api == null || panel == null || !config.connect() || !config.joinLive() || signedInName() == null)
		{
			return;
		}
		int tokenGen = signInGen;
		api.fetch(Endpoint.LIVE, force, result ->
		{
			if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
			{
				forgetToken(tokenGen);
			}
			else if (result.getValue() != null)
			{
				liveConsented = result.getValue().isConsented();
				LiveResponse.Status st = result.getValue().getStatus();
				if (!liveConsented || (st != null && "off".equals(st.getShow())))
				{
					liveOnStream = false;
				}
			}
			panel.showLive(result);
		});
	}

	/**
	 * Stores a new token and loads what it unlocks: everything, or all but
	 * Events while it isn't verified yet. {@code waitingOn} is null for a
	 * verified sign-in, else the game name the officers have to check (not
	 * the token's name: the site names an unverified sign-in from Discord).
	 */
	private void signedIn(String token, String name, String waitingOn)
	{
		boolean verified = waitingOn == null;
		beginSignIn(verified);
		waitedOn = verified || waitingOn.isEmpty() ? null : waitingOn;
		configManager.setConfiguration(DreamChasersClanConfig.GROUP, DreamChasersClanConfig.TOKEN_KEY, token);
		configManager.setConfiguration(DreamChasersClanConfig.GROUP, DreamChasersClanConfig.SIGNED_IN_AS_KEY,
			name == null || name.isEmpty() ? "clan member" : name);
		DreamChasersPanel panel = this.panel;
		DrmApiClient api = this.api;
		if (panel == null || api == null)
		{
			return;
		}
		panel.setSignedInAs(signedInName());
		panel.setWaitingFor(waitingOn);
		api.invalidate(Endpoint.ME);     // an earlier sign-in's answer says nothing about this one
		updateRosterStatus();
		backfillDue = true;
		if (verified)
		{
			fetchEvents(api, panel, true);
		}
		refreshLive(true);
	}

	// ---- Verify for Discord (verified join) ----

	/** Shown after the code once Sign in with Discord has waited 2 minutes (VerifyFlow.STILL_WAITING_AFTER). */
	public static final String VERIFY_STILL_WAITING = "Still waiting? Check your browser is signed in to discord.com "
		+ "with your main Discord account (it needs a verified email or phone). Then press Cancel in the menu at the top right "
		+ "and try again from there.";

	/**
	 * The player menu's Sign in with Discord (or Verify NAME for Discord), and
	 * the tick after Connect is ticked: the one-click verify (VerifyFlow),
	 * which signs in too. The typed code is the menu's "Enter a code from the site…".
	 */
	@Override
	public void verifyStart()
	{
		VerifyFlow flow = verifyFlow;
		DreamChasersPanel panel = this.panel;
		if (flow == null || panel == null)
		{
			return;
		}
		if (!config.connect())
		{
			panel.verifyDone("Turn on Connect to dream-chasers.cc in the plugin settings first", true);
			return;
		}
		if (joinFlow != null)
		{
			joinFlow.cancel();
		}
		flow.start(new VerifyFlow.Listener()
		{
			@Override
			public void waiting(String code)
			{
				panel.setVerifyWaiting(code == null ? "…" : code, null);
			}

			@Override
			public void stillWaiting(String code)
			{
				panel.setVerifyWaiting(code == null ? "…" : code, VERIFY_STILL_WAITING);
			}

			@Override
			public void verified(String token, String name, String message)
			{
				panel.verifiedDone(message);     // stays through the fold to verified that follows
				signedIn(token, name, null);
				addVerified(name);
			}

			@Override
			public void unverified(String token, String name, String rsn, String message)
			{
				panel.verifyDone(message, false);
				signedIn(token, name, rsn == null ? "" : rsn);
			}

			@Override
			public void finished(String message, boolean isError)
			{
				panel.verifyDone(message, isError);
			}
		});
	}

	@Override
	public void verifyStop()
	{
		VerifyFlow flow = verifyFlow;
		if (flow != null)
		{
			flow.cancel();
		}
	}

	@Override
	public void verifyPeek(String code)
	{
		JoinFlow flow = joinFlow;
		DreamChasersPanel panel = this.panel;
		if (flow == null || panel == null)
		{
			return;
		}
		if (!config.connect())
		{
			panel.verifyDone("Turn on Connect to dream-chasers.cc in the plugin settings first", true);
			return;
		}
		if (verifyFlow != null)
		{
			verifyFlow.cancel();
		}
		flow.peek(code, new JoinFlow.Listener()
		{
			@Override
			public void confirm(String rsn, String discordName)
			{
				panel.verifyAsk(rsn, discordName);
			}

			@Override
			public void verified(String token, String name, String message)
			{
				panel.verifiedDone(message);     // stays through the fold to verified that follows
				signedIn(token, name, null);
				addVerified(name);
			}

			@Override
			public void unverified(String token, String name, String rsn, String message)
			{
				panel.verifyDone(message, false);
				signedIn(token, name, rsn == null ? "" : rsn);
			}

			@Override
			public void finished(String message, boolean isError)
			{
				panel.verifyDone(message, isError);
			}
		});
	}

	@Override
	public void verifyConfirm()
	{
		JoinFlow flow = joinFlow;
		if (flow != null && config.connect())
		{
			flow.confirm();
		}
	}

	@Override
	public void verifyCancel()
	{
		JoinFlow flow = joinFlow;
		if (flow != null)
		{
			flow.cancel();
		}
	}

	// ---- DRM Live ----

	@Override
	public void liveAgree(String version)
	{
		if (!config.joinLive())
		{
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("version", version);
		liveAction("/plugin/live/consent", body, false);
	}

	@Override
	public void liveJoin()
	{
		if (!config.connect() || !config.joinLive())
		{
			return;
		}
		withRsn(rsn ->
		{
			DrmApiClient api = this.api;
			DreamChasersPanel panel = this.panel;
			if (api == null || panel == null)
			{
				return;
			}
			JsonObject body = new JsonObject();
			if (rsn != null)
			{
				body.addProperty("rsn", rsn);
			}
			int tokenGen = signInGen;
			api.postAuthed("/plugin/live/join", body, LiveJoinResponse.class, result ->
			{
				if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
				{
					forgetToken(tokenGen);
				}
				if (result.getStatus() != ApiResult.Status.OK || result.getValue() == null
					|| result.getValue().getPushUrl() == null)
				{
					panel.liveDone(null, true, result.getMessage(), true);
					return;
				}
				liveOnStream = true;
				raidNotShown = 0;
				panel.liveDone(result.getValue(), false, null, false);
				refreshLive(true);
			});
		});
	}

	@Override
	public void liveLeave()
	{
		liveAction("/plugin/live/leave", null, true);
	}

	@Override
	public void liveWithdraw()
	{
		liveAction("/plugin/live/withdraw", null, true);
	}

	/** Consent, leave, withdraw: POST, then reload the section. {@code dropsSlot}: forget the join. */
	private void liveAction(String path, JsonObject body, boolean dropsSlot)
	{
		DrmApiClient api = this.api;
		DreamChasersPanel panel = this.panel;
		if (api == null || panel == null || !config.connect())
		{
			return;
		}
		int tokenGen = signInGen;
		api.postAuthed(path, body, LiveActionResponse.class, result ->
		{
			if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
			{
				forgetToken(tokenGen);
			}
			boolean ok = result.getStatus() == ApiResult.Status.OK;
			if (ok && dropsSlot)
			{
				liveOnStream = false;
			}
			String note = ok && result.getValue() != null ? result.getValue().getNote() : null;
			panel.liveDone(null, !(ok && dropsSlot), ok ? (note == null || note.isEmpty() ? null : note) : result.getMessage(), !ok);
			api.invalidate(Endpoint.LIVE);
			refreshLive(true);
		});
	}

	// ---- Clan splash ----

	@Override
	public void featureMe()
	{
		DreamChasersPanel panel = this.panel;
		if (panel == null || !config.connect())
		{
			return;
		}
		if (!config.clanSplash() || signedInName() == null)
		{
			panel.splashDone("Turn on Clan splash in the plugin settings first", true);
			return;
		}
		Instant now = Clock.systemUTC().instant();
		Instant last = lastModelUpload;
		if (last != null && now.isBefore(last.plus(MODEL_UPLOAD_INTERVAL)))
		{
			long minutes = Math.max(1, Duration.between(now, last.plus(MODEL_UPLOAD_INTERVAL)).toMinutes() + 1);
			panel.splashDone("You can send a new pose in " + minutes + " minute" + (minutes == 1 ? "" : "s") + ".", true);
			return;
		}
		clientThread.invokeLater(() ->
		{
			Player me = client.getLocalPlayer();
			if (me == null || client.getGameState() != GameState.LOGGED_IN)
			{
				panel.splashDone("Log in to the game first, then stand still and try again.", true);
				return;
			}
			String problem = ModelExport.poseProblem(me.getAnimation(), me.getPoseAnimation(), me.getIdlePoseAnimation(),
				ModelExport.holdingSomething(me.getPlayerComposition()));
			if (problem != null)
			{
				panel.splashDone(problem, true);
				return;
			}
			Model model = me.getModel();
			if (model == null)
			{
				panel.splashDone("Couldn't read your character just now. Try again.", true);
				return;
			}
			ModelExport copy = ModelExport.copyOf(model);
			String name = me.getName();
			executor.execute(() -> uploadModel(copy, name));
		});
	}

	private void uploadModel(ModelExport model, String name)
	{
		DrmApiClient api = this.api;
		DreamChasersPanel panel = this.panel;
		if (api == null || panel == null)
		{
			return;
		}
		String obj = model.toObj(name);
		if (obj.getBytes(StandardCharsets.UTF_8).length > ModelExport.MAX_BYTES)
		{
			panel.splashDone("Your character's model is bigger than the site takes. Try without a pet or a large cape.", true);
			return;
		}
		int tokenGen = signInGen;
		api.uploadModel(obj, result ->
		{
			if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
			{
				forgetToken(tokenGen);
			}
			if (result.getStatus() != ApiResult.Status.OK)
			{
				panel.splashDone(result.getMessage(), true);
				return;
			}
			lastModelUpload = Clock.systemUTC().instant();
			configManager.setConfiguration(DreamChasersClanConfig.GROUP, DreamChasersClanConfig.SPLASH_OPTED_IN_KEY, true);
			api.invalidate(Endpoint.MODEL);
			ModelStatusResponse v = result.getValue();
			if (v != null && v.getStatus() != null)
			{
				panel.showModelStatus(result);
			}
			panel.splashDone(v != null && notBlank(v.getNote()) ? v.getNote()
				: "Sent. Your splash is being made; check back in an hour.", false);
		});
	}

	@Override
	public void removeMe()
	{
		DrmApiClient api = this.api;
		DreamChasersPanel panel = this.panel;
		if (api == null || panel == null || !config.connect())
		{
			return;
		}
		int tokenGen = signInGen;
		api.deleteModel(result ->
		{
			if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
			{
				forgetToken(tokenGen);
			}
			if (result.getStatus() != ApiResult.Status.OK)
			{
				panel.splashDone(result.getMessage(), true);
				return;
			}
			configManager.unsetConfiguration(DreamChasersClanConfig.GROUP, DreamChasersClanConfig.SPLASH_OPTED_IN_KEY);
			api.invalidate(Endpoint.MODEL);
			panel.splashDone("You're off the clan splash. The site deleted your model and splash.", false);
		});
	}

	private static boolean notBlank(String s)
	{
		return s != null && !s.trim().isEmpty();
	}

	/** Runs {@code then} on the client thread with the logged-in name (null when there's none); overridden in tests. */
	void withRsn(Consumer<String> then)
	{
		clientThread.invokeLater(() ->
		{
			Player me = client.getLocalPlayer();
			then.accept(me == null ? null : me.getName());
		});
	}

	@Override
	public void collapsedChanged(String keys)
	{
		configManager.setConfiguration(DreamChasersClanConfig.GROUP, DreamChasersClanConfig.COLLAPSED_KEY, keys);
	}

	@Override
	public void signOut()
	{
		// with Connect off nothing is sent; the token is only forgotten here
		if (api != null && config.connect())
		{
			api.unlink();
		}
		forgetToken();
	}

	/** A new token: earlier {@code /plugin/me} answers stop counting. Package-private for tests. */
	void beginSignIn(boolean verified)
	{
		signInGen++;
		signInUnverified = !verified;
		waitedOn = null;
	}

	/**
	 * A 401 for a request sent under sign-in {@code gen}: forgets the token
	 * only when it is still that sign-in's. Asking again for the same name
	 * revokes the previous held token at the site, so an answer for it that
	 * lands after the new token is stored must not sign the member out.
	 */
	void forgetToken(int gen)
	{
		if (gen == signInGen)
		{
			forgetToken();
		}
	}

	/** Signs this PC out: the token and all that hangs on it go. Package-private for tests. */
	void forgetToken()
	{
		signInGen++;
		verifiedNames = Collections.emptyList();
		justVerified.clear();
		signInUnverified = false;
		waitedOn = null;
		liveConsented = false;
		liveOnStream = false;
		configManager.unsetConfiguration(DreamChasersClanConfig.GROUP, DreamChasersClanConfig.TOKEN_KEY);
		configManager.unsetConfiguration(DreamChasersClanConfig.GROUP, DreamChasersClanConfig.SIGNED_IN_AS_KEY);
		if (api != null)
		{
			api.clearMembersOnly();
		}
		if (panel != null)
		{
			panel.setSignedInAs(null);
			panel.setVerifiedAs(null);
			panel.setWaitingFor(null);
			panel.setRosterSync(null);
		}
		rosterLine = null;
		rosterAnswer = null;
		rosterPending = false;
	}

	/**
	 * A sign-in or Verify NAME just verified {@code name}: the player menu
	 * folds to "Verified as NAME" now, not at the next {@code /plugin/me}.
	 */
	void addVerified(String name)
	{
		justVerified.add(name);
		List<String> v = new ArrayList<>(verifiedNames);
		v.add(name);
		verifiedNames = v;
		updateVerified();
	}

	/**
	 * A {@code /plugin/me} answer's names, plus any verified here since that the
	 * answer doesn't list yet (a request already on its way when the verify
	 * finished), so the fold isn't undone. A name drops out once the site lists it.
	 */
	List<String> withJustVerified(List<String> fromSite)
	{
		List<String> v = new ArrayList<>(fromSite);
		for (String n : justVerified)
		{
			if (fromSite.stream().anyMatch(s -> rsnKey(s) != null && rsnKey(s).equals(rsnKey(n))))
			{
				justVerified.remove(n);
			}
			else
			{
				v.add(n);
			}
		}
		return v;
	}

	/**
	 * Verify for Discord folds away once the logged-in name is one the site has
	 * verified to this member (from {@code /plugin/me}, already fetched with the
	 * panel: no extra request).
	 */
	private void updateVerified()
	{
		withRsn(this::applyVerified);
	}

	/** Client thread. A null name while logged in means the game hasn't sent it yet: try again next tick. */
	void applyVerified(String rsn)
	{
		if (rsn == null && loggedIn())
		{
			verifiedNeeded();
			return;     // keep what the panel shows until the name is known
		}
		showVerifiedAs(verifiedMatch(rsn, verifiedNames));
	}

	/** The panel's verified name: the matched name, or null for the menu to offer Verify NAME for Discord. */
	void showVerifiedAs(String match)
	{
		DreamChasersPanel panel = this.panel;
		if (panel != null)
		{
			panel.setVerifiedAs(match);
		}
	}

	/** {@code rsn} when it is one of {@code names} (as the game writes names), else null. */
	static String verifiedMatch(String rsn, List<String> names)
	{
		String key = rsnKey(rsn);
		if (key == null || names == null)
		{
			return null;
		}
		for (String n : names)
		{
			if (key.equals(rsnKey(n)))
			{
				return rsn;
			}
		}
		return null;
	}

	/** RSNs compare without case, and spaces, underscores and hyphens read alike in game. */
	static String rsnKey(String rsn)
	{
		if (rsn == null)
		{
			return null;
		}
		String k = rsn.replace('\u00a0', ' ').replace('_', ' ').replace('-', ' ').trim().toLowerCase(Locale.ROOT);
		return k.isEmpty() ? null : k;
	}

	/** The signed-in member's name, or null when there's no token. */
	private String signedInName()
	{
		String token = config.siteToken();
		if (token == null || token.isEmpty())
		{
			return null;
		}
		String name = config.signedInAs();
		return name == null || name.isEmpty() ? "clan member" : name;
	}

	@Provides
	DreamChasersClanConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DreamChasersClanConfig.class);
	}
}
