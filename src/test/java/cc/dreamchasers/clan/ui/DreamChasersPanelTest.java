/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import cc.dreamchasers.clan.OwnClan;
import cc.dreamchasers.clan.api.ApiResult;
import cc.dreamchasers.clan.api.CompetitionResponse;
import cc.dreamchasers.clan.api.EventsResponse;
import cc.dreamchasers.clan.api.HallOfFameResponse;
import cc.dreamchasers.clan.api.LinksResponse;
import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import net.runelite.client.util.ImageUtil;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Renders the panel headless from the API fixtures: checks what it says and
 * writes build/panel-*.png so the look can be reviewed without a game client.
 */
public class DreamChasersPanelTest
{
	private static final Instant NOW = Instant.parse("2026-11-02T17:00:00Z");
	private static final Gson GSON = new Gson();

	@BeforeClass
	public static void headless()
	{
		System.setProperty("java.awt.headless", "true");
	}

	private static class Calls implements DreamChasersPanel.Listener
	{
		final List<String> log = new ArrayList<>();

		@Override
		public void refresh(boolean force)
		{
			log.add("refresh " + force);
		}

		@Override
		public void collapsedChanged(String keys)
		{
			log.add("collapsed " + keys);
		}

		@Override
		public void link(String code)
		{
			log.add("link " + code);
		}

		@Override
		public void signOut()
		{
			log.add("signOut");
		}

		@Override
		public void connect()
		{
			log.add("connect");
		}

		@Override
		public void cancelConnect()
		{
			log.add("cancelConnect");
		}

		@Override
		public void optIn()
		{
			log.add("optIn");
		}

		@Override
		public void dismissConnect()
		{
			log.add("dismissConnect");
		}

		@Override
		public void verifyStart()
		{
			log.add("verifyStart");
		}

		@Override
		public void verifyStop()
		{
			log.add("verifyStop");
		}

		@Override
		public void verifyPeek(String code)
		{
			log.add("verifyPeek " + code);
		}

		@Override
		public void verifyConfirm()
		{
			log.add("verifyConfirm");
		}

		@Override
		public void verifyCancel()
		{
			log.add("verifyCancel");
		}

		@Override
		public void liveAgree(String version)
		{
			log.add("liveAgree " + version);
		}

		@Override
		public void liveJoin()
		{
			log.add("liveJoin");
		}

		@Override
		public void liveLeave()
		{
			log.add("liveLeave");
		}

		@Override
		public void liveWithdraw()
		{
			log.add("liveWithdraw");
		}

		@Override
		public void featureMe()
		{
			log.add("featureMe");
		}

		@Override
		public void removeMe()
		{
			log.add("removeMe");
		}

		@Override
		public void open(String url)
		{
			log.add("open " + url);
		}

		@Override
		public void enableAll()
		{
			log.add("enableAll");
		}
	}

	private static <T> ApiResult<T> ok(String fixture, Class<T> type)
	{
		return new ApiResult<>(ApiResult.Status.OK, GSON.fromJson(read(fixture), type), NOW, null);
	}

