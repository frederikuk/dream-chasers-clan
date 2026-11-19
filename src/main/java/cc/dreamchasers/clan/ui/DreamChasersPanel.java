/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.ui;

import cc.dreamchasers.clan.api.ApiResult;
import cc.dreamchasers.clan.api.Competition;
import cc.dreamchasers.clan.api.CompetitionPart;
import cc.dreamchasers.clan.api.CompetitionResponse;
import cc.dreamchasers.clan.api.EventInfo;
import cc.dreamchasers.clan.api.EventsResponse;
import cc.dreamchasers.clan.api.FeaturedResponse;
import cc.dreamchasers.clan.api.HallOfFameDigest;
import cc.dreamchasers.clan.api.HallOfFameResponse;
import cc.dreamchasers.clan.api.LinksResponse;
import cc.dreamchasers.clan.api.TilesResponse;
import cc.dreamchasers.clan.api.LiveJoinResponse;
import cc.dreamchasers.clan.api.LiveResponse;
import cc.dreamchasers.clan.api.ModelStatusResponse;
import cc.dreamchasers.clan.api.Standing;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseEvent;
import java.awt.event.MouseAdapter;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.ui.PluginPanel;
import static cc.dreamchasers.clan.ui.Widgets.*;

/**
 * The sidebar panel. Every public method may be called from any thread; it
 * hops onto the Swing thread itself.
 */
public class DreamChasersPanel extends PluginPanel
{
	public static final String SITE = "https://dream-chasers.cc";
	private static final int STANDINGS_SHOWN = 10;
	private static final int FIRSTS_SHOWN = 8;
	private static final int UPCOMING_SHOWN = 6;
	/** "Enable everything" was ticked before signing in, so it turned itself off. */
	static final String ENABLE_ALL_REFUSED = "<b>Enable everything</b> needs you signed in first, so it turned itself off. "
		+ "Sign in from the menu at the top right, then close and reopen the settings and tick it again.";
	/** The status line while Connect is off and nobody is signed in. */
	static final String CONNECT_OFF = "Turn on Connect to dream-chasers.cc in the plugin settings.";
	/** Used when the live server sends no steps of its own. */
	static final List<String> DEFAULT_STEPS = List.of(
		"Press Open my stream link. In the browser press Start, and pick the RuneLite window only, not your whole screen.",
		"Press Join the stream voice channel. Talk in Discord only; leave the browser's microphone off.",
		"Don't share system or tab audio: it echoes.",
		"On a phone? Open the stream link in the phone's browser instead, or ask a producer.");
	/** Shown before the first Feature me; the README's "Clan splash" section says the same. */
	static final String SPLASH_CONSENT = "<b>Be the statue on the clan splash</b><br>"
		+ "&bull; The plugin sends your character's 3D shape, gear included, to dream-chasers.cc. Nothing else, and no one else's.<br>"
		+ "&bull; It becomes the dragonstone statue on the splash, with your RSN. The clan sees it in this panel and on the site, and it may be shown on the DRM Live stream.<br>"
		+ "&bull; The same model is your profile picture and 3D view on your clan profile on the site.<br>"
		+ "&bull; Press the <b>x</b> in the splash row (Remove me from the clan splash) at any time: the site deletes your model and splash, and your profile stops using it.<br>"
		+ "Stand still in game, then press the button.";
	/** The player button in the header's top right corner, like the site's account icon. */
	static final int PLAYER_SIZE = 30;
	/**
	 * The content sections, top down, under the hint lines (Enable
	 * everything, the collection log hint). The clan splash is not in it: it sits under them all, at the
	 * bottom of the panel, as wide as the panel.
	 */
	static final List<String> SECTION_ORDER = List.of("events", "competition", "halloffame", "tiles", "live");

	public interface Listener
	{
		/** The panel became visible (or the plugin wants fresh data); {@code force} skips the cache. */
		void refresh(boolean force);

		void signOut();

		/** The player menu's "Sign in with Discord" / "Verify NAME for Discord": the one-click verify, which signs in too. */
		default void verifyStart()
		{
		}

		/** The player menu's Cancel while the browser approval waits; no more polls are sent. */
		default void verifyStop()
		{
		}

		/** The player menu's "Enter a code from the site…": a join code from dream-chasers.cc/join. */
		default void verifyPeek(String code)
		{
		}

		/** Yes on "This links RSN to Discord NAME". */
		default void verifyConfirm()
		{
		}

		/** No on "This links RSN to Discord NAME"; nothing more is sent. */
		default void verifyCancel()
		{
		}

		/** DRM Live: agree to the stream consent text of this version. */
		void liveAgree(String version);

		void liveJoin();

		void liveLeave();

		void liveWithdraw();

		/** Clan splash: capture the standing player and send the model. */
		void featureMe();

		/** Clan splash: leave the pool; the site deletes the model and splash. */
		void removeMe();

		/** The "Enable everything" line (shown only once signed in): ticks that setting. */
		default void enableAll()
		{
		}

		/** A section was folded or unfolded: the folded ones, comma-separated, to keep across restarts. */
		default void collapsedChanged(String keys)
		{
		}

		/** Opens a link the site gave us in the browser; the panel has checked it with {@link SafeLinks}. */
		default void open(String url)
		{
			SafeLinks.browse(url);
		}
	}

	private final Listener listener;
	private final Clock clock;
	private final ZoneId zone;
	private final SafeLinks safeLinks;

	/** The one status line under the header: the sign-in wait or answer, a hint, or a refresh failure. */
	private final JLabel status = new JLabel();
	private final JPanel everything = section();
	private final JPanel clogHint = section();
	private final JPanel live = section();
	/** Full width, under the last section: no side padding, its parts pad themselves. */
	private final JPanel splash = new JPanel();
	private final JPanel events = section();
	private final JPanel competition = section();
	private final JPanel hallOfFame = section();
	private final JPanel tiles = section();
	/** Top right, always: the player menu (who, sign in or verify, a code from the site, the links, Sign out). */
	private final JButton player = new JButton(new PlayerIcon(PLAYER_SIZE));
	private final Timer ticker;

	private boolean connected;
	/** Every feature setting is on (then the Enable everything line hides). */
	private boolean allOn;
	private String signedInAs;
	private ApiResult<EventsResponse> eventsResult;
	private ApiResult<CompetitionResponse> competitionResult;
	private ApiResult<HallOfFameResponse> hallOfFameResult;
	private ApiResult<LinksResponse> linksResult;
	/** Officer roster sync, shown greyed out in the player menu as on or off; null leaves it out. */
	private Boolean rosterOn;
	// sign in / verify for Discord (verified join), from the player menu
	private String verifyBlocker = cc.dreamchasers.clan.OwnClan.LOGGED_OUT_BLOCKER;
	private String verifyRsn;
	/** The logged-in name when the site has verified it to this member: the menu offers no verify then. */
	private String verifiedAs;
	/** "Enable everything" was ticked before signing in (so it unticked itself): the status line says why. */
	private boolean enableAllRefused;
	/** Sections the member folded (by key); only their title row shows. */
	private final Set<String> collapsed = new LinkedHashSet<>();
	/** A sign-in, verify or code is on its way to the site: no second one from the menu. */
	private boolean verifyBusy;
	/** The last answer of a sign-in or verify, for the status line; null for none. */
	private String verifyMessage;
	private boolean verifyMessageIsError;
	/** The answer is a finished verify's: the fold to verified that follows it keeps it on screen. */
	private boolean verifyMessageOutlivesFold;
	/** The message is why ticking Connect couldn't sign in (the clan record's blocker): it goes when the record changes. */
	private boolean verifyMessageIsBlocker;
	/** The browser approval is waiting: the code the site's page shows too; null when not waiting. */
	private String verifyWaitCode;
	private String verifyWaitHint;
	// DRM Live
	private boolean liveEnabled;
	private ApiResult<LiveResponse> liveResult;
	private LiveJoinResponse liveJoin;
	private boolean liveBusy;
	private String liveMessage;
	private boolean liveMessageIsError;
	// clan splash
	private boolean splashEnabled;
	private boolean splashOptedIn;
	private ApiResult<FeaturedResponse> featuredResult;
	private String featuredImageRsn;
	private SplashImage featuredImage;
	private ApiResult<ModelStatusResponse> modelStatus;
	private boolean splashBusy;
	private String splashMessage;
	private boolean splashMessageIsError;
	// clan tile packs
	private boolean tilesEnabled;
	private ApiResult<TilesResponse> tilesResult;
	private String tilesMessage;

