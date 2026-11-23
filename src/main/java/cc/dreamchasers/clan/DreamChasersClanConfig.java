/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(DreamChasersClanConfig.GROUP)
public interface DreamChasersClanConfig extends Config
{
	String GROUP = "dreamchasersclan";
	String CONNECT_KEY = "connect";
	String TOKEN_KEY = "siteToken";
	String SIGNED_IN_AS_KEY = "signedInAs";
	String LIVE_KEY = "joinLive";
	String SPLASH_KEY = "clanSplash";
	String SPLASH_OPTED_IN_KEY = "splashOptedIn";
	String PBS_KEY = "postPbs";
	String LOOT_KEY = "postLoot";
	String LIVE_KC_KEY = "liveCompetitionKc";
	String RAID_KEY = "shareRaidStats";
	String TILES_KEY = "tilePacks";
	String ENABLE_ALL_KEY = "enableEverything";
	String ROSTER_KEY = "officerRosterSync";
	/** When old PBs were last sent: "date|rsn|hash" (once a day at most). */
	String PB_BACKFILL_KEY = "pbBackfillSent";
	/** The panel sections the member folded, comma-separated. */
	String COLLAPSED_KEY = "collapsedSections";

	/**
	 * RuneLite shows a setting's description as its tooltip, as HTML on one
	 * line; this fixed width wraps it instead of running across the screen.
	 */
	String TIP = "<div style='width:230px'>";
	String TIP_END = "</div>";

	@ConfigSection(
		name = "Connect",
		description = "Sign in to dream-chasers.cc, Enable everything, and the officers' roster sync",
		position = 0
	)
	String CONNECT_SECTION = "sectionConnect";

	@ConfigSection(
		name = "Appearance",
		description = "The sidebar button and the clan splash",
		position = 1
	)
	String APPEARANCE_SECTION = "sectionAppearance";

	@ConfigSection(
		name = "Data",
		description = "What is posted to the clan's Hall of Fame",
		position = 2
	)
	String DATA_SECTION = "sectionData";

	@ConfigSection(
		name = "PvM",
		description = "The clan's ground-marker packs",
		position = 3
	)
	String PVM_SECTION = "sectionPvm";

	@ConfigSection(
		name = "Livestream",
		description = "DRM Live, the clan stream",
		position = 4
	)
	String LIVE_SECTION = "sectionLivestream";

	@ConfigItem(
		keyName = ENABLE_ALL_KEY,
		name = "Enable everything",
		description = TIP + "Sign in first (tick Connect to dream-chasers.cc above, or use the player menu at the top right of the Dream Chasers panel); until you're signed in it turns itself straight back off and the panel says why. "
			+ "Then it turns on every Dream Chasers feature below: Post personal bests, Post collection log, "
			+ "Live competition KC, Clan splash, Clan tile packs, Join DRM Live and Share raid stats. Ticking it also turns Connect back on if you'd turned it off; untick to turn them all off, Connect included. It ticks itself once you have turned every one of them on. "
			+ "RuneLite only shows the new ticks once you close and reopen these settings. "
			+ "It never agrees to the DRM Live stream consent or sends your character's model; those stay their own buttons" + TIP_END,
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		section = CONNECT_SECTION,
		position = 1
	)
	default boolean enableEverything()
	{
		return false;
	}

	@ConfigItem(
		keyName = CONNECT_KEY,
		name = "Connect to dream-chasers.cc",
		description = TIP + "Fetch the clan's events, competition standings, Hall of Fame, featured splash and links from dream-chasers.cc for the sidebar panel. "
			+ "Ticking it also signs you in: once you're logged in to the game and in DRM Chasers, your browser opens dream-chasers.cc to sign in with Discord, "
			+ "which verifies your RSN for the clan's Discord too. For that it reads only your own clan record and sends your RSN, the clan name and a hashed account id "
			+ "(never the account id itself). Sign in again, verify another name or enter a code from the site in the player menu at the top right of the panel" + TIP_END,
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		section = CONNECT_SECTION,
		position = 0
	)
	default boolean connect()
	{
		return false;
	}

