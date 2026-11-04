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
		public void dismissConnect()
		{
			log.add("dismissConnect");
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

	private static DreamChasersPanel newPanel(Calls calls) throws Exception
	{
		BufferedImage mark = ImageUtil.loadImageResource(DreamChasersPanel.class, "/cc/dreamchasers/clan/wordmark.png");
		DreamChasersPanel[] out = new DreamChasersPanel[1];
		SwingUtilities.invokeAndWait(() -> out[0] = new DreamChasersPanel(calls,
			mark, java.time.Clock.fixed(NOW, ZoneId.of("UTC")), ZoneId.of("Europe/London")));
		return out[0];
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
	public void disconnectedAsksForTheOptIn() throws Exception
	{
		DreamChasersPanel panel = newPanel(new Calls());
		flush();

		String text = allText(panel);
		assertTrue(text.contains("CONNECT TO DREAM CHASERS"));
		assertTrue(text.contains("[Connect]") && text.contains("[Not now]") && text.contains("[Privacy]"));
		assertTrue(text.contains("IP address") && text.contains("password"));
		assertFalse(text.contains("Get a code"));
		snapshot(panel, "disconnected");
	}

	@Test
	public void signedOutShowsSiteDataAndTheSignInSteps() throws Exception
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
		assertTrue(text.contains("How to connect"));
		assertTrue(text.contains("[Connect]"));
		assertTrue(text.contains("[Have a code instead?]"));
		assertFalse("the code box waits behind its button", text.contains("[Link]"));
		assertFalse("no Discord button while the site's invite is null", text.contains("Join our Discord"));
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
		assertTrue(text.contains("Signed in as Maho z h"));
		assertTrue(text.contains("Starts in"));
		assertTrue(text.contains("[Join our Discord]"));
		snapshot(panel, "signed-in");
	}

	@Test
	public void siteLinksOpenOnlyToTheirOwnHosts() throws Exception
	{
		Calls calls = new Calls();
		DreamChasersPanel panel = newPanel(calls);
		panel.setConnected(true);
		LinksResponse links = GSON.fromJson(read("links.json"), LinksResponse.class);
		links.setDiscord("https://discord.gg/example");
		panel.showLinks(new ApiResult<>(ApiResult.Status.OK, links, NOW, null));
		flush();
		click(panel, "Join our Discord");
		assertEquals("open https://discord.gg/example", calls.log.get(calls.log.size() - 1));
		click(panel, "dream-chasers.cc");
		assertEquals("open https://dream-chasers.cc/", calls.log.get(calls.log.size() - 1));    // the checked form goes to the browser

		// a site bug hands over an outside page: nothing opens, and the panel says so
		links.setDiscord("https://discord.gg.evil.example/login");
		panel.showLinks(new ApiResult<>(ApiResult.Status.OK, links, NOW, null));
		flush();
		int before = calls.log.size();
		click(panel, "Join our Discord");
		assertEquals(before, calls.log.size());
		assertTrue(allText(panel).contains("didn't look right"));
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
	public void linkButtonPassesTheCode() throws Exception
	{
		Calls calls = new Calls();
		DreamChasersPanel panel = newPanel(calls);
		panel.setConnected(true);
		flush();
		click(panel, "Have a code instead?");
		assertTrue(allText(panel).contains("[Link]"));

		SwingUtilities.invokeAndWait(() ->
		{
			// the account card's field; Verify for Discord's sits above it, greyed out until the clan record loads
			javax.swing.JTextField field = enabledField(panel);
			field.setText("DRM-7KQ4-P2XM");
			field.postActionEvent();
		});
		flush();

		assertEquals("link DRM-7KQ4-P2XM", calls.log.get(calls.log.size() - 1));
		assertTrue(allText(panel).contains("Linking…"));
	}

	@Test
	public void verifyForDiscordIsGatedOnTheOwnClanRecord() throws Exception
	{
		Calls calls = new Calls();
		DreamChasersPanel panel = newPanel(calls);
		assertFalse("hidden until Connect is on", allText(panel).contains("VERIFY FOR DISCORD"));
		panel.setConnected(true);
		flush();
		String text = allText(panel);
		assertTrue(text.contains("VERIFY FOR DISCORD"));
		assertTrue("unknown at first", text.contains("Open your clan tab or wait a few seconds after login."));
		assertEquals("greyed out", null, enabledField(panel));

		panel.setOwnClan("Join the clan in game first", null);
		flush();
		assertTrue(allText(panel).contains("Join the clan in game first."));
		assertFalse(findButton(panel, "Verify").isEnabled());

		panel.setOwnClan(null, "Tadeko");
		flush();
		assertFalse(allText(panel).contains("Join the clan in game first."));
		assertTrue(findButton(panel, "Verify").isEnabled());
		snapshot(panel, "verify");
	}

	@Test
	public void verifyForDiscordAsksBeforeLinking() throws Exception
	{
		Calls calls = new Calls();
		DreamChasersPanel panel = newPanel(calls);
		panel.setConnected(true);
		panel.setOwnClan(null, "Tadeko");
		flush();
		SwingUtilities.invokeAndWait(() ->
		{
			javax.swing.JTextField field = enabledField(panel);
			field.setText("abcd 2345");
			field.postActionEvent();
		});
		flush();
		assertEquals("verifyPeek abcd 2345", calls.log.get(calls.log.size() - 1));
		assertTrue(allText(panel).contains("Checking the code…"));

		panel.verifyAsk("Tadeko", "Maho <b>");
		flush();
		String text = allText(panel);
		assertTrue(text, text.contains("This links <b>Tadeko</b> to Discord <b>Maho &lt;b&gt;</b>. "
			+ "Only continue if that Discord account is yours. If someone sent you this code, press Cancel."));
		snapshot(panel, "verify-confirm");
		click(panel, "Confirm");
		assertEquals("verifyConfirm", calls.log.get(calls.log.size() - 1));
		assertTrue(allText(panel).contains("Linking…"));

		panel.verifyDone("Verified: Tadeko is linked to your Discord.", false);
		flush();
		assertTrue(allText(panel).contains("Verified: Tadeko is linked to your Discord."));

		panel.verifyAsk("Tadeko", "Someone");
		flush();
		click(panel, "Cancel");
		assertEquals("verifyCancel", calls.log.get(calls.log.size() - 1));
		assertTrue(allText(panel).contains("Cancelled. Nothing was linked."));
	}

	@Test
	public void verifyForDiscordFoldsOnceSignedIn() throws Exception
	{
		DreamChasersPanel panel = newPanel(new Calls());
		panel.setConnected(true);
		panel.setSignedInAs("Tadeko");
		panel.setOwnClan(null, "Tadeko");
		flush();
		assertFalse(allText(panel).contains("VERIFY FOR DISCORD"));
		click(panel, "Verify for Discord…");
		assertTrue(allText(panel).contains("VERIFY FOR DISCORD"));
		click(panel, "Hide");
		assertFalse(allText(panel).contains("VERIFY FOR DISCORD"));
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
	public void consentCardConnectsOrStepsAside() throws Exception
	{
		Calls calls = new Calls();
		DreamChasersPanel panel = newPanel(calls);
		flush();
		click(panel, "Connect");
		assertEquals("connect", calls.log.get(calls.log.size() - 1));
		click(panel, "Not now");
		assertEquals("dismissConnect", calls.log.get(calls.log.size() - 1));

		panel.setConnectDismissed(true);
		flush();
		String text = allText(panel);
		assertTrue(text.contains("NOT CONNECTED"));
		assertFalse("the card folds away", text.contains("IP address"));
		click(panel, "Connect…");
		assertTrue("Connect… opens the card again", allText(panel).contains("IP address"));
	}

	@Test
	public void waitingShowsTheCodeAndCancels() throws Exception
	{
		Calls calls = new Calls();
		DreamChasersPanel panel = newPanel(calls);
		panel.setConnected(true);
		panel.setConnectWaiting("K7QX", null);
		flush();
		String text = allText(panel);
		assertTrue(text.contains("Waiting for you to approve in the browser"));
		assertTrue(text.contains("K7QX"));
		snapshot(panel, "connect-waiting");
		click(panel, "Cancel");
		assertEquals("cancelConnect", calls.log.get(calls.log.size() - 1));

		panel.setConnectWaiting(null, "You pressed Deny in the browser, so nothing was linked.");
		flush();
		assertTrue(allText(panel).contains("pressed Deny"));

		panel.setSignedInAs("Maho z h");
		flush();
		assertFalse("the card goes once signed in", allText(panel).contains("CONNECT TO DREAM CHASERS"));
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
		assertTrue(text.contains("Featured: <b>Vorki &lt;i&gt;</b>"));
		JLabel img = findIcon(panel);
		assertEquals("fits inside the section", 193, img.getIcon().getIconWidth());
		assertEquals(108, img.getIcon().getIconHeight());
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
		snapshot(panel, "splash-rejected");
		click(panel, "Remove me");
		assertEquals("removeMe", calls.log.get(calls.log.size() - 1));

		panel.splashDone(null, false);
		panel.setSplashEnabled(false);
		flush();
		assertTrue("Remove me stays with the setting off", findButton(panel, "Remove me") != null);
		assertTrue(findButton(panel, "Send a new pose") == null);
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

	private static JLabel findIcon(Container c)
	{
		for (Component child : c.getComponents())
		{
			if (child instanceof JLabel && ((JLabel) child).getIcon() != null && ((JLabel) child).getIcon().getIconWidth() == DreamChasersPanel.SPLASH_WIDTH)
			{
				return (JLabel) child;
			}
			if (child instanceof Container)
			{
				JLabel l = findIcon((Container) child);
				if (l != null)
				{
					return l;
				}
			}
		}
		return null;
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

	private static javax.swing.JTextField enabledField(Component c)
	{
		if (c instanceof javax.swing.JTextField && c.isEnabled() && isVisibleUpTo(c))
		{
			return (javax.swing.JTextField) c;
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				javax.swing.JTextField f = enabledField(child);
				if (f != null)
				{
					return f;
				}
			}
		}
		return null;
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