	private static String read(String name)
	{
		try (InputStream in = DreamChasersPanelTest.class.getResourceAsStream("/cc/dreamchasers/clan/api/" + name))
		{
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
	}

	/** The panel with its two dialogs stubbed: no window opens headless. */
	private static class StubPanel extends DreamChasersPanel
	{
		/** What the code box answers; null is Cancel. */
		String code;
		/** What "This links RSN to Discord NAME" answers. */
		boolean yes;
		final List<String> asked = new ArrayList<>();

		StubPanel(Calls calls, BufferedImage mark)
		{
			super(calls, mark, java.time.Clock.fixed(NOW, ZoneId.of("UTC")), ZoneId.of("Europe/London"));
		}

		@Override
		String askCode()
		{
			return code;
		}

		@Override
		boolean confirmLink(String rsn, String discordName)
		{
			asked.add(rsn + "|" + discordName);
			return yes;
		}
	}

	private static StubPanel newPanel(Calls calls) throws Exception
	{
		BufferedImage mark = ImageUtil.loadImageResource(DreamChasersPanel.class, "/cc/dreamchasers/clan/wordmark.png");
		StubPanel[] out = new StubPanel[1];
		SwingUtilities.invokeAndWait(() -> out[0] = new StubPanel(calls, mark));
		return out[0];
	}

	/** The one status line under the header. */
	private static String statusText(DreamChasersPanel panel) throws Exception
	{
		String[] out = new String[1];
		SwingUtilities.invokeAndWait(() -> out[0] = ((JLabel) named(panel, "status")).getText());
		return out[0];
	}

	/** The old Join / Not connected / Connecting and Verify for Discord boxes are gone in every state. */
	private static void assertNoSignInBoxes(DreamChasersPanel panel)
	{
		assertEquals(null, findNamed(panel, "section:account"));
		assertEquals(null, findNamed(panel, "section:verify"));
		assertFalse(sectionNames(panel).contains("account") || sectionNames(panel).contains("verify"));
	}

	private static String allText(Container c)
	{
		StringBuilder sb = new StringBuilder();
		for (Component child : c.getComponents())
		{
			if (child instanceof JLabel)
			{
				sb.append(((JLabel) child).getText()).append('\n');
			}
			else if (child instanceof AbstractButton)
			{
				sb.append("[").append(((AbstractButton) child).getText()).append("]\n");
			}
			if (child instanceof Container)
			{
				sb.append(allText((Container) child));
			}
		}
		return sb.toString();
	}

	private static void snapshot(DreamChasersPanel panel, String name) throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			panel.setSize(225, 10);
			panel.setSize(225, panel.getPreferredSize().height);
			layoutAll(panel);
			// wrapped HTML labels know their height only after a layout pass: measure again
			panel.setSize(225, panel.getPreferredSize().height);
			layoutAll(panel);
			BufferedImage img = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			panel.paint(g);
			g.dispose();
			try
			{
				File out = new File("build/panel-" + name + ".png");
				out.getParentFile().mkdirs();
				ImageIO.write(img, "png", out);
			}
			catch (IOException e)
			{
				throw new IllegalStateException(e);
			}
		});
	}

	private static void layoutAll(Container c)
	{
		c.doLayout();
		for (Component child : c.getComponents())
		{
			if (child instanceof Container)
			{
				layoutAll((Container) child);
			}
		}
	}

	private static void flush() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
		});
	}

	@Test
	public void disconnectedPointsToTheSettings() throws Exception
	{
		Calls calls = new Calls();
		DreamChasersPanel panel = newPanel(calls);
		flush();

		assertNoSignInBoxes(panel);
		String text = allText(panel);
		assertFalse(text, text.contains("JOIN DREAM CHASERS") || text.contains("NOT CONNECTED") || text.contains("VERIFY FOR DISCORD"));
		assertTrue(statusText(panel), statusText(panel).contains(DreamChasersPanel.CONNECT_OFF));
		Component player = named(panel, "player");
		assertTrue("the player button is there signed out too", player.isVisible());
		assertEquals("Sign in", ((javax.swing.JComponent) player).getToolTipText());
		assertEquals(List.of("~Not signed in", "~Turn on Connect in the plugin settings", "Join our Discord", "Open dream-chasers.cc"),
			menuLabels(panel));
		assertFalse("the panel never turns Connect on by itself", calls.log.contains("optIn"));
		snapshot(panel, "disconnected");
	}

	@Test
	public void signedOutShowsSiteDataAndNoSignInBox() throws Exception
	{
		Calls calls = new Calls();
		DreamChasersPanel panel = newPanel(calls);
		panel.setConnected(true);
		panel.showCompetition(ok("competition-raid.json", CompetitionResponse.class));
		panel.showHallOfFame(ok("hall-of-fame.json", HallOfFameResponse.class));
		panel.showLinks(ok("links.json", LinksResponse.class));
		flush();

		String text = allText(panel);
		assertTrue(text.contains("Raid week - Chambers of Xeric"));
		assertTrue(text.contains("Zokairox Lam"));
		assertTrue(text.contains("Normal + CM"));
		assertTrue(text.contains("Recent clan firsts"));
		assertNoSignInBoxes(panel);
		assertFalse(text, text.contains("JOIN DREAM CHASERS") || text.contains("VERIFY FOR DISCORD") || text.contains("How to connect"));
		assertFalse("no call to action in the panel", visibleButton(panel, "Verify in browser") || visibleButton(panel, "Connect"));
		assertEquals("Connect on and signed out: no status line", "", statusText(panel));
		snapshot(panel, "signed-out");
	}

	@Test
	public void signedInShowsEventsAndEscapesSiteText() throws Exception
	{
		DreamChasersPanel panel = newPanel(new Calls());
		panel.setConnected(true);
		panel.setSignedInAs("Maho z h");
		String events = read("events.json").replace("Mass Zulrah", "Mass Zulrah <b>&</b>");
		panel.showEvents(new ApiResult<>(ApiResult.Status.OK, GSON.fromJson(events, EventsResponse.class), NOW, null));
		panel.showCompetition(ok("competition.json", CompetitionResponse.class));
		panel.showHallOfFame(ok("hall-of-fame.json", HallOfFameResponse.class));
		LinksResponse links = GSON.fromJson(read("links.json"), LinksResponse.class);
		links.setDiscord("https://discord.gg/example");
		panel.showLinks(new ApiResult<>(ApiResult.Status.OK, links, NOW, null));
		flush();

		String text = allText(panel);
		assertTrue(text.contains("Mass Zulrah &lt;b&gt;&amp;&lt;/b&gt;"));
		assertTrue(text.contains("Mon 2 Nov, 19:00 · in 2h"));
		assertTrue(text.contains("Wildy Wednesday"));
		assertTrue(text.contains("Starts in"));
		assertFalse("the website link section is gone: the wordmark is the link", text.contains("[dream-chasers.cc]"));
		assertFalse("no Links section", text.contains("DREAM CHASERS\n"));
		assertNoSignInBoxes(panel);
		assertEquals("signed in, logged out of the game: nothing to verify yet",
			List.of("~Connected as Maho z h", "Join our Discord", "Open dream-chasers.cc", "Sign out"),
			menuLabels(panel));
		snapshot(panel, "signed-in");
	}

	@Test
	public void eventsSendSignedOutMembersToTheMenu() throws Exception
	{
		DreamChasersPanel panel = newPanel(new Calls());
		panel.setConnected(true);
		flush();
		assertTrue(allText(panel).contains("Events are for clan members. Sign in from the menu at the top right to see them."));
	}

	@Test
	public void thePlayerMenuOffersTheDiscordInviteOnceKnownAndChecksIt() throws Exception
	{
		Calls calls = new Calls();
		DreamChasersPanel panel = newPanel(calls);
		panel.setConnected(true);
		panel.setSignedInAs("Maho z h");
		flush();
		clickMenu(panel, "Join our Discord");
		assertEquals("before /links: the join page, which has the invite", "open https://dream-chasers.cc/join",
			calls.log.get(calls.log.size() - 1));
		LinksResponse links = GSON.fromJson(read("links.json"), LinksResponse.class);
		links.setDiscord("https://discord.gg/example");
		panel.showLinks(new ApiResult<>(ApiResult.Status.OK, links, NOW, null));
		flush();
		clickMenu(panel, "Join our Discord");
		assertEquals("open https://discord.gg/example", calls.log.get(calls.log.size() - 1));

		links.setDiscord("https://discord.gg.evil.example/login");
		panel.showLinks(new ApiResult<>(ApiResult.Status.OK, links, NOW, null));
		flush();
		int before = calls.log.size();
		clickMenu(panel, "Join our Discord");
		assertEquals("an outside page never opens", before, calls.log.size());
		assertTrue(allText(panel).contains("didn't look right"));
	}

	@Test
	public void siteLinksOpenOnlyToTheirOwnHosts() throws Exception
	{
		Calls calls = new Calls();
		DreamChasersPanel panel = newPanel(calls);
		panel.setConnected(true);     // not signed in: the player menu has the invite
		LinksResponse links = GSON.fromJson(read("links.json"), LinksResponse.class);
		links.setDiscord("https://discord.gg/example");
		panel.showLinks(new ApiResult<>(ApiResult.Status.OK, links, NOW, null));
		flush();
		clickMenu(panel, "Join our Discord");
		assertEquals("open https://discord.gg/example", calls.log.get(calls.log.size() - 1));
		clickNamed(panel, "wordmark");
		assertEquals("open https://dream-chasers.cc/", calls.log.get(calls.log.size() - 1));    // the checked form goes to the browser

		// a site bug hands over an outside page: nothing opens, and the panel says so
		links.setDiscord("https://discord.gg.evil.example/login");
		panel.showLinks(new ApiResult<>(ApiResult.Status.OK, links, NOW, null));
		flush();
		clickMenu(panel, "Join our Discord");
		assertFalse(calls.log.get(calls.log.size() - 1).startsWith("open https://discord.gg.evil"));
		assertTrue(allText(panel).contains("didn't look right"));
	}

	@Test
	public void theWordmarkOpensTheSite() throws Exception
	{
		Calls calls = new Calls();
		DreamChasersPanel panel = newPanel(calls);
		flush();
		AbstractButton mark = (AbstractButton) named(panel, "wordmark");
		assertTrue("the wordmark image is the link", mark.getIcon() != null);
		assertEquals("Open dream-chasers.cc", mark.getToolTipText());
		clickNamed(panel, "wordmark");
		assertEquals("open https://dream-chasers.cc/", calls.log.get(calls.log.size() - 1));
	}

	@Test
	public void sectionsRunEventsCompetitionHallOfFameTilesLiveThenTheSplash() throws Exception
	{
		DreamChasersPanel panel = newPanel(new Calls());
		panel.setConnected(true);
		panel.setSignedInAs("Maho z h");
		panel.setTilesEnabled(true);
		panel.setLiveEnabled(true);
		flush();
		assertEquals(List.of("everything", "clogHint",
			"events", "competition", "halloffame", "tiles", "live", "splash"), sectionNames(panel));
		assertEquals(List.of("events", "competition", "halloffame", "tiles", "live"), DreamChasersPanel.SECTION_ORDER);

		String text = allText(panel);
		int at = -1;
		for (String title : new String[]{"EVENTS", "COMPETITION", "HALL OF FAME", "TILE PACKS", "DRM LIVE", "CLAN SPLASH"})
		{
			int i = text.indexOf(title);
			assertTrue(title + " after the one before it in:\n" + text, i > at);
			at = i;
		}
		snapshot(panel, "order");
	}

	@Test
	public void thePlayerButtonIsAlwaysThereAndItsMenuSignsOut() throws Exception
	{
		Calls calls = new Calls();
		DreamChasersPanel panel = newPanel(calls);
		flush();
		Component player = named(panel, "player");
		assertTrue("signed out, Connect off", player.isVisible());
		panel.setConnected(true);
		flush();
		assertTrue("signed out, Connect on", player.isVisible());
		assertEquals("Sign in", ((javax.swing.JComponent) player).getToolTipText());

		panel.setSignedInAs("Maho z h");
		flush();
		assertTrue(player.isVisible());
		assertEquals("Connected as Maho z h", ((javax.swing.JComponent) player).getToolTipText());
		assertEquals(DreamChasersPanel.PLAYER_SIZE, player.getPreferredSize().width);
		assertEquals("~Connected as Maho z h", menuLabels(panel).get(0));
		assertTrue(menuLabels(panel).contains("Sign out"));
		assertFalse("no Sign out button anywhere in the panel", visibleButton(panel, "Sign out"));
		assertFalse(allText(panel).contains("[Sign out]"));

		clickMenu(panel, "Open dream-chasers.cc");
		assertEquals("open https://dream-chasers.cc/", calls.log.get(calls.log.size() - 1));
		clickMenu(panel, "Sign out");
		assertEquals("signOut", calls.log.get(calls.log.size() - 1));

		panel.setVerifiedAs("Maho z h");
		flush();
		assertEquals("~Verified as Maho z h", menuLabels(panel).get(0));
		assertEquals("Verified as Maho z h", ((javax.swing.JComponent) player).getToolTipText());
		snapshot(panel, "player");

		panel.setSignedInAs(null);
		flush();
		assertTrue("still there after sign-out", player.isVisible());
		assertEquals("Sign in", ((javax.swing.JComponent) player).getToolTipText());
		assertFalse("no Sign out once signed out", menuLabels(panel).contains("Sign out"));
		assertEquals("~Not signed in", menuLabels(panel).get(0));
	}

	@Test
	public void theMenuSaysWhatSignInNeeds() throws Exception
	{
		Calls calls = new Calls();
		DreamChasersPanel panel = newPanel(calls);
		flush();
		assertEquals("Connect off", "~Turn on Connect in the plugin settings", menuLabels(panel).get(1));
		assertFalse("no code entry with Connect off", menuLabels(panel).contains("Enter a code from the site…"));

		panel.setConnected(true);
		flush();
		assertEquals("at the login screen", "~Log in to the game to sign in", menuLabels(panel).get(1));
		assertFalse("no code entry before there's a name to verify", menuLabels(panel).contains("Enter a code from the site…"));

		panel.setOwnClan("Join the clan in game first", null);
		flush();
		assertEquals("the own clan record blocks it", "~Join the clan in game first", menuLabels(panel).get(1));
		assertFalse("nothing to press", menuLabels(panel).contains("Sign in with Discord"));

		panel.setOwnClan(null, "Tadeko");
		flush();
		assertEquals(List.of("~Not signed in", "Sign in with Discord", "Enter a code from the site…", "Join our Discord", "Open dream-chasers.cc"),
			menuLabels(panel));
		clickMenu(panel, "Sign in with Discord");
		assertEquals("verifyStart", calls.log.get(calls.log.size() - 1));
		assertTrue(statusText(panel).contains("Asking dream-chasers.cc…"));
		assertEquals("one start at a time", "~Sign in with Discord", menuLabels(panel).get(1));
		assertFalse("no code entry while busy", menuLabels(panel).contains("Enter a code from the site…"));
		assertFalse("the panel never turns Connect on itself", calls.log.contains("optIn"));
	}

	@Test
	public void signedInTheMenuOffersVerifyForTheLoggedInName() throws Exception
	{
		Calls calls = new Calls();
		DreamChasersPanel panel = newPanel(calls);
		panel.setConnected(true);
		panel.setSignedInAs("Maho z h");
		flush();
		assertFalse("at the login screen a signed-in member sees no sign-in line",
			menuLabels(panel).contains("~Log in to the game to sign in"));

		panel.setOwnClan(null, "Tadeko");
		flush();
		assertEquals(List.of("~Connected as Maho z h", "Verify Tadeko for Discord", "Enter a code from the site…", "Join our Discord",
			"Open dream-chasers.cc", "Sign out"), menuLabels(panel));
		clickMenu(panel, "Verify Tadeko for Discord");
		assertEquals("verifyStart", calls.log.get(calls.log.size() - 1));

		panel.verifyDone(null, false);
		panel.setVerifiedAs("Tadeko");
		flush();
		assertEquals("verified: nothing more to do, no code entry", List.of("~Verified as Tadeko", "Join our Discord",
			"Open dream-chasers.cc", "Sign out"), menuLabels(panel));

		panel.setOwnClan(OwnClan.blocker(OwnClan.Reading.UNKNOWN), null);
		flush();
		assertEquals("verified, clan record not loaded (or no clan): no blocker line", List.of("~Verified as Tadeko", "Join our Discord",
			"Open dream-chasers.cc", "Sign out"), menuLabels(panel));
		panel.setOwnClan("Your clan in game isn't DRM Chasers. Join DRM Chasers first", null);
		flush();
		assertEquals("verified, in another clan: nothing to do either", "Join our Discord", menuLabels(panel).get(1));

		panel.setVerifiedAs(null);
		flush();
		assertEquals("blocked, signed in, wrapped to the panel", List.of("~Connected as Maho z h", "~Your clan in game isn't DRM Chasers.",
			"~Join DRM Chasers first", "Join our Discord", "Open dream-chasers.cc", "Sign out"), menuLabels(panel));
		panel.setOwnClan(OwnClan.blocker(OwnClan.Reading.UNKNOWN), null);
		flush();
		assertMenuFits(panel);
	}

	@Test
	public void theBrowserWaitIsInTheMenuAndTheStatusLine() throws Exception
	{
		Calls calls = new Calls();
		DreamChasersPanel panel = newPanel(calls);
		panel.setConnected(true);
		panel.setOwnClan(null, "Tadeko");
		flush();
		clickMenu(panel, "Sign in with Discord");
		panel.setVerifyWaiting("AB12", null);
		flush();
		assertEquals(List.of("~Not signed in", "~Approve in your browser: AB12", "Cancel", "Join our Discord", "Open dream-chasers.cc"),
			menuLabels(panel));
		assertTrue(statusText(panel), statusText(panel).contains("Approve in your browser. Check the page shows <b>AB12</b>."));
		assertNoSignInBoxes(panel);
		snapshot(panel, "verify-waiting");

		panel.setVerifyWaiting("AB12", cc.dreamchasers.clan.DreamChasersClanPlugin.VERIFY_STILL_WAITING);
		flush();
		assertTrue("the still-waiting hint joins the line", statusText(panel).contains("Still waiting? Check your browser"));
		assertTrue(statusText(panel).contains("AB12"));
		clickMenu(panel, "Cancel");
		assertEquals("verifyStop", calls.log.get(calls.log.size() - 1));
		assertTrue(statusText(panel).contains("Cancelled. Nothing was linked."));
		assertEquals("ready again", "Sign in with Discord", menuLabels(panel).get(1));

		panel.setVerifyWaiting("CD34", null);
		panel.verifyDone("Not on the clan roster yet. Watch dream-chasers.cc/join.", false);
		flush();
		assertFalse("the answer ends the wait", statusText(panel).contains("CD34"));
		assertTrue(statusText(panel).contains("Not on the clan roster yet."));

		panel.verifyDone(cc.dreamchasers.clan.OwnClan.LOGGED_OUT_BLOCKER + ".", true);
		flush();
		assertEquals("an error in the warning colour", Brand.WARN, ((JLabel) named(panel, "status")).getForeground());
	}

	@Test
	public void aCodeFromTheSiteIsAskedForThenConfirmed() throws Exception
	{
		Calls calls = new Calls();
		StubPanel panel = newPanel(calls);
		panel.setConnected(true);
		panel.setOwnClan(null, "Tadeko");
		flush();

		panel.code = null;     // the member closes the box
		clickMenu(panel, "Enter a code from the site…");
		assertFalse("nothing sent for no code", calls.log.stream().anyMatch(l -> l.startsWith("verifyPeek")));

		panel.code = " abcd 2345 ";
		clickMenu(panel, "Enter a code from the site…");
		assertEquals("verifyPeek abcd 2345", calls.log.get(calls.log.size() - 1));
		assertTrue(statusText(panel).contains("Checking the code…"));

		panel.yes = true;
		panel.verifyAsk("Tadeko", "Maho <b>");
		flush();
		assertEquals("asked with the RSN and the Discord name as given", List.of("Tadeko|Maho <b>"), panel.asked);
		assertEquals("This links Tadeko to Discord Maho <b>. Only continue if that Discord account is yours. "
			+ "If someone sent you this code, press Cancel.", DreamChasersPanel.linkQuestion("Tadeko", "Maho <b>"));
		assertEquals("verifyConfirm", calls.log.get(calls.log.size() - 1));
		assertTrue(statusText(panel).contains("Linking…"));

		panel.verifiedDone("Verified: Tadeko is linked to your Discord.");
		flush();
		assertTrue(statusText(panel).contains("Verified: Tadeko is linked to your Discord."));

		panel.yes = false;
		panel.verifyAsk("Tadeko", "Someone");
		flush();
		assertEquals("verifyCancel", calls.log.get(calls.log.size() - 1));
		assertTrue(statusText(panel).contains("Cancelled. Nothing was linked."));
	}

	@Test
	public void theStatusLineKeepsItsPriorities() throws Exception
	{
		DreamChasersPanel panel = newPanel(new Calls());
		panel.setEnableAllRefused(true);
		flush();
		assertTrue("refused beats Connect off", statusText(panel).contains(DreamChasersPanel.ENABLE_ALL_REFUSED));
		assertFalse(statusText(panel).contains(DreamChasersPanel.CONNECT_OFF));
		assertTrue(DreamChasersPanel.ENABLE_ALL_REFUSED.contains("Sign in from the menu at the top right"));

		panel.verifyDone("Turn on Connect to dream-chasers.cc in the plugin settings first", true);
		flush();
		assertTrue("a verify message beats both", statusText(panel).contains("plugin settings first"));
		panel.setConnected(true);
		panel.setVerifyWaiting("AB12", null);
		flush();
		assertTrue("the wait beats everything", statusText(panel).contains("AB12"));
		panel.verifyDone(null, false);
		flush();
		assertTrue("back to the refusal", statusText(panel).contains("turned itself off"));

		panel.setSignedInAs("Tadeko");
		panel.showCompetition(new ApiResult<>(ApiResult.Status.ERROR, null, null, "down"));
		flush();
		assertTrue("signing in clears the refusal; the refresh failure is last", statusText(panel).contains("Couldn't refresh just now"));
		panel.setConnected(false);
		flush();
		assertEquals("signed in with Connect off: nothing to say", "", statusText(panel));
	}

	@Test
	public void theVerifiedMessageSurvivesTheFoldThatFollows() throws Exception
	{
		DreamChasersPanel panel = newPanel(new Calls());
		panel.setConnected(true);
		panel.setOwnClan(null, "Tadeko");
		String verified = "Verified: Tadeko is linked to the Discord account maho. Your Discord role arrives within about a minute once you're in the Dream Chasers Discord."
			+ " Not your Discord account? Press Sign out in the player menu (top right) and tell an officer.";
		panel.verifiedDone(verified);
		panel.setSignedInAs("Maho z h");
		panel.setVerifiedAs("Tadeko");
		flush();
		assertTrue("the fold after the verify keeps its answer", statusText(panel).contains("Not your Discord account?"));
		assertEquals("~Verified as Tadeko", menuLabels(panel).get(0));
		snapshot(panel, "verified");

		// another account, then back: a later fold clears it
		panel.setVerifiedAs(null);
		panel.setVerifiedAs("Fred525");
		flush();
		assertFalse(statusText(panel).contains("Not your Discord account?"));

		panel.verifiedDone(verified);
		panel.setSignedInAs(null);
		flush();
		assertFalse("signing out clears it", statusText(panel).contains("Not your Discord account?"));

		panel.verifyDone("You pressed Deny in the browser, so nothing was linked.", true);
		panel.setVerifiedAs("Tadeko");
		flush();
		assertFalse("an error is cleared by the fold", statusText(panel).contains("pressed Deny"));

		// a plain (not error) answer that isn't the verify's own success goes with the fold too
		panel.setSignedInAs("Maho z h");
		panel.setVerifiedAs(null);
		panel.verifyDone("Your name isn't on the clan roster yet. Watch dream-chasers.cc/join.", false);
		flush();
		assertTrue(statusText(panel).contains("on the clan roster yet"));
		panel.setVerifiedAs("Tadeko");     // an officer verified them; the next refresh folds
		flush();
		assertFalse("the roster-not-yet answer doesn't outlive the fold", statusText(panel).contains("on the clan roster yet"));
		assertEquals("~Verified as Tadeko", menuLabels(panel).get(0));
	}

	@Test
	public void aBlockedConnectTickSaysWhyInTheStatusLine() throws Exception
	{
		DreamChasersPanel panel = newPanel(new Calls());
		panel.setConnected(true);
		String blocker = "Your clan in game isn't DRM Chasers. Join DRM Chasers first";
		panel.setOwnClan(blocker, null);
		flush();
		assertEquals("signed out with Connect on: nothing until a tick of Connect is blocked", "", statusText(panel));
		panel.showSignInBlocked(blocker);
		flush();
		assertTrue(statusText(panel), statusText(panel).contains("Join DRM Chasers first."));
		assertEquals(Brand.WARN, ((JLabel) named(panel, "status")).getForeground());
		assertTrue("the menu says it too, wrapped", String.join(" ", menuLabels(panel)).replace("~", "").contains(blocker));

		panel.setOwnClan(null, "Tadeko");     // joined DRM Chasers in game: the reason no longer holds
		flush();
		assertFalse(statusText(panel).contains("Join DRM Chasers first"));

		panel.setOwnClan(blocker, null);
		panel.showSignInBlocked(blocker);
		panel.setVerifyWaiting("AB12", null);     // a new attempt from the menu
		flush();
		assertFalse(statusText(panel).contains("Join DRM Chasers first"));
		panel.verifyDone(null, false);
		flush();
		assertFalse("the new attempt cleared it", statusText(panel).contains("Join DRM Chasers first"));
	}

	@Test
	public void whyTheSiteSignedTheMemberOutIsInTheStatusLine() throws Exception
	{
		DreamChasersPanel panel = newPanel(new Calls());
		panel.setConnected(true);
		panel.showEvents(new ApiResult<>(ApiResult.Status.UNAUTHORIZED, null, null, "Not signed in"));
		flush();
		assertEquals("plain signed out: nothing to say", "", statusText(panel));

		panel.showEvents(new ApiResult<>(ApiResult.Status.UNAUTHORIZED, null, null, "Your sign-in was revoked by an officer"));
		flush();
		assertTrue(statusText(panel), statusText(panel).contains("Your sign-in was revoked by an officer."));
		assertEquals(Brand.WARN, ((JLabel) named(panel, "status")).getForeground());

		panel.setEnableAllRefused(true);
		flush();
		assertTrue("Enable everything's refusal comes first", statusText(panel).contains(DreamChasersPanel.ENABLE_ALL_REFUSED));
		panel.setSignedInAs("Tadeko");
		panel.showEvents(new ApiResult<>(ApiResult.Status.OK, new EventsResponse(), NOW, null));
		flush();
		assertFalse("signed in again: gone", statusText(panel).contains("revoked"));
	}

	@Test
	public void aSecondLiveEventShowsAsLiveNotComingUp() throws Exception
	{
		DreamChasersPanel panel = newPanel(new Calls());
		panel.setConnected(true);
		panel.setSignedInAs("Maho z h");
		// NOW is Mon 2 Nov 17:00 UTC: the weekend's boss and the month's skill are both on, the week's not yet
		String json = "{\"events\":["
			+ "{\"id\":\"b\",\"title\":\"Boss of the Weekend\",\"startsAt\":\"2026-11-02T12:00:00Z\",\"endsAt\":\"2026-11-03T12:00:00Z\"},"
			+ "{\"id\":\"s\",\"title\":\"Skill of the Month\",\"startsAt\":\"2026-11-01T18:00:00Z\",\"endsAt\":\"2026-12-01T18:00:00Z\"},"
			+ "{\"id\":\"w\",\"title\":\"Skill of the Week\",\"startsAt\":\"2026-11-09T19:00:00Z\",\"endsAt\":\"2026-11-16T19:00:00Z\"}]}";
		panel.showEvents(new ApiResult<>(ApiResult.Status.OK, GSON.fromJson(json, EventsResponse.class), NOW, null));
		flush();
		String text = allText(panel);
		int boss = text.indexOf("Boss of the Weekend"), month = text.indexOf("Skill of the Month"), coming = text.indexOf("Coming up");
		assertTrue("the weekend leads", boss >= 0 && boss < month);
		assertTrue("the month is live too, not coming up", month < coming && text.indexOf("LIVE NOW", month) > month
			&& text.indexOf("LIVE NOW", month) < coming);
		assertTrue("the week is coming up", text.indexOf("Skill of the Week") > coming);
	}

	@Test
	public void eventsHaveNoSignOut() throws Exception
	{
		DreamChasersPanel panel = newPanel(new Calls());
		panel.setConnected(true);
		panel.setSignedInAs("Maho z h");
		panel.showEvents(ok("events.json", EventsResponse.class));
		flush();
		Container events = (Container) named(panel, "section:events");
		String text = allText(events);
		assertTrue(text, text.contains("[Events page]"));
		assertFalse(text, text.contains("Sign out"));
		assertFalse(text, text.contains("Signed in as"));
	}

	@Test
	public void forbiddenExplainsHowToFixMembership() throws Exception
	{
		DreamChasersPanel panel = newPanel(new Calls());
		panel.setConnected(true);
		panel.setSignedInAs("Someone");
		panel.showEvents(new ApiResult<>(ApiResult.Status.FORBIDDEN, null, null, "Not a clan member"));
		flush();

		assertTrue(allText(panel).contains("#rsn"));
	}

	@Test
	public void theSplashIsLastAndFullWidthInRuneLitesOwnScrollPane() throws Exception
	{
		DreamChasersPanel panel = newPanel(new Calls());
		panel.setConnected(true);
		panel.setSignedInAs("Maho z h");
		panel.showFeatured(new ApiResult<>(ApiResult.Status.OK, GSON.fromJson(
			"{\"rsn\":\"Vorki\",\"panelUrl\":\"https://dream-chasers.cc/s/v.png\"}", cc.dreamchasers.clan.api.FeaturedResponse.class), NOW, null));
		panel.showFeaturedImage("Vorki", new BufferedImage(450, 253, BufferedImage.TYPE_INT_RGB));
		flush();
		Component splash = named(panel, "section:splash");
		assertEquals("the splash is the panel's last part", splash,
			((java.awt.BorderLayout) panel.getLayout()).getLayoutComponent(java.awt.BorderLayout.SOUTH));

		Component img = named(panel, "splashImage");
		javax.swing.JPanel outer = panel.getWrappedPanel();
		javax.swing.JScrollPane scroll = find(outer, javax.swing.JScrollPane.class);
		assertEquals("RuneLite's own scroll bar policy", javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			scroll.getVerticalScrollBarPolicy());

		// PluginPanel's scroll pane is left as RuneLite made it: its view is still RuneLite's
		// own wrapper, with this panel at the top of it
		assertTrue("RuneLite's view, not ours", scroll.getViewport().getView() != panel
			&& scroll.getViewport().getView() == panel.getParent());

		// a tall sidebar (no scroll bar, 242 wide): the panel keeps its own height and the
		// splash comes straight after the last section
		layOut(outer, 2000);
		assertFalse("no scroll bar", scroll.getVerticalScrollBar().isVisible());
		assertEquals("the viewport's full width", scroll.getViewport().getWidth(), panel.getWidth());
		int wideWidth = panel.getWidth();
		assertEquals("its own height, not stretched", panel.getPreferredSize().height, panel.getHeight());
		assertEquals("the splash ends the panel", panel.getHeight(), splash.getY() + splash.getHeight());
		assertEquals("full width", panel.getWidth(), splash.getWidth());
		assertEquals("the image is edge to edge", panel.getWidth(), img.getWidth());
		assertEquals("its height is the one made for 225 wide", Math.round(253 * 225f / 450), img.getHeight());
		java.awt.Dimension wide = panel.getPreferredSize();

		// a short sidebar (scroll bar, 225 wide): the content keeps its own height and scrolls, as before
		layOut(outer, 200);
		assertTrue("a scroll bar", scroll.getVerticalScrollBar().isVisible());
		assertTrue("the scroll bar takes its width", panel.getWidth() < wideWidth);
		assertEquals(panel.getPreferredSize().height, panel.getHeight());
		assertEquals(panel.getHeight(), splash.getY() + splash.getHeight());
		assertEquals("the same height at either width", Math.round(253 * 225f / 450), img.getHeight());
		assertEquals("the preferred height doesn't follow the width", wide.height, panel.getPreferredSize().height);
	}

	@Test
	public void aLayoutPassNeverChangesPreferredSizes() throws Exception
	{
		DreamChasersPanel panel = newPanel(new Calls());
		panel.setConnected(true);
		panel.setSignedInAs("Maho z h");
		panel.showFeatured(new ApiResult<>(ApiResult.Status.OK, GSON.fromJson(
			"{\"rsn\":\"Vorki\",\"panelUrl\":\"https://dream-chasers.cc/s/v.png\"}", cc.dreamchasers.clan.api.FeaturedResponse.class), NOW, null));
		panel.showFeaturedImage("Vorki", new BufferedImage(450, 253, BufferedImage.TYPE_INT_RGB));
		flush();
		DreamChasersPanel.SplashImage img = (DreamChasersPanel.SplashImage) named(panel, "splashImage");
		javax.swing.JPanel outer = panel.getWrappedPanel();
		layOut(outer, 4000);
		java.awt.Dimension pref = panel.getPreferredSize();
		java.awt.Dimension imgPref = img.getPreferredSize();
		// every sidebar height around the content's own, where the scroll bar comes and goes
		for (int h = pref.height - 20; h <= pref.height + 20; h++)
		{
			layOut(outer, h);
			int width = panel.getWidth();
			java.awt.Rectangle bounds = panel.getBounds();
			java.awt.Rectangle splashBounds = named(panel, "section:splash").getBounds();
			layOut(outer, h);
			assertEquals("settled at " + h, bounds, panel.getBounds());
			assertEquals("splash settled at " + h, splashBounds, named(panel, "section:splash").getBounds());
			assertEquals("width settled at " + h, width, panel.getWidth());
			assertEquals("panel preferred size at " + h + " (" + width + " wide)", pref, panel.getPreferredSize());
			assertEquals("splash image preferred size at " + h, imgPref, img.getPreferredSize());
			assertEquals("the splash ends at the panel's bottom at " + h, panel.getHeight(), splashBounds.y + splashBounds.height);
		}
	}

	/** Sizes the sidebar and lays it out top down, as one validate pass in the client does. */
	private static void layOut(javax.swing.JPanel outer, int height) throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			outer.setSize(242, height);
			layoutAll(outer);
		});
		flush();
	}

	@Test
	public void everySectionFoldsFromItsTitleAndRemembers() throws Exception
	{
		Calls calls = new Calls();
		DreamChasersPanel panel = newPanel(calls);
		panel.setConnected(true);
		panel.showCompetition(ok("competition.json", CompetitionResponse.class));
		flush();
		assertTrue(allText(panel).contains("Skill of the Month - Fishing"));

		SwingUtilities.invokeAndWait(() -> panel.toggleSection("competition"));
		flush();
		String text = allText(panel);
		assertTrue("the title stays", text.contains("COMPETITION"));
		assertFalse("the body goes", text.contains("Skill of the Month - Fishing"));
		assertEquals("collapsed competition", calls.log.get(calls.log.size() - 1));
		snapshot(panel, "collapsed");

		// saved, then restored on the next start
		DreamChasersPanel again = newPanel(new Calls());
		again.setConnected(true);
		again.showCompetition(ok("competition.json", CompetitionResponse.class));
		again.setCollapsed(" competition ,");
		flush();
		assertFalse(allText(again).contains("Skill of the Month - Fishing"));

		SwingUtilities.invokeAndWait(() -> panel.toggleSection("competition"));
		flush();
		assertTrue(allText(panel).contains("Skill of the Month - Fishing"));
		assertEquals("collapsed ", calls.log.get(calls.log.size() - 1));
	}

	@Test
	public void clogHintShowsOnlyWhenAsked() throws Exception
	{
		DreamChasersPanel panel = newPanel(new Calls());
		flush();
		assertFalse(allText(panel).contains("New addition notification"));
		panel.setClogHint(true);
		flush();
		assertTrue(allText(panel).contains("Collection log - New addition notification"));
		panel.setClogHint(false);
		flush();
		assertFalse(allText(panel).contains("New addition notification"));
	}

	@Test
	public void enableEverythingOnlyAfterSignIn() throws Exception
	{
		Calls calls = new Calls();
		DreamChasersPanel panel = newPanel(calls);
		panel.setConnected(true);
		panel.setAllOn(false);
		flush();
		assertFalse("hidden before sign-in", allText(panel).contains("[Enable everything]") && visibleButton(panel, "Enable everything"));
		panel.setSignedInAs("Maho z h");
		flush();
		assertTrue("appears after sign-in", visibleButton(panel, "Enable everything"));
		assertTrue(allText(panel).contains("3rd-party server"));
		click(panel, "Enable everything");
		assertEquals("enableAll", calls.log.get(calls.log.size() - 1));
		panel.setAllOn(true);
		flush();
		assertFalse("hidden once everything is on", visibleButton(panel, "Enable everything"));
		panel.setAllOn(false);
		panel.setSignedInAs(null);
		flush();
		assertFalse("hidden again after sign-out", visibleButton(panel, "Enable everything"));
	}

	private static boolean visibleButton(Container root, String label) throws Exception
	{
		boolean[] out = new boolean[1];
		SwingUtilities.invokeAndWait(() -> out[0] = findButton(root, label) != null);
		return out[0];
	}

	@Test
	public void rosterSyncIsALineInThePlayerMenuNotTheSidebar() throws Exception
	{
		DreamChasersPanel panel = newPanel(new Calls());
		panel.setConnected(true);
		panel.setSignedInAs("Tadeko");
		flush();
		assertFalse(menuLabels(panel).stream().anyMatch(l -> l.contains("Roster")));
		panel.setRosterSync(true);
		flush();
		assertFalse("never in the sidebar", allText(panel).contains("Roster"));
		assertTrue("one short line, never the site's message", menuLabels(panel).contains("~Roster sync is on"));
		panel.setRosterSync(false);
		flush();
		assertTrue(menuLabels(panel).contains("~Roster sync is off"));
		assertMenuFits(panel);
		panel.setRosterSync(null);
		flush();
		assertFalse(menuLabels(panel).stream().anyMatch(l -> l.contains("Roster")));
	}

	private static final String LIVE_JSON = "{\"apiVersion\":1,\"consent\":{\"version\":\"2026-11-01\",\"title\":\"Before you go on stream\","
		+ "\"lines\":[\"Your game screen and your voice go out live.\",\"Never type your bank PIN.\"]},"
		+ "\"consented\":%s,\"consentedAt\":null,\"status\":{\"show\":\"open\",\"slotsFree\":3,\"voiceUrl\":\"https://discord.com/channels/1/2\"}}";

	@Test
	public void liveAsksForConsentThenJoins() throws Exception
	{
		Calls calls = new Calls();
		DreamChasersPanel panel = newPanel(calls);
		panel.setConnected(true);
		panel.setSignedInAs("Maho z h");
		flush();
		assertTrue("off until the setting is on", allText(panel).contains("Turn on <b>Join DRM Live</b>"));

		panel.setLiveEnabled(true);
		panel.showLive(new ApiResult<>(ApiResult.Status.OK,
			GSON.fromJson(String.format(LIVE_JSON, "false"), cc.dreamchasers.clan.api.LiveResponse.class), NOW, null));
		flush();
		String text = allText(panel);
		assertTrue(text.contains("Open for guests"));
		assertTrue(text.contains("bank PIN"));
		assertFalse("no join before consent", text.contains("[Join the stream]"));
		snapshot(panel, "live-consent");
		click(panel, "Agree");
		assertEquals("liveAgree 2026-11-01", calls.log.get(calls.log.size() - 1));

		panel.liveDone(null, true, null, false);
		panel.showLive(new ApiResult<>(ApiResult.Status.OK,
			GSON.fromJson(String.format(LIVE_JSON, "true"), cc.dreamchasers.clan.api.LiveResponse.class), NOW, null));
		flush();
		click(panel, "Join the stream");
		assertEquals("liveJoin", calls.log.get(calls.log.size() - 1));

		cc.dreamchasers.clan.api.LiveJoinResponse j = GSON.fromJson(
			"{\"slot\":2,\"pushUrl\":\"https://vdo.ninja/?push=TOKEN&password=PW\"}", cc.dreamchasers.clan.api.LiveJoinResponse.class);
		assertFalse("the push link stays out of toString (and logs)", j.toString().contains("TOKEN"));
		panel.liveDone(j, false, null, false);
		flush();
		text = allText(panel);
		assertTrue(text.contains("slot <b>2</b>"));
		assertTrue("new guests wait on hold", text.contains("Waiting to go on air"));
		assertFalse("the link itself is never printed in the panel", text.contains("TOKEN"));
		assertTrue(text.contains("RuneLite window only"));
		snapshot(panel, "live-joined");
		click(panel, "Open my stream link");
		assertEquals("open https://vdo.ninja/?push=TOKEN&password=PW", calls.log.get(calls.log.size() - 1));
		click(panel, "Join the stream voice channel");
		assertEquals("open https://discord.com/channels/1/2", calls.log.get(calls.log.size() - 1));
		click(panel, "Leave");
		assertEquals("liveLeave", calls.log.get(calls.log.size() - 1));
	}

	@Test
	public void splashShowsTheFeaturedMemberThenAsksBeforeSending() throws Exception
	{
		Calls calls = new Calls();
		DreamChasersPanel panel = newPanel(calls);
		panel.setConnected(true);
		panel.setSignedInAs("Maho z h");
		BufferedImage pic = new BufferedImage(450, 253, BufferedImage.TYPE_INT_RGB);
		panel.showFeatured(new ApiResult<>(ApiResult.Status.OK, GSON.fromJson(
			"{\"rsn\":\"Vorki <i>\",\"panelUrl\":\"https://dream-chasers.cc/s/v.png\"}", cc.dreamchasers.clan.api.FeaturedResponse.class), NOW, null));
		panel.showFeaturedImage("Vorki <i>", pic);
		flush();
		String text = allText(panel);
		assertTrue("the featured member in bold, escaped", text.contains("<b>Vorki &lt;i&gt;</b>"));
		assertFalse(text.contains("Featured:"));
		DreamChasersPanel.SplashImage img = (DreamChasersPanel.SplashImage) named(panel, "splashImage");
		assertEquals("its shape kept when drawn", 113, img.heightFor(201));
		assertEquals("a fixed height, made for RuneLite's panel width", 127, img.getPreferredSize().height);
		assertTrue("off until the setting is on", text.contains("Turn on <b>Clan splash</b>"));
		assertFalse(text.contains("[Feature me on the clan splash]"));

		panel.setSplashEnabled(true);
		flush();
		text = allText(panel);
		assertTrue("consent first", text.contains("gear included") && text.contains("DRM Live stream") && text.contains("Remove me"));
		snapshot(panel, "splash-consent");
		click(panel, "Feature me on the clan splash");
		assertEquals("featureMe", calls.log.get(calls.log.size() - 1));
		assertTrue(allText(panel).contains("Working…"));

		panel.splashDone("Stand still and try again.", true);
		flush();
		assertTrue(allText(panel).contains("Stand still and try again."));

		panel.setSplashOptedIn(true);
		panel.showModelStatus(new ApiResult<>(ApiResult.Status.OK, GSON.fromJson(
			"{\"featured\":{\"rsn\":\"x\",\"status\":\"rejected\",\"reason\":\"arms not found: stand idle and try again\"}}",
			cc.dreamchasers.clan.api.ModelStatusResponse.class), NOW, null));
		panel.splashDone(null, false);
		flush();
		text = allText(panel);
		assertTrue(text.contains("arms not found"));
		assertFalse("no consent once in", text.contains("gear included"));
		assertFalse("the long pool lines are gone", text.contains("in the pool"));
		snapshot(panel, "splash-rejected");
		clickNamed(panel, "splashRemove");
		assertEquals("removeMe", calls.log.get(calls.log.size() - 1));
		panel.splashDone(null, false);
		flush();
		clickNamed(panel, "splashResend");
		assertEquals("featureMe", calls.log.get(calls.log.size() - 1));
		panel.splashDone(null, false);
		flush();
		clickNamed(panel, "splashView");
		assertEquals("the eye opens the member's own profile", "open https://dream-chasers.cc/clan/x", calls.log.get(calls.log.size() - 1));

		panel.splashDone(null, false);
		panel.setSplashEnabled(false);
		flush();
		assertTrue("Remove me stays with the setting off", findNamed(panel, "splashRemove") != null);
		assertTrue(findNamed(panel, "splashResend") == null);
		assertTrue(findNamed(panel, "splashView") != null);
		assertTrue("no refresh icon: the rejected line says how to send a new pose",
			allText(panel).contains("Turn on <b>Clan splash</b> in this plugin's settings to send a new pose."));

		panel.showModelStatus(new ApiResult<>(ApiResult.Status.OK, GSON.fromJson(
			"{\"featured\":{\"rsn\":\"x\",\"status\":\"rejected\"}}",
			cc.dreamchasers.clan.api.ModelStatusResponse.class), NOW, null));
		flush();
		text = allText(panel);
		assertTrue(text.contains("Your last pose couldn't be used. Turn on <b>Clan splash</b>"));
		assertFalse("never asks for a pose there's no button for", text.contains("Stand still in game and send a new pose."));
	}

	@Test
	public void splashIsHiddenUntilConnected() throws Exception
	{
		DreamChasersPanel panel = newPanel(new Calls());
		flush();
		assertFalse(allText(panel).contains("CLAN SPLASH"));
	}

	@Test
	public void tilePacksCopyTheGroundMarkersFormat() throws Exception
	{
		DreamChasersPanel panel = newPanel(new Calls());
		panel.setConnected(true);
		flush();
		assertFalse("off by default", allText(panel).contains("TILE PACKS"));
		panel.setTilesEnabled(true);
		String markers = "[{\"regionId\":12613,\"regionX\":32,\"regionY\":20,\"z\":0,\"color\":\"#FFFF0000\",\"label\":\"Stack\"}]";
		panel.showTiles(new ApiResult<>(ApiResult.Status.OK, GSON.fromJson(
			"{\"packs\":[{\"id\":\"abc\",\"name\":\"ToB Maiden\",\"description\":\"Stack <b>tiles</b>\",\"count\":1,\"markers\":" + markers + "}]}",
			cc.dreamchasers.clan.api.TilesResponse.class), NOW, null));
		flush();
		String text = allText(panel);
		assertTrue(text.contains("TILE PACKS"));
		assertTrue(text.contains("ToB Maiden"));
		assertTrue("site text is escaped", text.contains("&lt;b&gt;tiles"));
		snapshot(panel, "tiles");
		click(panel, "Copy");
		text = allText(panel);
		assertTrue(text.contains("Copied ToB Maiden") || text.contains("use the clipboard just now"));
		if (text.contains("Copied"))
		{
			Object got = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
			assertEquals("exactly the export, for core Ground Markers' Import", markers, got);
		}
		panel.setTilesEnabled(false);
		flush();
		assertFalse(allText(panel).contains("TILE PACKS"));
	}

	/** The first component with this name, depth first; fails if there is none. */
	private static Component named(Container c, String name)
	{
		Component found = findNamed(c, name);
		if (found == null)
		{
			throw new AssertionError("no component named " + name);
		}
		return found;
	}

	private static Component findNamed(Container c, String name)
	{
		for (Component child : c.getComponents())
		{
			if (name.equals(child.getName()))
			{
				return child;
			}
			if (child instanceof Container)
			{
				Component found = findNamed((Container) child, name);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	/** The panel's sections ("section:KEY"), top down. */
	private static List<String> sectionNames(Container c)
	{
		List<String> out = new ArrayList<>();
		for (Component child : c.getComponents())
		{
			if (child.getName() != null && child.getName().startsWith("section:"))
			{
				out.add(child.getName().substring("section:".length()));
			}
			else if (child instanceof Container)
			{
				out.addAll(sectionNames((Container) child));
			}
		}
		return out;
	}

	private static void clickNamed(Container root, String name) throws Exception
	{
		SwingUtilities.invokeAndWait(() -> ((AbstractButton) named(root, name)).doClick());
		flush();
	}

	/** The player menu's items, in order; a greyed-out one starts with "~". Names are never HTML. */
	private static List<String> menuLabels(DreamChasersPanel panel) throws Exception
	{
		List<String> out = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			for (Component c : panel.accountMenu().getComponents())
			{
				if (c instanceof javax.swing.JMenuItem)
				{
					javax.swing.JMenuItem item = (javax.swing.JMenuItem) c;
					assertEquals(Boolean.TRUE, item.getClientProperty("html.disable"));
					out.add((item.isEnabled() ? "" : "~") + item.getText());
				}
			}
		});
		return out;
	}

	/** No menu line is much wider than the panel: the menu opens right-aligned and would spill over the game. */
	private static void assertMenuFits(DreamChasersPanel panel) throws Exception
	{
		for (String l : menuLabels(panel))
		{
			assertTrue("too wide for the menu: " + l, l.replace("~", "").length() <= DreamChasersPanel.MENU_LINE_CHARS);
		}
	}

	@Test
	public void longMenuLinesWrapAtSpaces()
	{
		assertEquals(List.of("Join DRM Chasers in game first, or", "open your clan tab and wait a few", "seconds"),
			DreamChasersPanel.wrapMenuLine(OwnClan.blocker(OwnClan.Reading.UNKNOWN)));
		assertEquals(List.of("Turn on Connect in the plugin settings"), DreamChasersPanel.wrapMenuLine("Turn on Connect in the plugin settings"));
		String word = "x".repeat(DreamChasersPanel.MENU_LINE_CHARS + 3);
		assertEquals(List.of("a", word.substring(0, DreamChasersPanel.MENU_LINE_CHARS), "xxx b"), DreamChasersPanel.wrapMenuLine("a " + word + " b"));
	}

	private static void clickMenu(DreamChasersPanel panel, String label) throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			for (Component c : panel.accountMenu().getComponents())
			{
				if (c instanceof javax.swing.JMenuItem && label.equals(((javax.swing.JMenuItem) c).getText()))
				{
					assertTrue(label + " is greyed out", c.isEnabled());
					((javax.swing.JMenuItem) c).doClick();
					return;
				}
			}
			throw new AssertionError("no menu item " + label);
		});
		flush();
	}

	private static void click(Container root, String label) throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			AbstractButton b = findButton(root, label);
			if (b == null)
			{
				throw new AssertionError("no button " + label + " in:\n" + allText(root));
			}
			b.doClick();
		});
		flush();
	}

	private static AbstractButton findButton(Container c, String label)
	{
		for (Component child : c.getComponents())
		{
			if (child instanceof AbstractButton && label.equals(((AbstractButton) child).getText()) && isVisibleUpTo(child))
			{
				return (AbstractButton) child;
			}
			if (child instanceof Container)
			{
				AbstractButton b = findButton((Container) child, label);
				if (b != null)
				{
					return b;
				}
			}
		}
		return null;
	}

	private static boolean isVisibleUpTo(Component c)
	{
		for (Component x = c; x != null; x = x.getParent())
		{
			if (!x.isVisible())
			{
				return false;
			}
		}
		return true;
	}

	private static <T> T find(Container c, Class<T> type)
	{
		for (Component child : c.getComponents())
		{
			if (type.isInstance(child))
			{
				return type.cast(child);
			}
			if (child instanceof Container)
			{
				T t = find((Container) child, type);
				if (t != null)
				{
					return t;
				}
			}
		}
		return null;
	}
}
