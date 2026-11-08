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
import cc.dreamchasers.clan.api.Endpoint;
import cc.dreamchasers.clan.api.FeaturedResponse;
import cc.dreamchasers.clan.api.HallOfFameResponse;
import cc.dreamchasers.clan.api.LiveActionResponse;
import cc.dreamchasers.clan.api.LiveJoinResponse;
import cc.dreamchasers.clan.api.LiveResponse;
import cc.dreamchasers.clan.api.MeResponse;
import cc.dreamchasers.clan.api.ModelStatusResponse;
import cc.dreamchasers.clan.api.RaidResponse;
import cc.dreamchasers.clan.api.RosterResponse;
import cc.dreamchasers.clan.api.WitnessResponse;
import cc.dreamchasers.clan.api.BatchResponse;
import cc.dreamchasers.clan.api.SubmitResponse;
import cc.dreamchasers.clan.loot.ClogTracker;
import cc.dreamchasers.clan.loot.LootPicker;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.clan.ClanID;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.events.ClanChannelChanged;
import net.runelite.api.events.GameTick;
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
	static final String VERSION = "0.5.2";
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
	private ConnectFlow connectFlow;
	private JoinFlow joinFlow;
	private VerifyFlow verifyFlow;
	// clan splash: the site enforces the upload limit too
	private volatile Instant lastModelUpload;
	private volatile String splashImageUrl;
	private volatile Instant splashImageTriedAt = Instant.EPOCH;
	private volatile boolean splashImageOk;
	// Pairs kill-count and time lines; client thread only
	private final PbParser pbParser = new PbParser();
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
	/** The time last sent per boss and team size, so a standing PB is reported once a session. */
	private final Map<String, Double> pbSent = new ConcurrentHashMap<>();
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
	private volatile List<String> verifiedNames = Collections.emptyList();
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
	// verify pickup (with the roster sync setting): witness codes said in clan chat or a PM, and the 5-minute "here"
	private volatile WitnessPickup pickup;
	// the clan and own name the last "here" went with, so the "gone" after logout can name them
	private volatile String hereClan;
	private volatile String hereSender;

	@Override
	protected void startUp()
	{
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
		pickup = new WitnessPickup();
		connectFlow = new ConnectFlow(api, executor, Clock.systemUTC(), url -> safeLinks.open(url, safeLinks.site()));
		panel.setCollapsed(config.collapsedSections());
		panel.setConnected(config.connect());
		panel.setConnectDismissed(config.connectDismissed());
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
		pickupGone();
		for (ScheduledFuture<?> f : new ScheduledFuture<?>[]{tick, liveTick})
		{
			if (f != null)
			{
				f.cancel(false);
			}
		}
		tick = null;
		liveTick = null;
		if (connectFlow != null)
		{
			connectFlow.cancel();
			connectFlow = null;
		}
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
		clientToolbar.removeNavigation(navButton);
		if (panel != null)
		{
			panel.dispose();
		}
		panel = null;
		navButton = null;
		api = null;
		raidStats = null;
		roster = null;
		pickup = null;
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
		pbSent.clear();
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
			if (state == GameState.LOGGING_IN)
			{
				return;
			}
			setOwnClan(OwnClan.Reading.LOGGED_OUT);
			clanCheckDue = true;
			officer = false;
			rosterDueTick = -1;
			pbParser.reset();
			trialTimer.reset();
			clogTracker.reset();
			if (state == GameState.LOGIN_SCREEN)
			{
				pbSent.clear();     // maybe another account next
				liveOnStream = false;
				RosterSync r = roster;
				if (r != null)
				{
					r.resume();     // another account may be an officer
				}
				pickupGone();
				WitnessPickup p = pickup;
				if (p != null)
				{
					p.resume();
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
		updateVerified();     // another account may be verified, or not
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
		if (type == ChatMessageType.CLAN_CHAT || type == ChatMessageType.PRIVATECHAT)
		{
			pickupChat(event.getName(), event.getMessage(),
				type == ChatMessageType.PRIVATECHAT ? WitnessPickup.Via.PM : WitnessPickup.Via.CLAN);
			return;
		}
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
		Double last = pbSent.get(record.key());
		if (!record.isPb() && last != null && last <= record.getSeconds())
		{
			return;     // this standing PB was already sent this session
		}
		pbSent.put(record.key(), record.getSeconds());
		api.postAuthed("/plugin/pbs", Uploads.pb(record, rsn, Clock.systemUTC().instant(), accountDigest()), SubmitResponse.class, result ->
		{
			if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
			{
				forgetToken();
			}
			if (result.getStatus() != ApiResult.Status.OK)
			{
				log.debug("PB for {} not taken: {}", record.getBoss(), result.getMessage());
			}
		});
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
		clanTick();
		clogTick();
		raidTick();
		trialTick();
		backfillTick();
		rosterTick();
		pickupTick();
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
	private void backfillTick()
	{
		if (!backfillDue || !config.connect() || !config.postPbs() || signedInName() == null)
		{
			return;
		}
		Player me = client.getLocalPlayer();
		String rsn = me == null ? null : me.getName();
		String profile = configManager.getRSProfileKey();
		DrmApiClient api = this.api;
		if (rsn == null || profile == null || api == null)
		{
			return;     // try again next tick
		}
		backfillDue = false;
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
			return;
		}
		String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
		String hash = PbBackfill.hash(stored);
		if (!PbBackfill.due(config.pbBackfillSent(), today, rsn, hash))
		{
			return;
		}
		JsonObject body = Uploads.batch(PbBackfill.records(stored), rsn, accountDigest());
		api.postAuthed("/plugin/pbs/batch", body, BatchResponse.class, result ->
		{
			if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
			{
				forgetToken();
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
		api.postAuthed("/plugin/raid", RaidStats.body(raid, hp, maxHp, prayer, maxPrayer, list), RaidResponse.class, result ->
		{
			if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
			{
				forgetToken();
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
		api.postAuthed(RosterSync.PATH, RosterSync.body(settings.getName(), Text.toJagexName(own), members), RosterResponse.class, result ->
		{
			RosterSync.Outcome out = sync.answered(result, hash, LocalTime.now(ZoneId.systemDefault()));
			if (out.isForgetToken())
			{
				forgetToken();
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

	// ---- verify pickup ----

	/** Whether the pickup may run: Connect and the setting on, signed in, logged in, an officer in Dream Chasers. */
	private boolean pickupOn()
	{
		return pickup != null && api != null && officer && config.connect() && config.officerRosterSync()
			&& signedInName() != null && client.getGameState() == GameState.LOGGED_IN;
	}

	/** The 5-minute "here", so the /join page can say an officer's plugin is online. Client thread. */
	private void pickupTick()
	{
		WitnessPickup p = pickup;
		if (!pickupOn())
		{
			return;
		}
		ClanSettings settings = client.getClanSettings(ClanID.CLAN);
		Player me = client.getLocalPlayer();
		if (settings == null || me == null || me.getName() == null || !p.hereDue(Clock.systemUTC().instant()))
		{
			return;
		}
		hereClan = settings.getName();
		hereSender = Text.toJagexName(me.getName());
		api.postAuthed(WitnessPickup.PATH, WitnessPickup.hereBody(hereClan, hereSender, true), WitnessResponse.class,
			this::pickupAnswered);
	}

	/** Logged out, the setting off or the plugin stopping: the site hears the officer has gone. */
	private void pickupGone()
	{
		WitnessPickup p = pickup;
		DrmApiClient api = this.api;
		String clan = hereClan, sender = hereSender;
		if (p == null || api == null || clan == null || sender == null || !p.goneDue())
		{
			return;
		}
		api.postAuthed(WitnessPickup.PATH, WitnessPickup.hereBody(clan, sender, false), WitnessResponse.class, result -> { });
	}

	/**
	 * A clan chat line or a received PM: only when it holds a witness code do
	 * the code and the sender's name go to the site. Client thread.
	 */
	private void pickupChat(String name, String message, WitnessPickup.Via via)
	{
		WitnessPickup p = pickup;
		if (!pickupOn())
		{
			return;
		}
		String code = WitnessPickup.findCode(message);
		String rsn = code == null ? null : WitnessPickup.senderName(name);
		ClanSettings settings = client.getClanSettings(ClanID.CLAN);
		Player me = client.getLocalPlayer();
		if (rsn == null || settings == null || me == null || me.getName() == null)
		{
			return;
		}
		String own = Text.toJagexName(me.getName());
		if (rsn.equalsIgnoreCase(own) || (via == WitnessPickup.Via.PM && settings.findMember(rsn) == null))
		{
			return;     // the officer's own line, or a PM from someone outside the clan
		}
		if (!p.take(Clock.systemUTC().instant(), rsn, code))
		{
			return;     // already sent, or over the limit
		}
		api.postAuthed(WitnessPickup.PATH, WitnessPickup.witnessBody(settings.getName(), own, rsn, code, via),
			WitnessResponse.class, result ->
			{
				if (result.getStatus() == ApiResult.Status.ERROR)
				{
					p.release(rsn, code);     // no answer: the same line may go again
				}
				pickupAnswered(result);
			});
	}

	private void pickupAnswered(ApiResult<WitnessResponse> result)
	{
		WitnessPickup p = pickup;
		if (p == null)
		{
			return;
		}
		WitnessPickup.Outcome out = p.answered(result, LocalTime.now(ZoneId.systemDefault()));
		if (out.isForgetToken())
		{
			forgetToken();
		}
		if (out.getLine() != null)
		{
			rosterAnswer = out.getLine();
			if (!rosterPending)
			{
				rosterLine = out.getLine();
			}
			updateRosterStatus();
		}
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
		panel.setRosterStatus(show ? (line == null ? RosterSync.idleLine() : line) : null);
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
			postLoot("clog " + c.getName(), Uploads.clog(c, rsn, at, accountDigest()));
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
			postLoot(source, Uploads.drop(source, items, rsn, at, accountDigest()));
		}
	}

	private void postLoot(String what, JsonObject body)
	{
		DrmApiClient api = this.api;
		if (api == null)
		{
			return;
		}
		api.postAuthed("/plugin/loots", body, SubmitResponse.class, result ->
		{
			if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
			{
				forgetToken();
			}
			if (result.getStatus() != ApiResult.Status.OK)
			{
				log.debug("{} not taken: {}", what, result.getMessage());
			}
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!DreamChasersClanConfig.GROUP.equals(event.getGroup()) || panel == null)
		{
			return;
		}
		// "Enable everything": on switches the seven features on, off switches them
		// off; one turned off by hand unticks it. It only flips settings: it never
		// opens the browser. Not signed in? The panel's Connect card does that.
		boolean signedIn = signedInName() != null;
		if (DreamChasersClanConfig.ENABLE_ALL_KEY.equals(event.getKey()) && featureOn(event.getKey()) && !signedIn)
		{
			panel.setEnableAllRefused(true);     // it unticks itself below: say why in the Connect card
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
				if (!config.connect())
				{
					pickupGone();
				}
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
				if (!config.connect() && connectFlow != null)
				{
					connectFlow.cancel();
					panel.setConnectWaiting(null, null);
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
				if (config.officerRosterSync() && pickup != null)
				{
					pickup.resume();
				}
				else if (!config.officerRosterSync())
				{
					pickupGone();
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
		api.fetch(Endpoint.COMPETITION, force, panel::showCompetition);
		api.fetch(Endpoint.HALL_OF_FAME, force, result ->
		{
			onHallOfFame(result);
			panel.showHallOfFame(result);
		});
		api.fetch(Endpoint.LINKS, force, panel::showLinks);
		if (signedInName() != null)
		{
			api.fetch(Endpoint.ME, force, result ->
			{
				MeResponse me = result.getValue();
				if (result.getStatus() == ApiResult.Status.OK && me != null && me.getVerified() != null)
				{
					verifiedNames = me.getVerified();
					updateVerified();
				}
			});
			api.fetch(Endpoint.EVENTS, force, result ->
			{
				if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
				{
					// The site no longer knows this token (revoked or expired).
					forgetToken();
				}
				panel.showEvents(result);
			});
		}
		refreshLive(force);
		refreshSplash();
		refreshTiles(force);
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
			api.fetch(Endpoint.MODEL, false, result ->
			{
				if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
				{
					forgetToken();
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
		api.fetch(Endpoint.LIVE, force, result ->
		{
			if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
			{
				forgetToken();
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

	@Override
	public void link(String code)
	{
		DrmApiClient api = this.api;
		DreamChasersPanel panel = this.panel;
		if (api == null || panel == null)
		{
			return;
		}
		if (!config.connect())
		{
			panel.linkFinished("Turn on Connect to dream-chasers.cc in the plugin settings first");
			return;
		}
		api.link(code, "RuneLite", result ->
		{
			if (result.getStatus() != ApiResult.Status.OK)
			{
				panel.linkFinished(result.getMessage());
				return;
			}
			panel.linkFinished(null);
			signedIn(result.getValue().getToken(), result.getValue().getName());
		});
	}

	/** Stores a new token and loads what it unlocks. */
	private void signedIn(String token, String name)
	{
		configManager.setConfiguration(DreamChasersClanConfig.GROUP, DreamChasersClanConfig.TOKEN_KEY, token);
		configManager.setConfiguration(DreamChasersClanConfig.GROUP, DreamChasersClanConfig.SIGNED_IN_AS_KEY,
			name == null || name.isEmpty() ? "clan member" : name);
		DreamChasersPanel panel = this.panel;
		DrmApiClient api = this.api;
		if (panel == null || api == null)
		{
			return;
		}
		panel.setConnectWaiting(null, null);
		panel.setSignedInAs(signedInName());
		updateRosterStatus();
		backfillDue = true;
		api.fetch(Endpoint.EVENTS, true, panel::showEvents);
		refreshLive(true);
	}

	/** Shown under the code once a Connect has waited 2 minutes (ConnectFlow.STILL_WAITING_AFTER). */
	public static final String STILL_WAITING = "Still waiting for Discord? Check your browser is signed in to discord.com "
		+ "with your main Discord account (it needs a verified email or phone). Then press Cancel and Connect again.";

	/**
	 * Pressing a join step (Join our Discord, Show Verify for Discord) or
	 * Connect is the opt-in: it turns on the same setting as the config panel's
	 * toggle, which stays off by default. Nothing is sent from here.
	 */
	@Override
	public void optIn()
	{
		if (!config.connect())
		{
			configManager.setConfiguration(DreamChasersClanConfig.GROUP, DreamChasersClanConfig.CONNECT_KEY, true);
		}
		configManager.unsetConfiguration(DreamChasersClanConfig.GROUP, DreamChasersClanConfig.DISMISSED_KEY);
	}

	/** "Already verified on another PC?": the browser-approve sign-in (ConnectFlow). */
	@Override
	public void connect()
	{
		optIn();
		DreamChasersPanel panel = this.panel;
		if (panel != null)
		{
			panel.setConnectWaiting(null, "Asking dream-chasers.cc…");
		}
		withRsn(rsn ->
		{
			ConnectFlow flow = connectFlow;
			if (flow == null || panel == null)
			{
				return;
			}
			flow.start(rsn, new ConnectFlow.Listener()
			{
				@Override
				public void waiting(String code)
				{
					panel.setConnectWaiting(code == null ? "…" : code, null);
				}

				@Override
				public void stillWaiting(String code)
				{
					panel.setConnectWaiting(code == null ? "…" : code, STILL_WAITING);
				}

				@Override
				public void connected(String token, String name)
				{
					signedIn(token, name);
				}

				@Override
				public void failed(String message)
				{
					panel.setConnectWaiting(null, message);
				}
			});
		});
	}

	@Override
	public void cancelConnect()
	{
		if (connectFlow != null)
		{
			connectFlow.cancel();
		}
		if (panel != null)
		{
			panel.setConnectWaiting(null, null);
		}
	}

	@Override
	public void dismissConnect()
	{
		configManager.setConfiguration(DreamChasersClanConfig.GROUP, DreamChasersClanConfig.DISMISSED_KEY, true);
		if (panel != null)
		{
			panel.setConnectDismissed(true);
		}
	}

	// ---- Verify for Discord (verified join) ----

	/** Shown under the code once Verify in browser has waited 2 minutes (ConnectFlow.STILL_WAITING_AFTER). */
	public static final String VERIFY_STILL_WAITING = "Still waiting? Check your browser is signed in to discord.com "
		+ "with your main Discord account (it needs a verified email or phone). Then press Cancel and Verify in browser again.";

	/** "Verify in browser": the one-click verify (VerifyFlow). The typed code stays behind "Have a code from the site?". */
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
				panel.verifyDone(message, false);
				signedIn(token, name);
				List<String> v = new ArrayList<>(verifiedNames);
				v.add(name);
				verifiedNames = v;
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
				panel.verifyDone(message, false);
				signedIn(token, name);
				List<String> v = new ArrayList<>(verifiedNames);
				v.add(name);
				verifiedNames = v;
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
			api.postAuthed("/plugin/live/join", body, LiveJoinResponse.class, result ->
			{
				if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
				{
					forgetToken();
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
		api.postAuthed(path, body, LiveActionResponse.class, result ->
		{
			if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
			{
				forgetToken();
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
		api.uploadModel(obj, result ->
		{
			if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
			{
				forgetToken();
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
		api.deleteModel(result ->
		{
			if (result.getStatus() == ApiResult.Status.UNAUTHORIZED)
			{
				forgetToken();
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

	/** The logged-in player's name (null at the login screen), read on the client thread. */
	private void withRsn(Consumer<String> then)
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
		pickupGone();     // while the token still works
		// with Connect off nothing is sent; the token is only forgotten here
		if (api != null && config.connect())
		{
			api.unlink();
		}
		forgetToken();
	}

	private void forgetToken()
	{
		verifiedNames = Collections.emptyList();
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
			panel.setRosterStatus(null);
		}
		rosterLine = null;
		rosterAnswer = null;
		rosterPending = false;
	}

	/**
	 * Verify for Discord folds away once the logged-in name is one the site has
	 * verified to this member (from {@code /plugin/me}, already fetched with the
	 * panel: no extra request).
	 */
	private void updateVerified()
	{
		DreamChasersPanel panel = this.panel;
		if (panel == null)
		{
			return;
		}
		List<String> names = verifiedNames;
		withRsn(rsn ->
		{
			String key = rsnKey(rsn);
			String match = null;
			for (String n : names)
			{
				if (key != null && key.equals(rsnKey(n)))
				{
					match = rsn;
				}
			}
			panel.setVerifiedAs(match);
		});
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