	@ConfigItem(
		keyName = ROSTER_KEY,
		name = "Officer roster sync",
		description = TIP + "Officers and up only: when you close the clan Members screen, send the clan's member list and ranks to dream-chasers.cc, which updates the Wise Old Man group. "
			+ "Names and rank titles only. "
			+ "Needs you signed in; Enable everything leaves it alone" + TIP_END,
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		section = CONNECT_SECTION,
		position = 10
	)
	default boolean officerRosterSync()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showSidebar",
		name = "Show sidebar button",
		description = TIP + "Show the Dream Chasers button in the RuneLite sidebar" + TIP_END,
		section = APPEARANCE_SECTION,
		position = 2
	)
	default boolean showSidebar()
	{
		return true;
	}

	@ConfigItem(
		keyName = LIVE_KEY,
		name = "Join DRM Live",
		description = TIP + "Show the DRM Live section: read the stream consent, then get your own link to share your game window on the clan stream (opens in your browser; the plugin never captures the game itself). Joining sends your RSN" + TIP_END,
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		section = LIVE_SECTION,
		position = 3
	)
	default boolean joinLive()
	{
		return false;
	}

	@ConfigItem(
		keyName = SPLASH_KEY,
		name = "Clan splash",
		description = TIP + "Show the Feature me button: send your own character's 3D shape (gear included) to dream-chasers.cc, to be drawn as the statue on the clan splash with your RSN. The same picture becomes your profile picture and 3D view on the site and may be shown on DRM Live" + TIP_END,
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		section = APPEARANCE_SECTION,
		position = 4
	)
	default boolean clanSplash()
	{
		return false;
	}

	@ConfigItem(
		keyName = PBS_KEY,
		name = "Post personal bests",
		description = TIP + "When the game tells you a boss or raid time, or you finish a Sailing Barracuda Trial (timed by the plugin), send it to the clan's Hall of Fame on dream-chasers.cc (boss, time, kill count, team size where the game gives it, your RSN and a hashed account id). Also sends the PBs RuneLite's Chat Commands has already stored, at most once a day (again if you switch accounts that day). Your own kills only; needs you to be connected" + TIP_END,
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		section = DATA_SECTION,
		position = 5
	)
	default boolean postPbs()
	{
		return false;
	}

	@ConfigItem(
		keyName = LOOT_KEY,
		name = "Post collection log",
		description = TIP + "Send each new collection log slot, and your own drops worth the clan's big-drop amount (set by the site, 10M today, never below 1M), with where each came from, to the clan's Hall of Fame on dream-chasers.cc. Needs the game's 'Collection log - New addition notification' setting (chat or popup); the panel tells you if it's off. Big drops are priced with RuneLite's own prices. Each post carries your RSN and a hashed account id" + TIP_END,
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		section = DATA_SECTION,
		position = 6
	)
	default boolean postLoot()
	{
		return false;
	}

	@ConfigItem(
		keyName = LIVE_KC_KEY,
		name = "Live competition KC",
		description = TIP + "While a clan competition on a boss or raid is running, send your kill count for that boss to dream-chasers.cc each time the game tells you it, "
			+ "so the competition standings move as you kill instead of waiting for Wise Old Man (which only sees your hiscores after you log out). "
			+ "Sends the boss, your kill count, your RSN, a hashed account id and your own account type (normal, ironman and so on); only for the bosses the site says are in a running competition, "
			+ "your own kills only. When you log out or hop worlds during one, it also tells the site once (your RSN, hashed account id and account type) so the site can ask Wise Old Man to update you straight away. Needs you signed in" + TIP_END,
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		section = DATA_SECTION,
		position = 7
	)
	default boolean liveCompetitionKc()
	{
		return false;
	}

	@ConfigItem(
		keyName = RAID_KEY,
		name = "Share raid stats on DRM Live",
		description = TIP + "While you're on the clan stream (DRM Live) and inside Chambers of Xeric, Theatre of Blood or Tombs of Amascut, send your own hitpoints, prayer and inventory to dream-chasers.cc, at most every 5 seconds, for the stream overlay. Nothing about other players. Only after you've agreed to the stream consent and joined the stream in the DRM Live section; otherwise nothing is sent" + TIP_END,
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		section = LIVE_SECTION,
		position = 8
	)
	default boolean shareRaidStats()
	{
		return false;
	}

	@ConfigItem(
		keyName = TILES_KEY,
		name = "Clan tile packs",
		description = TIP + "Show the clan's ground-marker packs from dream-chasers.cc in the panel, each with a Copy button. Import a copied pack with RuneLite's own Ground Markers (right-click the world map orb)" + TIP_END,
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		section = PVM_SECTION,
		position = 9
	)
	default boolean tilePacks()
	{
		return false;
	}

	@ConfigItem(
		keyName = PB_BACKFILL_KEY,
		name = "",
		description = "",
		hidden = true
	)
	default String pbBackfillSent()
	{
		return "";
	}

	@ConfigItem(
		keyName = SPLASH_OPTED_IN_KEY,
		name = "",
		description = "",
		hidden = true
	)
	default boolean splashOptedIn()
	{
		return false;
	}

	@ConfigItem(
		keyName = TOKEN_KEY,
		name = "",
		description = "",
		hidden = true,
		secret = true
	)
	default String siteToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = COLLAPSED_KEY,
		name = "",
		description = "",
		hidden = true
	)
	default String collapsedSections()
	{
		return "";
	}

	@ConfigItem(
		keyName = SIGNED_IN_AS_KEY,
		name = "",
		description = "",
		hidden = true
	)
	default String signedInAs()
	{
		return "";
	}
}