	public DreamChasersPanel(Listener listener, BufferedImage mark, Clock clock, ZoneId zone)
	{
		this(listener, mark, clock, zone, new SafeLinks(SITE));
	}

	public DreamChasersPanel(Listener listener, BufferedImage mark, Clock clock, ZoneId zone, SafeLinks safeLinks)
	{
		super(true);
		this.listener = listener;
		this.clock = clock;
		this.zone = zone;
		this.safeLinks = safeLinks;

		setBackground(Brand.VOID);
		setBorder(new EmptyBorder(0, 0, 0, 0));
		setLayout(new BorderLayout());

		// everything but the splash, top down; the splash goes under it, at the bottom
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setBorder(new EmptyBorder(8, 8, 0, 8));
		content.add(header(mark));
		status.setName("status");
		status.setForeground(Brand.MUTED);
		status.setFont(FontManager.getRunescapeSmallFont());
		status.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(status);
		content.add(Box.createVerticalStrut(6));
		everything.setName("section:everything");
		clogHint.setName("section:clogHint");
		events.setName("section:events");
		competition.setName("section:competition");
		hallOfFame.setName("section:halloffame");
		tiles.setName("section:tiles");
		live.setName("section:live");
		// the hint lines first (each only when it has something to say), then SECTION_ORDER
		for (JPanel p : new JPanel[]{everything, clogHint, events, competition, hallOfFame, tiles, live})
		{
			content.add(p);
			content.add(Box.createVerticalStrut(8));
		}
		add(content, BorderLayout.NORTH);

		splash.setName("section:splash");
		splash.setLayout(new BoxLayout(splash, BoxLayout.Y_AXIS));
		splash.setBackground(Brand.NIGHT);
		splash.setBorder(BorderFactory.createMatteBorder(2, 0, 0, 0, Brand.GOLD_DEEP));
		splash.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(splash, BorderLayout.SOUTH);

		// Keep "in 2h 15m" and LIVE current without any network traffic.
		ticker = new Timer(30_000, e -> renderEvents());
		ticker.start();
		renderAll();
	}

	@Override
	public void onActivate()
	{
		listener.refresh(false);
	}

	public void dispose()
	{
		ticker.stop();
	}

	// ---- state updates (any thread) ----

	public void setConnected(boolean connected)
	{
		onEdt(() ->
		{
			this.connected = connected;
			if (!connected)
			{
				eventsResult = null;
				competitionResult = null;
				hallOfFameResult = null;
				linksResult = null;
				liveResult = null;
				liveJoin = null;
				modelStatus = null;
				tilesResult = null;
			}
			renderAll();
		});
	}

	/** "Post collection log" is on but the game's new-item notification is off: say so. */
	public void setClogHint(boolean show)
	{
		onEdt(() ->
		{
			clogHint.removeAll();
			clogHint.setVisible(show);
			if (show)
			{
				clogHint.add(text("Turn on <b>Collection log - New addition notification</b> in the game settings, "
					+ "or new collection log slots can't be posted.", Brand.WARN));
			}
			done(clogHint);
		});
	}

	public void setAllOn(boolean allOn)
	{
		onEdt(() ->
		{
			this.allOn = allOn;
			renderEverything();
		});
	}

	public void setSignedInAs(String name)
	{
		onEdt(() ->
		{
			boolean wasSignedIn = signedInAs != null;
			signedInAs = name == null || name.isEmpty() ? null : name;
			if (signedInAs != null)
			{
				enableAllRefused = false;
			}
			if (wasSignedIn && signedInAs == null)
			{
				clearVerifyMessage();     // signed out: the last verify's answer goes with it
			}
			if (signedInAs == null)
			{
				liveResult = null;
				liveJoin = null;
				modelStatus = null;
			}
			renderAll();
		});
	}

	public void showEvents(ApiResult<EventsResponse> result)
	{
		onEdt(() ->
		{
			eventsResult = result;
			renderEvents();
			renderStatus();
		});
	}

	public void showCompetition(ApiResult<CompetitionResponse> result)
	{
		onEdt(() ->
		{
			competitionResult = result;
			renderCompetition();
			renderStatus();
		});
	}

	public void showHallOfFame(ApiResult<HallOfFameResponse> result)
	{
		onEdt(() ->
		{
			hallOfFameResult = result;
			renderHallOfFame();
			renderStatus();
		});
	}

	/** Clan tile packs: the setting is on. */
	public void setTilesEnabled(boolean enabled)
	{
		onEdt(() ->
		{
			tilesEnabled = enabled;
			if (!enabled)
			{
				tilesResult = null;
				tilesMessage = null;
			}
			renderTiles();
		});
	}

	public void showTiles(ApiResult<TilesResponse> result)
	{
		onEdt(() ->
		{
			tilesResult = result;
			renderTiles();
		});
	}

	/** The site's links; the player menu, built on each click, picks up the Discord invite. */
	public void showLinks(ApiResult<LinksResponse> result)
	{
		onEdt(() -> linksResult = result);
	}

	/**
	 * What the player's own clan record says: {@code blocker} is the line to
	 * show while verifying isn't possible (null once it says Dream Chasers).
	 */
	public void setOwnClan(String blocker, String rsn)
	{
		onEdt(() ->
		{
			boolean changed = !Objects.equals(blocker, verifyBlocker);
			verifyBlocker = blocker;
			verifyRsn = rsn;     // the player menu reads both when it opens
			if (changed && verifyMessageIsBlocker)
			{
				clearVerifyMessage();     // the reason it couldn't sign in no longer holds
				renderStatus();
			}
		});
	}

	/** The browser approval is waiting for the member's answer; {@code hint} (or null) follows the code in the status line. */
	public void setVerifyWaiting(String code, String hint)
	{
		onEdt(() ->
		{
			verifyBusy = false;
			verifyWaitCode = code;
			verifyWaitHint = hint;
			clearVerifyMessage();
			renderStatus();
		});
	}

	/**
	 * The typed code worked: whose Discord it belongs to. Asks before linking
	 * (a dialog on the Swing thread; the caller's thread never waits for it).
	 */
	public void verifyAsk(String rsn, String discordName)
	{
		onEdt(() ->
		{
			verifyBusy = false;
			verifyWaitCode = null;
			verifyRsn = rsn;
			clearVerifyMessage();
			renderStatus();
			if (confirmLink(rsn, discordName))
			{
				verifyBusy = true;
				setVerifyMessage("Linking…", false);
				listener.verifyConfirm();
			}
			else
			{
				setVerifyMessage("Cancelled. Nothing was linked.", false);
				listener.verifyCancel();
			}
		});
	}

	/** The words a typed code is confirmed with; site text is shown as typed, never as HTML. */
	static String linkQuestion(String rsn, String discordName)
	{
		return "This links " + rsn + " to Discord " + discordName + ". "
			+ "Only continue if that Discord account is yours. If someone sent you this code, press Cancel.";
	}

