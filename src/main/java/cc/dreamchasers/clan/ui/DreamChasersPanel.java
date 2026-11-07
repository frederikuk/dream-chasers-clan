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
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseEvent;
import java.awt.event.MouseAdapter;
import java.awt.image.BufferedImage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
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
	/** The same steps are in the README's "Connecting" section. */
	static final String HOW_TO_CONNECT = "<b>How to connect</b><br>"
		+ "1. Press <b>Connect</b>. Your browser opens dream-chasers.cc.<br>"
		+ "2. Sign in with Discord, if it asks.<br>"
		+ "3. Check the code on the page matches this panel, then press <b>Allow</b>.<br>"
		+ "The panel updates by itself.";
	/** "Enable everything" was ticked before signing in, so it turned itself off. */
	static final String ENABLE_ALL_REFUSED = "<b>Enable everything</b> needs you signed in first, so it turned itself off. "
		+ "Follow the steps below, then close and reopen the settings and tick it again.";
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
		+ "&bull; Press <b>Remove me</b> at any time: the site deletes your model and splash, and your profile stops using it.<br>"
		+ "Stand still in game, then press the button.";
	/**
	 * The featured splash is 450×253, made for 225 wide. Inside the panel's and
	 * the section's borders there are 193 pixels, so it's drawn at that.
	 */
	static final int SPLASH_WIDTH = PluginPanel.PANEL_WIDTH - 16 - 16;

	public interface Listener
	{
		/** The panel became visible (or the plugin wants fresh data); {@code force} skips the cache. */
		void refresh(boolean force);

		void link(String code);

		void signOut();

		/** "Already verified on another PC?" then Connect: opt in, then approve in the browser. */
		void connect();

		/** A join step (Join our Discord, Open dream-chasers.cc/join) was pressed: that is the opt-in to Connect. */
		default void optIn()
		{
		}

		void cancelConnect();

		/** "Not now" on the consent card. */
		void dismissConnect();

		/** Verify for Discord: the member typed a join code from dream-chasers.cc/join. */
		default void verifyPeek(String code)
		{
		}

		/** Verify for Discord: Confirm on "This links RSN to Discord NAME". */
		default void verifyConfirm()
		{
		}

		/** Verify for Discord: Cancel; nothing more is sent. */
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

	private final JLabel status = new JLabel();
	private final JPanel account = section();
	private final JPanel verify = section();
	private final JPanel roster = section();
	private final JPanel everything = section();
	private final JPanel clogHint = section();
	private final JPanel live = section();
	private final JPanel splash = section();
	private final JPanel events = section();
	private final JPanel competition = section();
	private final JPanel hallOfFame = section();
	private final JPanel tiles = section();
	private final JPanel links = section();
	private final Timer ticker;

	private boolean connected;
	/** Every feature setting is on (then the Enable everything line hides). */
	private boolean allOn;
	private String signedInAs;
	private ApiResult<EventsResponse> eventsResult;
	private ApiResult<CompetitionResponse> competitionResult;
	private ApiResult<HallOfFameResponse> hallOfFameResult;
	private ApiResult<LinksResponse> linksResult;
	private String linkMessage;
	private boolean linking;
	// connect from the panel
	private boolean connectDismissed;
	private String waitingCode;
	private String connectMessage;
	private boolean showCodeEntry;
	/** "Already verified on another PC?" is open: the browser-approve Connect and the link code. */
	private boolean showOtherPc;
	/** Officer roster sync's one line; null hides it. */
	private String rosterLine;
	// verify for Discord (verified join)
	private String verifyBlocker = "Log in to the game to verify";
	private String verifyRsn;
	private boolean verifyOpen;
	/** The logged-in name when the site has verified it to this member: Verify for Discord folds to one line. */
	private String verifiedAs;
	/** "Enable everything" was ticked before signing in (so it unticked itself): the Connect card says why. */
	private boolean enableAllRefused;
	/** Sections the member folded (by key); only their title row shows. */
	private final Set<String> collapsed = new LinkedHashSet<>();
	private boolean verifyBusy;
	private String verifyDiscordName;
	private String verifyMessage;
	private boolean verifyMessageIsError;
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
	private Image featuredImage;
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
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

		add(header(mark));
		status.setForeground(Brand.MUTED);
		status.setFont(FontManager.getRunescapeSmallFont());
		status.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(status);
		add(Box.createVerticalStrut(6));
		for (JPanel p : new JPanel[]{account, verify, roster, everything, clogHint, live, splash, events, competition, hallOfFame, tiles, links})
		{
			add(p);
			add(Box.createVerticalStrut(8));
		}

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
			signedInAs = name == null || name.isEmpty() ? null : name;
			if (signedInAs != null)
			{
				enableAllRefused = false;
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

	public void showLinks(ApiResult<LinksResponse> result)
	{
		onEdt(() ->
		{
			boolean hadInvite = discordInvite() != null;
			linksResult = result;
			renderLinks();
			if (hadInvite != (discordInvite() != null))
			{
				renderAccount();     // step 1 of the join card names the invite; only then, so nothing typed is lost
			}
		});
	}

	public void setConnectDismissed(boolean dismissed)
	{
		onEdt(() ->
		{
			connectDismissed = dismissed;
			renderAccount();
			renderVerify();
		});
	}

	/** Waiting for the browser (code shown), or idle again with an optional message. */
	public void setConnectWaiting(String code, String message)
	{
		onEdt(() ->
		{
			waitingCode = code;
			connectMessage = message;
			renderAccount();
			renderVerify();
		});
	}

	/**
	 * What the player's own clan record says: {@code blocker} is the line to
	 * show while verifying isn't possible (null once it says Dream Chasers).
	 */
	public void setOwnClan(String blocker, String rsn)
	{
		onEdt(() ->
		{
			verifyBlocker = blocker;
			verifyRsn = rsn;
			renderVerify();
		});
	}

	/** Peek worked: show whose Discord the code belongs to, with Confirm and Cancel. */
	public void verifyAsk(String rsn, String discordName)
	{
		onEdt(() ->
		{
			verifyBusy = false;
			verifyRsn = rsn;
			verifyDiscordName = discordName;
			verifyMessage = null;
			renderVerify();
		});
	}

	/** Officer roster sync's status line (officers with the setting on only); null hides it. */
	public void setRosterStatus(String line)
	{
		onEdt(() ->
		{
			rosterLine = line;
			renderRoster();
		});
	}

	/** "Enable everything" was ticked before signing in; cleared by signing in. */
	public void setEnableAllRefused(boolean refused)
	{
		onEdt(() ->
		{
			enableAllRefused = refused;
			renderAccount();
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

	/** The logged-in name once it is verified (Verify for Discord folds to one line), else null. */
	public void setVerifiedAs(String rsn)
	{
		onEdt(() ->
		{
			if (rsn != null && !rsn.equals(verifiedAs) && !verifyMessageIsError)
			{
				verifyOpen = false;
				verifyMessage = null;
			}
			verifiedAs = rsn;
			renderVerify();
		});
	}

	/** Verify for Discord is over; {@code message} goes under the field. */
	public void verifyDone(String message, boolean isError)
	{
		onEdt(() ->
		{
			verifyBusy = false;
			verifyDiscordName = null;
			verifyMessage = message;
			verifyMessageIsError = isError;
			renderVerify();
		});
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

	/** A downloaded splash, drawn at {@link #SPLASH_WIDTH}. Kept until a newer one loads. */
	public void showFeaturedImage(String rsn, BufferedImage image)
	{
		if (image == null || image.getWidth() <= 0)
		{
			return;
		}
		int h = Math.max(1, image.getHeight() * SPLASH_WIDTH / image.getWidth());
		Image scaled = image.getScaledInstance(SPLASH_WIDTH, h, Image.SCALE_SMOOTH);
		onEdt(() ->
		{
			featuredImage = scaled;
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

	/** After Feature me or Remove me; {@code message} goes under the buttons. */
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

	public void linkFinished(String message)
	{
		onEdt(() ->
		{
			linking = false;
			linkMessage = message;
			renderAccount();
		});
	}

	// ---- rendering (Swing thread) ----

	private void renderAll()
	{
		renderStatus();
		renderAccount();
		renderVerify();
		renderRoster();
		renderEverything();
		clogHint.setVisible(clogHint.getComponentCount() > 0);
		renderLive();
		renderSplash();
		renderEvents();
		renderCompetition();
		renderHallOfFame();
		renderTiles();
		renderLinks();
	}

	private void renderStatus()
	{
		if (!connected)
		{
			status.setText("");
			return;
		}
		boolean failed = isError(competitionResult) || isError(hallOfFameResult) || isError(eventsResult);
		status.setText(failed ? html("Couldn't refresh just now; showing the last copy.") : "");
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
			events.add(text("Events are for clan members. Follow the steps above to see them here.", Brand.MUTED));
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
				if (list.size() > 1)
				{
					events.add(Box.createVerticalStrut(6));
					events.add(text("<b>Coming up</b>", Brand.GOLD));
					for (EventInfo e : list.subList(1, Math.min(list.size(), UPCOMING_SHOWN + 1)))
					{
						events.add(text(esc(e.getTitle()) + "<br><font color='" + Brand.hex(Brand.MUTED) + "'>"
							+ TimeText.when(e.startInstant(), zone) + "</font>", Brand.TEXT));
					}
				}
			}
		}
		events.add(Box.createVerticalStrut(4));
		JPanel footer = row();
		footer.add(small("Signed in as " + signedInAs));
		events.add(footer);
		JPanel buttons = row();
		buttons.add(button("Events page", () -> go(linkOr(l -> l.getEvents(), SITE + "/events"), safeLinks.site())));
		buttons.add(button("Sign out", listener::signOut));
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

	/**
	 * The join card, for anyone not signed in: one path, each step its own
	 * button. 1 Join our Discord, 2 Open dream-chasers.cc/join, 3 Verify for
	 * Discord (its own card, under this one). Pressing step 1 or 2 is the
	 * opt-in: it turns Connect on. The browser-approve sign-in waits behind
	 * "Already verified on another PC?". Hidden once signed in.
	 */
	private void renderAccount()
	{
		account.removeAll();
		account.setVisible(signedInAs == null);
		if (signedInAs != null)
		{
			done(account);
			return;
		}
		if (!connected && connectDismissed)
		{
			if (header(account, "account", "Not connected"))
			{
				done(account);
				return;
			}
			if (enableAllRefused)
			{
				account.add(text(ENABLE_ALL_REFUSED, Brand.GOLD));
			}
			account.add(text("Join to see clan events, the competition and the Hall of Fame, and to join DRM Live.", Brand.MUTED));
			JPanel b = row();
			b.add(primary("Join…", () -> onEdt(() ->
			{
				connectDismissed = false;
				renderAccount();
				renderVerify();
			})));
			account.add(b);
			done(account);
			return;
		}
		if (waitingCode != null)
		{
			if (header(account, "account", "Connecting"))
			{
				done(account);
				return;
			}
			account.add(text("Waiting for you to approve in the browser…<br>Check the page shows this code:", Brand.TEXT));
			JLabel code = new JLabel(esc(waitingCode));
			code.setForeground(Brand.GOLD);
			code.setFont(FontManager.getRunescapeBoldFont().deriveFont(24f));
			code.setAlignmentX(Component.LEFT_ALIGNMENT);
			account.add(code);
			account.add(text("Discord signs in with the account open on discord.com in your browser: use your main one.", Brand.MUTED));
			if (connectMessage != null)
			{
				account.add(text(esc(connectMessage), Brand.WARN));
			}
			JPanel b = row();
			b.add(button("Cancel", listener::cancelConnect));
			account.add(b);
			done(account);
			return;
		}
		if (header(account, "account", "Join Dream Chasers"))
		{
			done(account);
			return;
		}
		if (enableAllRefused)
		{
			account.add(text(ENABLE_ALL_REFUSED, Brand.GOLD));
		}
		if (!connected)
		{
			account.add(text("Link your RSN to the clan's Discord. This panel then shows clan events, the competition and the Hall of Fame, and lets you join DRM Live.", Brand.TEXT));
			account.add(text("&bull; Step 1 or 2 turns on Connect to dream-chasers.cc.<br>"
				+ "&bull; Your IP address goes to dream-chasers.cc, a server not run or checked by RuneLite.<br>"
				+ "&bull; It never sees your Jagex or Discord password.", Brand.TEXT));
		}
		// the invite comes with /links, which only loads once Connect is on: before that, say where it is
		boolean invite = discordInvite() != null;
		account.add(text(invite ? "<b>1.</b> Join the clan's Discord."
			: "<b>1.</b> Join the clan's Discord. The invite is on dream-chasers.cc/join.", Brand.TEXT));
		JPanel step1 = row();
		step1.add(primary(invite ? "Join our Discord" : "Get the Discord invite", this::joinDiscord));
		account.add(step1);
		account.add(text("<b>2.</b> On dream-chasers.cc/join press <b>Connect Discord</b>. It shows a code.", Brand.TEXT));
		JPanel step2 = row();
		step2.add(primary("Open dream-chasers.cc/join", this::openJoin));
		account.add(step2);
		account.add(text("<b>3.</b> Type the code in <b>Verify for Discord</b> "
			+ (connected ? "below" : "(it appears below after step 1 or 2)") + ", then press Verify.", Brand.TEXT));
		if (!connected)
		{
			JPanel b = row();
			b.add(button("Not now", listener::dismissConnect));
			b.add(button("Privacy", () -> go(linkOr(LinksResponse::getSite, SITE) + "/privacy", safeLinks.site())));
			account.add(b);
		}
		else
		{
			ApiResult<EventsResponse> r = eventsResult;
			if (r != null && r.getStatus() == ApiResult.Status.UNAUTHORIZED && r.getMessage() != null
				&& !"Not signed in".equals(r.getMessage()))
			{
				account.add(text(esc(r.getMessage()) + ".", Brand.WARN));
			}
		}
		if (connectMessage != null)
		{
			account.add(text(esc(connectMessage), Brand.WARN));
		}
		JPanel other = row();
		other.add(button(showOtherPc ? "Hide" : "Already verified on another PC?", () -> onEdt(() ->
		{
			showOtherPc = !showOtherPc;
			renderAccount();
		})));
		account.add(other);
		if (showOtherPc)
		{
			account.add(text(HOW_TO_CONNECT, Brand.TEXT));
			JPanel b = row();
			b.add(primary("Connect", listener::connect));
			account.add(b);
			if (connected)
			{
				JPanel more = row();
				more.add(button(showCodeEntry ? "Hide code entry" : "Have a code instead?", () -> onEdt(() ->
				{
					showCodeEntry = !showCodeEntry;
					renderAccount();
				})));
				account.add(more);
				if (showCodeEntry)
				{
					renderCodeEntry();
				}
			}
		}
		done(account);
	}

	/** Step 1: the site's Discord invite when it has given one, else the join page (which has it too). */
	private void joinDiscord()
	{
		listener.optIn();
		String invite = discordInvite();
		if (invite != null)
		{
			go(invite, SafeLinks.DISCORD);
		}
		else
		{
			openJoinPage();
		}
	}

	/** The site's Discord invite from /links, or null before links have loaded. */
	private String discordInvite()
	{
		LinksResponse l = linksResult == null ? null : linksResult.getValue();
		return l != null && notBlank(l.getDiscord()) ? l.getDiscord() : null;
	}

	/** Step 2. */
	private void openJoin()
	{
		listener.optIn();
		openJoinPage();
	}

	private void openJoinPage()
	{
		go(linkOr(LinksResponse::getSite, SITE) + "/join", safeLinks.site());
	}

	/** Officer roster sync: one muted line under Verify for Discord, only when the plugin gives one. */
	private void renderRoster()
	{
		roster.removeAll();
		String line = rosterLine;
		roster.setVisible(line != null);
		if (line != null)
		{
			// the site's own messages already end in a full stop
			boolean ended = line.endsWith(".") || line.endsWith("!") || line.endsWith("?");
			roster.add(text(esc(line) + (ended ? "" : "."), Brand.MUTED));
		}
		done(roster);
	}

	/**
	 * Verify for Discord (verified join): the member types the code from
	 * dream-chasers.cc/join. Only while Connect is on; usable only when the
	 * player's own clan record says Dream Chasers. Signed-in members see it
	 * folded behind one button (it also gives them the Verified role).
	 */
	private void renderVerify()
	{
		verify.removeAll();
		boolean folded = signedInAs != null && !verifyOpen && verifyDiscordName == null && !verifyBusy && verifyMessage == null;
		boolean show = connected;     // with Connect on, also while the browser-approve sign-in waits
		verify.setVisible(show);
		if (!show)
		{
			done(verify);
			return;
		}
		if (verifiedAs != null && !verifyOpen && !verifyBusy && verifyDiscordName == null && verifyMessage == null)
		{
			verify.add(text("Verified as <b>" + esc(verifiedAs) + "</b>.", Brand.MUTED));
			done(verify);
			return;
		}
		if (folded)
		{
			JPanel b = row();
			b.add(button("Verify for Discord…", () -> onEdt(() ->
			{
				verifyOpen = true;
				renderVerify();
			})));
			verify.add(b);
			done(verify);
			return;
		}
		if (header(verify, "verify", "Verify for Discord"))
		{
			done(verify);
			return;
		}
		if (verifyDiscordName != null)
		{
			verify.add(text("This links <b>" + esc(verifyRsn) + "</b> to Discord <b>" + esc(verifyDiscordName) + "</b>. "
				+ "Only continue if that Discord account is yours. If someone sent you this code, press Cancel.", Brand.TEXT));
			JPanel b = row();
			JButton confirm = primary("Confirm", () ->
			{
				verifyBusy = true;
				verifyMessage = "Linking…";
				verifyMessageIsError = false;
				verifyDiscordName = null;
				renderVerify();
				listener.verifyConfirm();
			});
			b.add(confirm);
			b.add(button("Cancel", () ->
			{
				verifyDiscordName = null;
				verifyMessage = "Cancelled. Nothing was linked.";
				verifyMessageIsError = false;
				renderVerify();
				listener.verifyCancel();
			}));
			verify.add(b);
			done(verify);
			return;
		}
		if (signedInAs != null)
		{
			verify.add(text("On dream-chasers.cc/join press <b>Connect Discord</b>, then type the code it shows here.", Brand.MUTED));
			JPanel getCode = row();
			getCode.add(button("Get a code", this::openJoinPage));
			verify.add(getCode);
		}
		else
		{
			verify.add(text("Type the code from dream-chasers.cc/join here.", Brand.MUTED));
		}
		boolean ready = verifyBlocker == null;
		if (!ready)
		{
			verify.add(text(esc(verifyBlocker) + ".", Brand.WARN));
		}
		JTextField code = new JTextField();
		code.setToolTipText("The 8-character code from dream-chasers.cc/join, like ABCD-2345");
		code.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		code.setAlignmentX(Component.LEFT_ALIGNMENT);
		code.setEnabled(ready && !verifyBusy);
		Runnable submit = () ->
		{
			if (verifyBusy || verifyBlocker != null)
			{
				return;
			}
			verifyBusy = true;
			verifyMessage = "Checking the code…";
			verifyMessageIsError = false;
			String value = code.getText();
			renderVerify();
			listener.verifyPeek(value);
		};
		code.addActionListener(e -> submit.run());
		verify.add(code);
		JPanel b = row();
		JButton go = button("Verify", submit);
		go.setEnabled(ready && !verifyBusy);
		b.add(go);
		if (signedInAs != null && !verifyBusy)
		{
			b.add(button("Hide", () -> onEdt(() ->
			{
				verifyOpen = false;
				verifyMessage = null;
				renderVerify();
			})));
		}
		verify.add(b);
		if (verifyMessage != null)
		{
			verify.add(text(esc(verifyMessage), verifyMessageIsError ? Brand.WARN : verifyBusy ? Brand.MUTED : Brand.TEXT));
		}
		done(verify);
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
		if (header(splash, "splash", "Clan splash"))
		{
			done(splash);
			return;
		}
		ApiResult<FeaturedResponse> fr = featuredResult;
		FeaturedResponse f = fr == null ? null : fr.getValue();
		if (featuredImage != null)
		{
			JLabel img = new JLabel(new ImageIcon(featuredImage));
			img.setAlignmentX(Component.LEFT_ALIGNMENT);
			splash.add(img);
			splash.add(text("Featured: <b>" + esc(featuredImageRsn) + "</b>", Brand.GOLD));
		}
		else if (f != null && !notBlank(f.getRsn()))
		{
			splash.add(text("Nobody is featured yet.", Brand.MUTED));
		}
		else if (fr != null && f == null)
		{
			splash.add(text(esc(fr.getMessage()), Brand.MUTED));
		}

		if (signedInAs != null)
		{
			if (splashOptedIn)
			{
				ModelStatusResponse ms = modelStatus == null ? null : modelStatus.getValue();
				String st = ms == null || ms.getStatus() == null ? "" : ms.getStatus();
				switch (st)
				{
					case "ok":
						splash.add(text("You're in the pool: any day could be yours.", Brand.TEXT));
						break;
					case "rejected":
						splash.add(text("The splash maker couldn't use your last pose"
							+ (notBlank(ms.getReason()) ? ": " + esc(ms.getReason()) + "." : ". Stand still in game and press <b>Send a new pose</b>."), Brand.WARN));
						break;
					case "queued":
						splash.add(text("Your model is sent. Your splash is being made; check back in an hour.", Brand.TEXT));
						break;
					default:
						splash.add(text("You're signed up for the clan splash.", Brand.TEXT));
				}
				JPanel b = row();
				if (splashEnabled)
				{
					b.add(button("Send a new pose", () -> splashAction(listener::featureMe)));
				}
				b.add(button("Remove me", () -> splashAction(listener::removeMe)));
				splash.add(b);
			}
			else if (!splashEnabled)
			{
				splash.add(text("Want to be the statue? Turn on <b>Clan splash</b> in this plugin's settings.", Brand.MUTED));
			}
			else
			{
				splash.add(text(SPLASH_CONSENT, Brand.TEXT));
				JPanel b = row();
				b.add(primary("Feature me on the clan splash", () -> splashAction(listener::featureMe)));
				splash.add(b);
			}
			if (splashBusy)
			{
				splash.add(text("Working…", Brand.MUTED));
			}
			else if (splashMessage != null)
			{
				splash.add(text(esc(splashMessage), splashMessageIsError ? Brand.WARN : Brand.MUTED));
			}
		}
		done(splash);
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

	private void renderCodeEntry()
	{
		account.add(text("Get a code on dream-chasers.cc/plugin, paste it here and press <b>Link</b>.", Brand.MUTED));
		JPanel getCode = row();
		// the site from /links, so a staging build opens staging's sign-in page
		getCode.add(button("Get a code", () -> go(linkOr(LinksResponse::getSite, SITE) + "/plugin", safeLinks.site())));
		account.add(getCode);

		JTextField code = new JTextField();
		code.setToolTipText("The code from dream-chasers.cc/plugin, like DRM-7KQ4-P2XM");
		code.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		code.setAlignmentX(Component.LEFT_ALIGNMENT);
		Runnable submit = () ->
		{
			if (linking)
			{
				return;
			}
			linking = true;
			linkMessage = "Linking…";
			String value = code.getText();
			renderAccount();
			listener.link(value);
		};
		code.addActionListener(e -> submit.run());
		account.add(code);
		JPanel linkRow = row();
		JButton link = button("Link", submit);
		link.setEnabled(!linking);
		linkRow.add(link);
		account.add(linkRow);
		if (linkMessage != null)
		{
			account.add(text(esc(linkMessage), linking ? Brand.MUTED : Brand.WARN));
		}
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

	private void renderLinks()
	{
		links.removeAll();
		if (header(links, "links", "Dream Chasers"))
		{
			done(links);
			return;
		}
		JPanel buttons = row();
		LinksResponse l = linksResult == null ? null : linksResult.getValue();
		if (l != null && notBlank(l.getDiscord()))
		{
			JButton discord = button("Join our Discord", () -> go(l.getDiscord(), SafeLinks.DISCORD));
			discord.setForeground(Brand.VOID);
			discord.setBackground(Brand.GOLD);
			buttons.add(discord);
		}
		buttons.add(button("dream-chasers.cc", () -> go(linkOr(LinksResponse::getSite, SITE), safeLinks.site())));
		links.add(buttons);
		done(links);
	}

	// ---- widgets ----

	/** The site's Dream ♥ Chasers wordmark across the top, as on dream-chasers.cc. */
	private JPanel header(BufferedImage wordmark)
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		if (wordmark != null)
		{
			int w = WORDMARK_WIDTH, h = Math.max(1, Math.round(wordmark.getHeight() * (float) w / wordmark.getWidth()));
			JLabel img = new JLabel(new ImageIcon(ImageUtil.resizeImage(wordmark, w, h)));
			img.setToolTipText("Dream Chasers");
			p.add(img, BorderLayout.CENTER);
			p.setMaximumSize(new Dimension(Integer.MAX_VALUE, h + 4));
		}
		else
		{
			JLabel name = new JLabel(html("<b>DREAM CHASERS</b>"));
			name.setForeground(Brand.GOLD);
			name.setFont(FontManager.getRunescapeBoldFont());
			p.add(name, BorderLayout.CENTER);
			p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		}
		return p;
	}

	/** The panel's inner width: RuneLite's 225 less this panel's 8 px border each side. */
	private static final int WORDMARK_WIDTH = 209;

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