	/** Asks for the code from dream-chasers.cc/join; null or blank when the member cancels. Swing thread; tests stub it. */
	String askCode()
	{
		return JOptionPane.showInputDialog(this, "The 8-character code from dream-chasers.cc/join, like ABCD-2345:",
			"Enter a code from the site", JOptionPane.PLAIN_MESSAGE);
	}

	/** True when the member continues with "This links RSN to Discord NAME". Swing thread; tests stub it. */
	boolean confirmLink(String rsn, String discordName)
	{
		JLabel question = new JLabel(linkQuestion(rsn, discordName));
		question.putClientProperty("html.disable", Boolean.TRUE);
		return JOptionPane.showConfirmDialog(this, question, "Verify for Discord",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
	}

	/** Officer roster sync's state (officers with the setting on only): on, off once the site stopped it, or null to leave it out. */
	public void setRosterSync(Boolean on)
	{
		onEdt(() ->
		{
			rosterOn = on;
		});
	}

	/** "Enable everything" was ticked before signing in; cleared by signing in. */
	public void setEnableAllRefused(boolean refused)
	{
		onEdt(() ->
		{
			enableAllRefused = refused;
			renderStatus();
		});
	}

	/** The folded sections, as saved (comma-separated keys). */
	public void setCollapsed(String keys)
	{
		onEdt(() ->
		{
			collapsed.clear();
			if (keys != null)
			{
				Arrays.stream(keys.split(",")).map(String::trim).filter(k -> !k.isEmpty()).forEach(collapsed::add);
			}
			renderAll();
		});
	}

	/**
	 * A section's title row, which folds and unfolds it. Returns true when the
	 * section is folded: the caller adds nothing more.
	 */
	private boolean header(JPanel section, String key, String name)
	{
		boolean folded = collapsed.contains(key);
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(title(name), BorderLayout.CENTER);
		JLabel arrow = new JLabel(folded ? "\u25B8" : "\u25BE");
		arrow.setForeground(Brand.GOLD);
		arrow.setFont(new Font(Font.DIALOG, Font.PLAIN, 11));     // the RuneScape fonts have no arrows
		arrow.setBorder(new EmptyBorder(0, 0, 4, 0));
		row.add(arrow, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setToolTipText(folded ? "Show" : "Hide");
		row.setName("header:" + key);
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggleSection(key);
			}
		});
		section.add(row);
		return folded;
	}

	/** Folds or unfolds one section (on the EDT), and saves the folded set. */
	void toggleSection(String key)
	{
		if (!collapsed.remove(key))
		{
			collapsed.add(key);
		}
		listener.collapsedChanged(String.join(",", collapsed));
		renderAll();
	}

	/**
	 * The logged-in name once it is verified, else null. A fold to a newly
	 * verified name clears the last verify message, except the answer of the
	 * verify that just did it ("Verified … Not your Discord account? …").
	 */
	public void setVerifiedAs(String rsn)
	{
		onEdt(() ->
		{
			if (rsn != null && !rsn.equals(verifiedAs))
			{
				if (verifyMessageOutlivesFold)
				{
					verifyMessageOutlivesFold = false;
				}
				else
				{
					clearVerifyMessage();
				}
			}
			verifiedAs = rsn;
			renderHeader();
			renderStatus();
		});
	}

	/**
	 * A sign-in, verify or typed code is over; {@code message} (or null) goes
	 * in the status line until the next fold to verified, sign-out or attempt.
	 */
	public void verifyDone(String message, boolean isError)
	{
		onEdt(() ->
		{
			verifyBusy = false;
			verifyWaitCode = null;
			verifyWaitHint = null;
			setVerifyMessage(message, isError);
		});
	}

	/**
	 * A sign-in or verify succeeded ("Verified … Not your Discord account? …"):
	 * unlike {@link #verifyDone}, the message stays through the fold to
	 * verified that this very verify causes.
	 */
	public void verifiedDone(String message)
	{
		onEdt(() ->
		{
			verifyBusy = false;
			verifyWaitCode = null;
			verifyWaitHint = null;
			setVerifyMessage(message, false);
			verifyMessageOutlivesFold = message != null;
		});
	}

	/**
	 * Ticking Connect couldn't sign in: {@code blocker} (the clan record's
	 * line, as the menu shows it) goes in the status line until the record
	 * changes, a new attempt starts or the member signs in.
	 */
	public void showSignInBlocked(String blocker)
	{
		onEdt(() ->
		{
			if (blocker == null || verifyBusy || verifyWaitCode != null)
			{
				return;
			}
			setVerifyMessage(blocker + ".", true);
			verifyMessageIsBlocker = true;
		});
	}

	private void setVerifyMessage(String message, boolean isError)
	{
		verifyMessage = message;
		verifyMessageIsError = isError;
		verifyMessageOutlivesFold = false;
		verifyMessageIsBlocker = false;
		renderStatus();
	}

	private void clearVerifyMessage()
	{
		verifyMessage = null;
		verifyMessageIsError = false;
		verifyMessageOutlivesFold = false;
		verifyMessageIsBlocker = false;
	}

	public void setLiveEnabled(boolean enabled)
	{
		onEdt(() ->
		{
			liveEnabled = enabled;
			renderLive();
		});
	}

	/** Whether the DRM Live section is on screen, so the plugin knows to poll it. */
	public boolean isLiveShown()
	{
		return liveEnabled && connected && signedInAs != null && isShowing();
	}

	public void showLive(ApiResult<LiveResponse> result)
	{
		onEdt(() ->
		{
			liveResult = result;
			renderLive();
		});
	}

	/**
	 * After a live action. {@code join} replaces the current slot unless
	 * {@code keepJoin}; {@code message} is shown under the buttons, in the warning colour when {@code isError}.
	 */
	public void liveDone(LiveJoinResponse join, boolean keepJoin, String message, boolean isError)
	{
		onEdt(() ->
		{
			liveBusy = false;
			if (!keepJoin)
			{
				liveJoin = join;
			}
			liveMessage = message;
			liveMessageIsError = isError;
			renderLive();
		});
	}

	public void setSplashEnabled(boolean enabled)
	{
		onEdt(() ->
		{
			splashEnabled = enabled;
			renderSplash();
		});
	}

	public void setSplashOptedIn(boolean optedIn)
	{
		onEdt(() ->
		{
			splashOptedIn = optedIn;
			if (!optedIn)
			{
				modelStatus = null;
			}
			renderSplash();
		});
	}

	public void showFeatured(ApiResult<FeaturedResponse> result)
	{
		onEdt(() ->
		{
			featuredResult = result;
			renderSplash();
		});
	}

	/** A downloaded splash, drawn across the panel's full width. Kept until a newer one loads. */
	public void showFeaturedImage(String rsn, BufferedImage image)
	{
		if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0)
		{
			return;
		}
		onEdt(() ->
		{
			featuredImage = new SplashImage(image);
			featuredImageRsn = rsn;
			renderSplash();
		});
	}

	public void showModelStatus(ApiResult<ModelStatusResponse> result)
	{
		onEdt(() ->
		{
			modelStatus = result;
			renderSplash();
		});
	}

	/** After Feature me or Remove me; {@code message} goes under the splash row (an error always, else only before opting in). */
	public void splashDone(String message, boolean isError)
	{
		onEdt(() ->
		{
			splashBusy = false;
			splashMessage = message;
			splashMessageIsError = isError;
			renderSplash();
		});
	}

	// ---- rendering (Swing thread) ----

	private void renderAll()
	{
		renderHeader();
		renderStatus();
		renderEverything();
		clogHint.setVisible(clogHint.getComponentCount() > 0);
		renderLive();
		renderSplash();
		renderEvents();
		renderCompetition();
		renderHallOfFame();
		renderTiles();
	}

	/** The player button is always there: the one place to sign in, verify and sign out. */
	private void renderHeader()
	{
		player.setVisible(true);
		player.setToolTipText(signedInAs != null ? whoLine() : "Sign in");
	}

	/** "Verified as NAME" once the site has verified the logged-in name, else "Connected as NAME". */
	private String whoLine()
	{
		return verifiedAs != null ? "Verified as " + verifiedAs : "Connected as " + signedInAs;
	}

	/**
	 * The player button's menu, built fresh on each click: who this is
	 * (greyed out), the sign-in or verify step, a code from the site, then
	 * the Discord invite, the site and Sign out.
	 */
	JPopupMenu accountMenu()
	{
		boolean signedIn = signedInAs != null;
		JPopupMenu menu = new JPopupMenu();
		menu.setName("accountMenu");
		menu.add(greyed(signedIn ? whoLine() : "Not signed in"));
		if (verifyWaitCode != null)
		{
			addGreyed(menu, "Approve in your browser: " + verifyWaitCode);
			menu.add(menuItem("Cancel", this::stopWaiting));
		}
		else if (!connected)
		{
			addGreyed(menu, "Turn on Connect in the plugin settings");
		}
		else if (notInGame())
		{
			if (!signedIn)
			{
				menu.add(greyed("Log in to the game to sign in"));
			}
		}
		else if (signedIn && verifiedAs != null)
		{
			// verified: nothing left to do, whatever the clan record says (it may not have loaded, or there is none)
		}
		else if (verifyBlocker != null)
		{
			addGreyed(menu, verifyBlocker);
		}
		else
		{
			menu.add(startItem(signedIn ? "Verify " + verifyRsn + " for Discord" : "Sign in with Discord"));
			if (!verifyBusy)
			{
				// the typed-code route: only where verifying is still to do, never for a verified name
				menu.add(menuItem("Enter a code from the site…", this::enterCode));
			}
		}
		Boolean roster = rosterOn;
		if (roster != null)
		{
			// one short line (Frederik, 19 Nov: "Just \"Roster Sync is On\" is enough"); off once the site stopped it
			menu.add(greyed(roster ? "Roster sync is on" : "Roster sync is off"));
		}
		menu.addSeparator();
		// the server invite, from /links; before links load, the join page (it has the invite too)
		String invite = discordInvite();
		menu.add(menuItem("Join our Discord", invite != null ? () -> go(invite, SafeLinks.DISCORD) : this::openJoinPage));
		menu.add(menuItem("Open dream-chasers.cc", this::openSite));
		if (signedIn)
		{
			menu.add(menuItem("Sign out", listener::signOut));
		}
		return menu;
	}

	/** At the login screen: no own-clan record, so no name to sign in or verify with. */
	private boolean notInGame()
	{
		return verifyRsn == null && (verifyBlocker == null || cc.dreamchasers.clan.OwnClan.LOGGED_OUT_BLOCKER.equals(verifyBlocker));
	}

	/**
	 * Characters per greyed menu line. The menu opens right-aligned under the
	 * player button, so a line much wider than the panel (225 px) would spill
	 * out over the game.
	 */
	static final int MENU_LINE_CHARS = 38;

	/** A greyed-out line, split at spaces into lines of at most {@link #MENU_LINE_CHARS} (plain text, never HTML). */
	private static void addGreyed(JPopupMenu menu, String text)
	{
		for (String line : wrapMenuLine(text))
		{
			menu.add(greyed(line));
		}
	}

	static List<String> wrapMenuLine(String text)
	{
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.trim().split("\\s+"))
		{
			while (word.length() > MENU_LINE_CHARS)
			{
				if (line.length() > 0)
				{
					out.add(line.toString());
					line.setLength(0);
				}
				out.add(word.substring(0, MENU_LINE_CHARS));
				word = word.substring(MENU_LINE_CHARS);
			}
			if (line.length() > 0 && line.length() + 1 + word.length() > MENU_LINE_CHARS)
			{
				out.add(line.toString());
				line.setLength(0);
			}
			if (line.length() > 0)
			{
				line.append(' ');
			}
			line.append(word);
		}
		if (line.length() > 0)
		{
			out.add(line.toString());
		}
		return out;
	}

	private static JMenuItem greyed(String label)
	{
		JMenuItem item = menuItem(label, null);
		item.setEnabled(false);
		return item;
	}

	/** Sign in with Discord / Verify NAME for Discord: opens the browser approval. Greyed out while one is on its way. */
	private JMenuItem startItem(String label)
	{
		JMenuItem item = menuItem(label, this::startVerify);
		item.setEnabled(!verifyBusy);
		return item;
	}

	private void startVerify()
	{
		if (verifyBusy || verifyWaitCode != null || verifyBlocker != null)
		{
			return;
		}
		verifyBusy = true;
		setVerifyMessage("Asking dream-chasers.cc…", false);
		listener.verifyStart();
	}

	private void stopWaiting()
	{
		verifyWaitCode = null;
		verifyWaitHint = null;
		setVerifyMessage("Cancelled. Nothing was linked.", false);
		listener.verifyStop();
	}

	/** "Enter a code from the site…": asks for it, then the site says whose Discord it is (see {@link #verifyAsk}). */
	private void enterCode()
	{
		if (verifyBusy || verifyWaitCode != null || !connected)
		{
			return;
		}
		String code = askCode();
		if (code == null || code.trim().isEmpty())
		{
			return;
		}
		verifyBusy = true;
		setVerifyMessage("Checking the code…", false);
		listener.verifyPeek(code.trim());
	}

	private static JMenuItem menuItem(String label, Runnable action)
	{
		JMenuItem item = new JMenuItem(label);
		item.putClientProperty("html.disable", Boolean.TRUE);     // a name is shown as typed, never as HTML
		if (action != null)
		{
			item.addActionListener(e -> action.run());
		}
		return item;
	}

	private void showAccountMenu()
	{
		JPopupMenu menu = accountMenu();
		// right-aligned under the button, as on the site
		menu.show(player, player.getWidth() - menu.getPreferredSize().width, player.getHeight());
	}

	/** The wordmark and the menu's site item: the site from /links, so a staging build opens staging. */
	private void openSite()
	{
		go(linkOr(LinksResponse::getSite, SITE), safeLinks.site());
	}

	/**
	 * The one status line, in priority: the browser approval's wait, the
	 * last sign-in or verify answer (or why ticking Connect couldn't sign in),
	 * Enable everything's refusal, why the site signed the member out,
	 * Connect off, then a refresh failure.
	 */
	private void renderStatus()
	{
		if (verifyWaitCode != null)
		{
			status.setForeground(Brand.TEXT);
			status.setText(html("Approve in your browser. Check the page shows <b>" + esc(verifyWaitCode) + "</b>."
				+ (verifyWaitHint != null ? "<br><font color='" + Brand.hex(Brand.WARN) + "'>" + esc(verifyWaitHint) + "</font>" : "")));
			return;
		}
		if (verifyMessage != null)
		{
			status.setForeground(verifyMessageIsError ? Brand.WARN : verifyBusy ? Brand.MUTED : Brand.TEXT);
			status.setText(html(esc(verifyMessage)));
			return;
		}
		if (enableAllRefused)
		{
			status.setForeground(Brand.GOLD);
			status.setText(html(ENABLE_ALL_REFUSED));
			return;
		}
		String revoked = signedOutReason();
		if (revoked != null)
		{
			status.setForeground(Brand.WARN);
			status.setText(html(esc(revoked) + "."));
			return;
		}
		status.setForeground(Brand.MUTED);
		if (!connected)
		{
			status.setText(signedInAs == null ? html(CONNECT_OFF) : "");
			return;
		}
		boolean failed = isError(competitionResult) || isError(hallOfFameResult) || isError(eventsResult);
		status.setText(failed ? html("Couldn't refresh just now; showing the last copy.") : "");
	}

	/**
	 * The site's 401 reason when it signed the member out (a revoked or
	 * expired token), else null. "Not signed in" is just the signed-out state.
	 */
	private String signedOutReason()
	{
		ApiResult<EventsResponse> r = eventsResult;
		if (!connected || signedInAs != null || r == null || r.getStatus() != ApiResult.Status.UNAUTHORIZED
			|| r.getMessage() == null || r.getMessage().isEmpty() || "Not signed in".equals(r.getMessage()))
		{
			return null;
		}
		return r.getMessage();
	}

	private void renderEvents()
	{
		events.removeAll();
		events.setVisible(connected);
		if (header(events, "events", "Events"))
		{
			done(events);
			return;
		}
		if (!connected)
		{
			done(events);
			return;
		}

		if (signedInAs == null)
		{
			events.add(text("Events are for clan members. Sign in from the menu at the top right to see them.", Brand.MUTED));
			done(events);
			return;
		}

		ApiResult<EventsResponse> r = eventsResult;
		if (r != null && r.getStatus() == ApiResult.Status.FORBIDDEN)
		{
			events.add(text(esc(r.getMessage()) + ". Set your server nickname to your RSN in #rsn, or claim your account on the site, then reopen this panel.", Brand.WARN));
		}
		else if (r == null || r.getValue() == null)
		{
			events.add(text(r == null ? "Loading…" : esc(r.getMessage()), Brand.MUTED));
		}
		else
		{
			List<EventInfo> list = r.getValue().getEvents() == null ? new ArrayList<>() : r.getValue().getEvents();
			if (list.isEmpty())
			{
				events.add(text("Nothing scheduled right now.", Brand.MUTED));
			}
			else
			{
				renderNextEvent(list.get(0));
				// another event on at the same time (a Boss of the Weekend inside the Skill of the Month)
				// is live too: say so, with its end, rather than listing it as coming up
				Instant now = clock.instant();
				List<EventInfo> rest = list.subList(1, list.size());
				for (EventInfo e : rest)
				{
					if (e.isLive(now))
					{
						events.add(Box.createVerticalStrut(6));
						events.add(text("<b><font color='" + Brand.hex(Brand.GOLD) + "'>" + esc(e.getTitle()) + "</font></b><br>"
							+ "<font color='" + Brand.hex(Brand.LIVE) + "'><b>LIVE NOW</b></font>"
							+ (e.endInstant() != null ? " · ends " + TimeText.until(now, e.endInstant()) : ""), Brand.TEXT));
					}
				}
				List<EventInfo> coming = rest.stream().filter(e -> !e.isLive(now)).limit(UPCOMING_SHOWN).collect(Collectors.toList());
				if (!coming.isEmpty())
				{
					events.add(Box.createVerticalStrut(6));
					events.add(text("<b>Coming up</b>", Brand.GOLD));
					for (EventInfo e : coming)
					{
						events.add(text(esc(e.getTitle()) + "<br><font color='" + Brand.hex(Brand.MUTED) + "'>"
							+ TimeText.when(e.startInstant(), zone) + "</font>", Brand.TEXT));
					}
				}
			}
		}
		events.add(Box.createVerticalStrut(4));
		JPanel buttons = row();
		buttons.add(button("Events page", () -> go(linkOr(l -> l.getEvents(), SITE + "/events"), safeLinks.site())));
		events.add(buttons);
		done(events);
	}

	private void renderNextEvent(EventInfo e)
	{
		Instant now = clock.instant();
		boolean live = e.isLive(now);
		StringBuilder sb = new StringBuilder();
		sb.append("<b><font color='").append(Brand.hex(Brand.GOLD)).append("'>").append(esc(e.getTitle())).append("</font></b><br>");
		if (live)
		{
			sb.append("<font color='").append(Brand.hex(Brand.LIVE)).append("'><b>LIVE NOW</b></font>");
			if (e.endInstant() != null)
			{
				sb.append(" · ends ").append(TimeText.until(now, e.endInstant()));
			}
		}
		else
		{
			sb.append(TimeText.when(e.startInstant(), zone)).append(" · ").append(TimeText.until(now, e.startInstant()));
		}
		if (notBlank(e.getWhere()))
		{
			sb.append("<br>Where: ").append(esc(e.getWhere()));
		}
		if (notBlank(e.getHost()))
		{
			sb.append("<br>Host: ").append(esc(e.getHost()));
		}
		if (notBlank(e.getDetails()))
		{
			sb.append("<br><font color='").append(Brand.hex(Brand.MUTED)).append("'>")
				.append(esc(e.getDetails()).replace("\n", "<br>")).append("</font>");
		}
		if (e.isWeekly())
		{
			sb.append("<br><i>Weekly</i>");
		}
		events.add(text(sb.toString(), Brand.TEXT));
	}

	/** The site's Discord invite from /links, or null before links have loaded. */
	private String discordInvite()
	{
		LinksResponse l = linksResult == null ? null : linksResult.getValue();
		return l != null && notBlank(l.getDiscord()) ? l.getDiscord() : null;
	}

	private void openJoinPage()
	{
		go(linkOr(LinksResponse::getSite, SITE) + "/join", safeLinks.site());
	}

	/**
	 * "Enable everything", only once signed in and only while something is
	 * off. It ticks the setting of the same name.
	 */
	private void renderEverything()
	{
		everything.removeAll();
		everything.setVisible(signedInAs != null && !allOn);
		if (signedInAs != null && !allOn)
		{
			everything.add(text("Turn on every Dream Chasers feature (and Connect, if it's off): PBs, collection log, clan splash, tile packs, "
				+ "DRM Live and raid stats. It doesn't agree to the stream consent or send your model.", Brand.TEXT));
			everything.add(text("These submit your IP address to a 3rd-party server not controlled or verified by "
				+ "RuneLite developers.", Brand.WARN));
			JPanel b = row();
			b.add(button("Enable everything", listener::enableAll));
			everything.add(b);
		}
		done(everything);
	}

	/**
	 * DRM Live: the show's state, the stream consent (every time until
	 * agreed), then this member's own stream link, the voice channel, short
	 * steps and Leave. The plugin only opens links; it never captures the game.
	 */
	private void renderLive()
	{
		live.removeAll();
		boolean signedIn = connected && signedInAs != null;
		live.setVisible(signedIn);
		if (!signedIn)
		{
			done(live);
			return;
		}
		if (header(live, "live", "DRM Live"))
		{
			done(live);
			return;
		}
		if (!liveEnabled)
		{
			live.add(text("Want to get on the clan stream? Turn on <b>Join DRM Live</b> in this plugin's settings.", Brand.MUTED));
			done(live);
			return;
		}
		ApiResult<LiveResponse> r = liveResult;
		LiveResponse lr = r == null ? null : r.getValue();
		if (lr == null)
		{
			live.add(text(r == null ? "Loading…" : esc(r.getMessage()), Brand.MUTED));
			done(live);
			return;
		}
		LiveResponse.Status st = lr.getStatus();
		String show = st == null || st.getShow() == null ? "off" : st.getShow();
		if ("live".equals(show))
		{
			live.add(text("<font color='" + Brand.hex(Brand.LIVE) + "'><b>LIVE NOW</b></font>"
				+ (st.getSlotsFree() > 0 ? " · " + st.getSlotsFree() + " guest slot" + (st.getSlotsFree() == 1 ? "" : "s") + " free" : " · no free slots"), Brand.TEXT));
		}
		else if ("open".equals(show))
		{
			live.add(text("<b>Open for guests</b> · " + st.getSlotsFree() + " slot" + (st.getSlotsFree() == 1 ? "" : "s") + " free", Brand.TEXT));
		}
		else
		{
			live.add(text("No show right now." + (st != null && notBlank(st.getNote()) ? "<br>" + muted(esc(st.getNote())) : ""), Brand.MUTED));
		}

		LiveJoinResponse j = liveJoin;
		if (j != null)
		{
			live.add(text("Guest slot <b>" + j.getSlot() + "</b>. " + (j.isOnAir() ? "<font color='" + Brand.hex(Brand.LIVE) + "'><b>You're on air.</b></font>" : "You're connected. Waiting to go on air: a producer will put you on."), Brand.TEXT));
			JPanel b1 = row();
			b1.add(primary("Open my stream link", () -> go(j.getPushUrl(), SafeLinks.VDO)));
			live.add(b1);
			String voice = notBlank(j.getVoiceUrl()) ? j.getVoiceUrl() : st == null ? null : st.getVoiceUrl();
			if (notBlank(voice))
			{
				JPanel b2 = row();
				b2.add(button("Join the stream voice channel", () -> go(voice, SafeLinks.DISCORD)));
				live.add(b2);
			}
			List<String> steps = j.getSteps() != null && !j.getSteps().isEmpty() ? j.getSteps() : DEFAULT_STEPS;
			for (int i = 0; i < steps.size(); i++)
			{
				live.add(text((i + 1) + ". " + esc(steps.get(i)), Brand.TEXT));
			}
			JPanel b3 = row();
			b3.add(button("Leave", () -> liveAction(listener::liveLeave)));
			live.add(b3);
		}
		else if (!lr.isConsented())
		{
			LiveResponse.Consent c = lr.getConsent();
			if (c != null && c.getLines() != null)
			{
				StringBuilder sb = new StringBuilder("<b>").append(esc(notBlank(c.getTitle()) ? c.getTitle() : "Before you go on stream")).append("</b>");
				for (String line : c.getLines())
				{
					sb.append("<br>&bull; ").append(esc(line));
				}
				live.add(text(sb.toString(), Brand.TEXT));
				JPanel b = row();
				b.add(primary("Agree", () -> liveAction(() -> listener.liveAgree(c.getVersion()))));
				live.add(b);
			}
		}
		else
		{
			JPanel b = row();
			JButton join = primary("Join the stream", () -> liveAction(listener::liveJoin));
			join.setEnabled(!"off".equals(show) && !liveBusy);
			b.add(join);
			live.add(b);
			JPanel w = row();
			w.add(button("Withdraw consent", () -> liveAction(listener::liveWithdraw)));
			live.add(w);
		}
		if (liveBusy)
		{
			live.add(text("Working…", Brand.MUTED));
		}
		else if (liveMessage != null)
		{
			live.add(text(esc(liveMessage), liveMessageIsError ? Brand.WARN : Brand.MUTED));
		}
		done(live);
	}

	private void liveAction(Runnable action)
	{
		if (liveBusy)
		{
			return;
		}
		liveBusy = true;
		liveMessage = null;
		renderLive();
		action.run();
	}

	/**
	 * Clan splash: today's featured member, then this member's own entry:
	 * the consent and Feature me, or where their splash stands and Remove me.
	 * Remove me shows whenever they're in, even with the setting off.
	 */
	private void renderSplash()
	{
		splash.removeAll();
		splash.setVisible(connected);
		if (!connected)
		{
			done(splash);
			return;
		}
		JPanel top = splashPart(6, 0);
		splash.add(top);
		if (header(top, "splash", "Clan splash"))
		{
			done(splash);
			return;
		}
		JPanel bottom = splashPart(0, 8);
		ApiResult<FeaturedResponse> fr = featuredResult;
		FeaturedResponse f = fr == null ? null : fr.getValue();
		boolean mine = signedInAs != null && splashOptedIn;
		ModelStatusResponse ms = modelStatus == null ? null : modelStatus.getValue();
		String st = ms == null || ms.getStatus() == null ? "" : ms.getStatus();
		if (featuredImage != null)
		{
			splash.add(featuredImage);     // edge to edge
			bottom.add(Box.createVerticalStrut(4));
		}
		// one row: the featured member in bold, then (opted in) resubmit, remove and view as icons
		JPanel line = new JPanel(new BorderLayout(4, 0));
		line.setOpaque(false);
		line.setAlignmentX(Component.LEFT_ALIGNMENT);
		String who = featuredImage != null ? "<b>" + esc(featuredImageRsn) + "</b>"
			: f != null && !notBlank(f.getRsn()) ? "Nobody is featured yet."
			: fr != null && f == null ? esc(fr.getMessage()) : null;
		if (who != null)
		{
			JLabel name = new JLabel(html(who));
			name.setForeground(featuredImage != null ? Brand.GOLD : Brand.MUTED);
			name.setFont(FontManager.getRunescapeSmallFont());
			name.setName("splashName");
			line.add(name, BorderLayout.CENTER);
		}
		if (mine)
		{
			JPanel icons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
			icons.setOpaque(false);
			if (splashEnabled)
			{
				icons.add(iconButton("splashResend", ICON_REFRESH, "Send a new pose (stand still in game first)",
					() -> splashAction(listener::featureMe)));
			}
			icons.add(iconButton("splashRemove", ICON_REMOVE, "Remove me from the clan splash",
				() -> splashAction(listener::removeMe)));
			icons.add(iconButton("splashView", ICON_VIEW, splashViewTip(st), this::viewMySplash));
			line.add(icons, BorderLayout.EAST);
		}
		line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
		if (who != null || mine)
		{
			bottom.add(line);
		}

		if (signedInAs != null)
		{
			if (mine)
			{
				// only what needs the member: a pose the splash maker couldn't use
				if ("rejected".equals(st))
				{
					// the refresh icon that sends a new pose is only there with the Clan splash setting on
					String again = splashEnabled ? "Stand still in game and send a new pose."
						: "Turn on <b>Clan splash</b> in this plugin's settings to send a new pose.";
					bottom.add(text("Your last pose couldn't be used"
						+ (notBlank(ms.getReason()) ? ": " + esc(ms.getReason()) + "." + (splashEnabled ? "" : " " + again)
						: ". " + again), Brand.WARN));
				}
			}
			else if (!splashEnabled)
			{
				bottom.add(text("Want to be the statue? Turn on <b>Clan splash</b> in this plugin's settings.", Brand.MUTED));
			}
			else
			{
				bottom.add(text(SPLASH_CONSENT, Brand.TEXT));
				JPanel b = row();
				b.add(primary("Feature me on the clan splash", () -> splashAction(listener::featureMe)));
				bottom.add(b);
			}
			if (splashBusy)
			{
				bottom.add(text("Working…", Brand.MUTED));
			}
			else if (splashMessage != null && (splashMessageIsError || !mine))
			{
				bottom.add(text(esc(splashMessage), splashMessageIsError ? Brand.WARN : Brand.MUTED));
			}
		}
		splash.add(bottom);
		done(splash);
	}

	static final ImageIcon ICON_REFRESH = icon("icon_refresh.png");
	static final ImageIcon ICON_REMOVE = icon("icon_remove.png");
	static final ImageIcon ICON_VIEW = icon("icon_view.png");

	private static ImageIcon icon(String file)
	{
		return new ImageIcon(ImageUtil.loadImageResource(DreamChasersPanel.class, "/cc/dreamchasers/clan/" + file));
	}

	/** A 14 px gold icon as a flat button, with its action in the tooltip. */
	private static JButton iconButton(String name, ImageIcon icon, String tip, Runnable action)
	{
		JButton b = flat(new JButton(icon));
		b.setName(name);
		b.setToolTipText(tip);
		Dimension size = new Dimension(18, 18);
		b.setPreferredSize(size);
		b.setMinimumSize(size);
		b.setMaximumSize(size);
		b.addActionListener(e -> action.run());
		return b;
	}

	/** The eye's tooltip says where the member's own splash is at. */
	private static String splashViewTip(String status)
	{
		switch (status)
		{
			case "ok":
				return "View my splash on dream-chasers.cc (you're in the pool)";
			case "queued":
				return "View my profile on dream-chasers.cc (your splash is being made)";
			default:
				return "View my profile on dream-chasers.cc";
		}
	}

	/** The eye: the member's own profile, which shows their splash model. */
	private void viewMySplash()
	{
		ModelStatusResponse ms = modelStatus == null ? null : modelStatus.getValue();
		String rsn = ms != null && ms.getFeatured() != null && notBlank(ms.getFeatured().getRsn()) ? ms.getFeatured().getRsn()
			: verifiedAs != null ? verifiedAs : signedInAs;
		if (!notBlank(rsn))
		{
			openSite();
			return;
		}
		try
		{
			go(linkOr(LinksResponse::getSite, SITE) + "/clan/" + java.net.URLEncoder.encode(rsn, "UTF-8").replace("+", "%20"), safeLinks.site());
		}
		catch (java.io.UnsupportedEncodingException e)
		{
			openSite();
		}
	}

	/** One padded part of the splash section; its text lines up with the other sections' text. */
	private static JPanel splashPart(int top, int bottom)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setBorder(new EmptyBorder(top, 18, bottom, 14));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private void splashAction(Runnable action)
	{
		if (splashBusy)
		{
			return;
		}
		splashBusy = true;
		splashMessage = null;
		renderSplash();
		action.run();
	}

	private void renderCompetition()
	{
		competition.removeAll();
		competition.setVisible(connected);
		if (header(competition, "competition", "Competition"))
		{
			done(competition);
			return;
		}
		if (!connected)
		{
			done(competition);
			return;
		}
		ApiResult<CompetitionResponse> r = competitionResult;
		if (r == null || r.getValue() == null)
		{
			competition.add(text(r == null ? "Loading…" : esc(r.getMessage()), Brand.MUTED));
			done(competition);
			return;
		}
		CompetitionResponse cr = r.getValue();
		Competition c = cr.getCompetition();
		if (c == null || "none".equals(cr.getStatus()))
		{
			competition.add(text("No competition right now.", Brand.MUTED));
			done(competition);
			return;
		}

		Instant now = clock.instant();
		Instant starts = parse(c.getStartsAt());
		Instant ends = parse(c.getEndsAt());
		String when;
		switch (cr.getStatus() == null ? "" : cr.getStatus())
		{
			case "ongoing":
				when = "<font color='" + Brand.hex(Brand.LIVE) + "'>On now</font> · ends " + TimeText.until(now, ends);
				break;
			case "upcoming":
				when = "Starts " + TimeText.until(now, starts) + " (" + TimeText.when(starts, zone) + ")";
				break;
			case "finished":
				when = "Finished " + TimeText.day(ends, zone);
				break;
			default:
				when = "";
		}
		competition.add(text("<b><font color='" + Brand.hex(Brand.GOLD) + "'>" + esc(c.getTitle()) + "</font></b><br>"
			+ when + (c.getParticipants() > 0 ? "<br>" + c.getParticipants() + " taking part" : ""), Brand.TEXT));

		List<Standing> standings = c.getStandings() == null ? new ArrayList<>() : c.getStandings();
		if (standings.isEmpty())
		{
			competition.add(text("No gains yet.", Brand.MUTED));
		}
		else
		{
			boolean split = c.getParts() != null && !c.getParts().isEmpty();
			StringBuilder sb = new StringBuilder("<table cellpadding='0' cellspacing='0' width='" + TEXT_WIDTH + "'>");
			if (split)
			{
				String labels = c.getParts().stream().map(CompetitionPart::getLabel).map(DreamChasersPanel::esc)
					.collect(Collectors.joining(" + "));
				sb.append("<tr><td colspan='3'><font color='").append(Brand.hex(Brand.MUTED)).append("'>")
					.append(labels).append("</font></td></tr>");
			}
			for (Standing s : standings.subList(0, Math.min(STANDINGS_SHOWN, standings.size())))
			{
				String colour = s.getRank() == 1 ? Brand.hex(Brand.GOLD) : Brand.hex(Brand.TEXT);
				sb.append("<tr><td width='18'><font color='").append(Brand.hex(Brand.MUTED)).append("'>").append(s.getRank())
					.append("</font></td><td><font color='").append(colour).append("'>").append(esc(s.getRsn()))
					.append("</font></td><td align='right'>");
				if (split && s.getParts() != null)
				{
					sb.append("<font color='").append(Brand.hex(Brand.MUTED)).append("'>")
						.append(s.getParts().stream().map(n -> String.format("%,d", n)).collect(Collectors.joining("+")))
						.append("</font> ");
				}
				sb.append(String.format("%,d", s.getGained())).append("</td></tr>");
			}
			sb.append("</table>");
			competition.add(text(sb.toString(), Brand.TEXT));
		}
		JPanel buttons = row();
		buttons.add(button("Standings", () -> go(notBlank(c.getUrl()) ? c.getUrl() : SITE + "/competitions", safeLinks.site())));
		if (notBlank(c.getWom()))
		{
			buttons.add(button("Wise Old Man", () -> go(c.getWom(), SafeLinks.WOM)));
		}
		competition.add(buttons);
		done(competition);
	}

	private void renderHallOfFame()
	{
		hallOfFame.removeAll();
		hallOfFame.setVisible(connected);
		if (header(hallOfFame, "halloffame", "Hall of Fame"))
		{
			done(hallOfFame);
			return;
		}
		if (!connected)
		{
			done(hallOfFame);
			return;
		}
		ApiResult<HallOfFameResponse> r = hallOfFameResult;
		if (r == null || r.getValue() == null)
		{
			hallOfFame.add(text(r == null ? "Loading…" : esc(r.getMessage()), Brand.MUTED));
			done(hallOfFame);
			return;
		}
		List<HallOfFameDigest.RecentFirst> firsts = HallOfFameDigest.recentFirsts(r.getValue(), FIRSTS_SHOWN);
		if (firsts.isEmpty())
		{
			hallOfFame.add(text("No dated clan firsts yet.", Brand.MUTED));
		}
		else
		{
			hallOfFame.add(text("<b>Recent clan firsts</b>", Brand.GOLD));
			for (HallOfFameDigest.RecentFirst f : firsts)
			{
				hallOfFame.add(text(esc(f.getAchievement()) + "<br><font color='" + Brand.hex(Brand.MUTED) + "'>"
					+ esc(f.getName()) + " · " + TimeText.day(f.getDate(), clock.instant().atZone(zone).getYear()) + "</font>", Brand.TEXT));
			}
		}
		JPanel buttons = row();
		buttons.add(button("Full board", () -> go(linkOr(l -> l.getHallOfFame(), SITE + "/hall-of-fame"), safeLinks.site())));
		hallOfFame.add(buttons);
		done(hallOfFame);
	}

	private void renderTiles()
	{
		tiles.removeAll();
		tiles.setVisible(connected && tilesEnabled);
		if (!tiles.isVisible())
		{
			done(tiles);
			return;
		}
		if (header(tiles, "tiles", "Tile packs"))
		{
			done(tiles);
			return;
		}
		ApiResult<TilesResponse> r = tilesResult;
		List<TilesResponse.Pack> packs = r == null || r.getValue() == null || r.getValue().getPacks() == null
			? null : r.getValue().getPacks();
		if (packs == null)
		{
			tiles.add(text(r == null ? "Loading…" : esc(r.getMessage()), Brand.MUTED));
			done(tiles);
			return;
		}
		if (packs.isEmpty())
		{
			tiles.add(text("No packs yet. Officers publish them on the site.", Brand.MUTED));
		}
		for (TilesResponse.Pack p : packs)
		{
			if (p == null || p.getMarkers() == null || p.getMarkers().size() == 0)
			{
				continue;
			}
			tiles.add(text("<b>" + esc(p.getName()) + "</b> <font color='" + Brand.hex(Brand.MUTED) + "'>· "
				+ p.getMarkers().size() + (p.getMarkers().size() == 1 ? " tile" : " tiles") + "</font>"
				+ (notBlank(p.getDescription()) ? "<br>" + esc(p.getDescription()) : ""), Brand.TEXT));
			JPanel row = row();
			row.add(button("Copy", () -> copyPack(p)));
			tiles.add(row);
		}
		tiles.add(text(tilesMessage != null ? esc(tilesMessage)
			: "Copy a pack, then in game right-click the world map orb and pick Ground Markers' <b>Import</b>.", Brand.MUTED));
		done(tiles);
	}

	/** Puts the pack on the clipboard in core Ground Markers' own format. Nothing else is touched. */
	private void copyPack(TilesResponse.Pack p)
	{
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(p.getMarkers().toString()), null);
			tilesMessage = "Copied " + p.getName() + ". In game, right-click the world map orb and pick Import under Ground Markers.";
		}
		catch (IllegalStateException | java.awt.HeadlessException e)
		{
			tilesMessage = "Couldn't use the clipboard just now. Try again.";
		}
		renderTiles();
	}

	// ---- widgets ----

	/**
	 * The header row, as on dream-chasers.cc: the site's Dream ♥ Chasers
	 * wordmark on the left (a link to the site), the player button on the right.
	 */
	private JPanel header(BufferedImage wordmark)
	{
		JPanel p = new JPanel(new BorderLayout(4, 0));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		JButton mark = flat(new JButton());
		mark.setName("wordmark");
		mark.setToolTipText("Open dream-chasers.cc");
		mark.setHorizontalAlignment(JButton.LEFT);
		int h;
		if (wordmark != null)
		{
			int w = WORDMARK_WIDTH;
			h = Math.max(1, Math.round(wordmark.getHeight() * (float) w / wordmark.getWidth()));
			mark.setIcon(new ImageIcon(ImageUtil.resizeImage(wordmark, w, h)));
		}
		else
		{
			mark.setText(html("<b>DREAM CHASERS</b>"));
			mark.setForeground(Brand.GOLD);
			mark.setFont(FontManager.getRunescapeBoldFont());
			h = 24;
		}
		mark.addActionListener(e -> openSite());
		p.add(mark, BorderLayout.CENTER);

		flat(player);
		player.setName("player");
		Dimension size = new Dimension(PLAYER_SIZE, PLAYER_SIZE);
		player.setPreferredSize(size);
		player.setMinimumSize(size);
		player.setMaximumSize(size);
		player.addActionListener(e -> showAccountMenu());
		p.add(player, BorderLayout.EAST);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(h, PLAYER_SIZE) + 4));
		return p;
	}

	/** A button that is only its icon or text: no fill, no border, a hand cursor. */
	private static JButton flat(JButton b)
	{
		b.setBorder(new EmptyBorder(0, 0, 0, 0));
		b.setBorderPainted(false);
		b.setContentAreaFilled(false);
		b.setFocusPainted(false);
		b.setOpaque(false);
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return b;
	}

	/**
	 * The panel's inner width (RuneLite's 225 less this panel's 8 px border
	 * each side) less the player button and the gap before it.
	 */
	private static final int WORDMARK_WIDTH = PluginPanel.PANEL_WIDTH - 16 - PLAYER_SIZE - 4;

	/** A round person glyph in the brand, like the site's account icon. No image is fetched for it. */
	static final class PlayerIcon implements Icon
	{
		private final int size;

		PlayerIcon(int size)
		{
			this.size = size;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.translate(x, y);
				float s = size;
				g2.setColor(Brand.ROYAL);
				g2.fill(new Ellipse2D.Float(1, 1, s - 2, s - 2));
				// head and shoulders, clipped to the circle
				g2.clip(new Ellipse2D.Float(1, 1, s - 2, s - 2));
				g2.setColor(Brand.GOLD);
				g2.fill(new Ellipse2D.Float(s * 0.34f, s * 0.18f, s * 0.32f, s * 0.32f));
				g2.fill(new Ellipse2D.Float(s * 0.17f, s * 0.56f, s * 0.66f, s * 0.6f));
				g2.setClip(null);
				g2.setColor(Brand.GOLD_DEEP);
				g2.setStroke(new BasicStroke(1.5f));
				g2.draw(new Ellipse2D.Float(1, 1, s - 2, s - 2));
			}
			finally
			{
				g2.dispose();
			}
		}

		@Override
		public int getIconWidth()
		{
			return size;
		}

		@Override
		public int getIconHeight()
		{
			return size;
		}
	}

	/**
	 * The featured splash, drawn edge to edge at whatever width the panel
	 * gives it, keeping its shape. Its height is fixed: the image's height at
	 * RuneLite's panel width (the width with the scroll bar showing; the splash
	 * has no side insets). Wider, it is centred and a few rows are cropped. A
	 * height that followed the width would feed back into the scroll bar
	 * (as-needed) that sets the width, and the layout could flip forever.
	 * It is rescaled only when the width changes.
	 */
	static final class SplashImage extends JComponent
	{
		/** The width the fixed height is made for. */
		static final int SIZED_FOR = PluginPanel.PANEL_WIDTH;

		private final BufferedImage image;
		private BufferedImage scaled;

		SplashImage(BufferedImage image)
		{
			this.image = image;
			setName("splashImage");
			setAlignmentX(Component.LEFT_ALIGNMENT);
		}

		/** The fixed height, whatever the width. */
		int fixedHeight()
		{
			return heightFor(SIZED_FOR);
		}

		int heightFor(int width)
		{
			return Math.max(1, Math.round(image.getHeight() * (float) width / image.getWidth()));
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(SIZED_FOR, fixedHeight());
		}

		@Override
		public Dimension getMinimumSize()
		{
			return new Dimension(0, getPreferredSize().height);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			int w = getWidth();
			if (w <= 0)
			{
				return;
			}
			if (scaled == null || scaled.getWidth() != w)
			{
				scaled = ImageUtil.resizeImage(image, w, heightFor(w));
			}
			// centred on the fixed height: cropped top and bottom when wider, banded when narrower
			int y = (getHeight() - scaled.getHeight()) / 2;
			g.drawImage(scaled, 0, y, null);
		}
	}

	// ---- helpers ----

	/** Opens a link only if it goes where that kind of link should; otherwise says so. */
	private void go(String url, java.util.Set<String> hosts)
	{
		String safe = safeLinks.checked(url, hosts);
		if (safe != null)
		{
			listener.open(safe);    // the form that was checked, not the raw string
		}
		else
		{
			status.setText(html("That link didn't look right, so it wasn't opened."));
		}
	}

	private String linkOr(java.util.function.Function<LinksResponse, String> pick, String fallback)
	{
		LinksResponse l = linksResult == null ? null : linksResult.getValue();
		String v = l == null ? null : pick.apply(l);
		return notBlank(v) ? v : fallback;
	}

	private static boolean isError(ApiResult<?> r)
	{
		return r != null && r.getStatus() == ApiResult.Status.ERROR;
	}

	private static Instant parse(String iso)
	{
		try
		{
			return iso == null ? null : Instant.parse(iso);
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/** Escapes text from the site (event text, RSNs) before it goes into Swing HTML. */
	static String esc(String s)
	{
		return Widgets.esc(s);
	}
}
