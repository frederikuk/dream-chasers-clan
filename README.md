# Dream Chasers Clan (RuneLite plugin)

The Dream Chasers clan's own RuneLite plugin. It adds a sidebar panel with:

- **Events**: the next clan event (with a countdown, or LIVE) and what's coming
  up. Events are for clan members only, so this needs a one-time sign-in (below).
- **Competition**: the clan's current Wise Old Man competition and its top 10.
- **Hall of Fame**: the most recent clan firsts.

Everything comes from the clan site, `https://dream-chasers.cc`. The Dream
Chasers wordmark at the top opens the site. The round player button in the top
right corner is always there and opens the player menu: who you're signed in as
("Verified as …" once your name is verified, "Not signed in" before), **Sign in
with Discord** (or **Verify NAME for Discord** for a logged-in name that isn't
verified yet), **Enter a code from the site…**, **Join our Discord**, the site,
and **Sign out**. When signing in isn't possible yet, the menu says why in a
greyed-out line.

There is no sign-in box in the sidebar. Signing in starts in the plugin's
settings (see Sign in, below), and one status line under the header says what
is happening: "Approve in your browser. Check the page shows AB12.", the
answer once it's done, or "Turn on Connect to dream-chasers.cc in the plugin
settings." while Connect is off.

Top to bottom the panel runs: the status line, **Events**, **Competition**,
**Hall of Fame**, **Tile packs**, **DRM Live**, and the **Clan splash** across
the full width at the bottom.
Two opt-in extras sit in the same panel: **DRM Live** (get on the clan stream)
and the **Clan splash** (be the featured member's statue). Further opt-ins post
your own personal bests and new collection log slots to the clan's Hall of Fame,
share your raid stats while you're on DRM Live, and copy the clan's tile packs.

![Signed in](docs/panel-signed-in.png) ![Not signed in](docs/panel-signed-out.png)

(Rendered headless by `DreamChasersPanelTest` from the test fixtures; the test writes fresh copies to `build/`.)

## Settings

Every setting below that talks to dream-chasers.cc is off until you turn it on.
RuneLite shows them in five sections: Connect, Appearance, Data, PvM and
Livestream.
Sign in first: tick **Connect to dream-chasers.cc**, the first setting (see
Sign in, below). Once you're signed in, the panel
offers **Enable everything** (with the IP-address warning next to it), and the
second setting does the same. It switches on Post personal bests, Post
collection log, Clan splash, Clan tile packs, Join DRM Live and Share raid
stats. Before you're signed in it does nothing: the setting unticks itself and
the panel's status line says why. It
never agrees to the DRM Live stream consent or sends your character's model;
those stay their own buttons. Untick it to turn them all off again. Turn one
feature off by hand and it unticks itself, leaving the others as they are;
turn all of them on by hand and it ticks itself. RuneLite's settings show these
changes once you close and reopen them.

- **Connect to dream-chasers.cc** (off by default). Nothing is fetched until you
  turn this on; the panel never turns it on by itself. As with every plugin
  that talks to a server outside RuneLite, this sends your IP address to that
  server (dream-chasers.cc), and RuneLite asks you to agree to that when you
  tick it. Ticking it while signed out also signs you in: once you're logged in
  to the game, your browser opens dream-chasers.cc to sign in with Discord
  (see Sign in, below). It reads only your own clan record.
- **Officer roster sync** (off by default, officers only). When you close the
  clan **Members** screen in game, the plugin sends the clan's member list
  (names and rank titles, nothing else) to dream-chasers.cc, which updates the
  clan's Wise Old Man group. It also verifies newcomers who say their witness
  code in clan chat or PM it to you. See Officer roster sync (below). Same
  IP-address warning. **Enable everything** leaves it alone.
- **Join DRM Live** (off by default). Shows the DRM Live section (below). Same
  IP-address warning.
- **Clan splash** (off by default). Shows the **Feature me on the clan
  splash** button (below). Same IP-address warning.
- **Post personal bests** (off by default). When the game tells you a boss,
  raid or Sailing Barracuda Trial time ("Fight duration: … (new personal best)", raid completion times),
  the plugin sends it to the clan's Hall of Fame: boss, time, kill count, team
  size where the game gives it, your RSN and your hashed account id (see
  Verify for Discord). The PBs RuneLite already knows
  (its !pb store) are sent too, at most once a day (again if you switch accounts that day) and only when they've changed, so you don't have to re-kill every boss. Only your own kills, and only
  while you're connected. Same IP-address warning.
- **Post collection log** (off by default). Each new collection log slot goes
  to the clan's Hall of Fame, with where it came from, your RSN and your hashed
  account id. Turn on the game's own
  setting **Collection log - New addition notification** (Settings, Chat; chat,
  popup or both); the panel tells you if it's off. Your own drops worth the clan's big-drop amount (10M today, never below 1M; the same for
  everyone, set by the site) are posted too, priced with RuneLite's own item
  prices (no extra downloads). PvP kills are never posted. Same IP-address warning.
- **Share raid stats on DRM Live** (off by default). While you're on the clan
  stream and inside Chambers of Xeric, Theatre of Blood or Tombs of Amascut,
  the plugin sends your own hitpoints, prayer and inventory to dream-chasers.cc
  at most every 5 seconds, for a small box on your stream tile. Nothing about
  other players. It needs the stream consent, and the site keeps nothing. Same
  IP-address warning.
- **Clan tile packs** (off by default). Lists the clan's ground-marker packs
  (published by officers) with a **Copy** button. Copy a pack, then in game
  right-click the world map orb and use RuneLite's own Ground Markers
  **Import**. The plugin only uses the clipboard; it never changes Ground
  Markers' settings itself. Same IP-address warning.
- **Show sidebar button**.

The clan's PB and loot feeds live on the site
([dream-chasers.cc/hall-of-fame](https://dream-chasers.cc/hall-of-fame)), not
inside RuneLite.

## Sign in (and Verify for Discord)

Signing in and getting the clan Discord's **Verified** role are one step, and
it works for new and returning members alike. It needs **Connect to
dream-chasers.cc** on (off by default, with the IP-address warning) and works
only when your own clan record in game says the clan's in-game name, DRM
Chasers.

1. In the plugin's settings tick **Connect to dream-chasers.cc** and agree to
   the warning. Once you're logged in to the game, the plugin opens your
   browser by itself. (Signed out later, or want to try again? The player menu
   at the top right of the panel has **Sign in with Discord**.) If signing in
   isn't possible yet, the menu says why in a greyed-out line: "Log in to the
   game to sign in", "Join the clan in game first", "Your clan in game isn't
   DRM Chasers. Join DRM Chasers first", or "Join DRM Chasers in game first,
   or open your clan tab and wait a few seconds".
2. Your browser opens a dream-chasers.cc page (sign in with Discord if it
   asks). It says "Link game account **your RSN** (DRM Chasers) to your
   Discord **a name**?". Only press **Approve** if you started this in your
   own RuneLite just now; if someone sent you the link, press **Deny**. On a
   different network from the PC the page also asks for the 4-character code
   the panel's status line shows. **Cancel** in the player menu stops waiting.
3. The panel picks up the answer by itself: it is signed in, and the Verified
   role arrives in Discord within about a minute once you're in the server.
   If your name isn't on the clan roster yet, or an officer has to check, the
   status line says so and dream-chasers.cc/join tells you what's next.

Have a code from dream-chasers.cc/join instead? **Enter a code from the
site…** in the player menu asks for it, then asks you to check the Discord name
it belongs to before anything is linked.

Already signed in on another name? The player menu offers **Verify NAME for
Discord** for the logged-in name until it's verified; run it once to get the
Verified role and protect your name.

**What the plugin reads:** only your own clan record: the clan's name and
whether you're in it. Never the member list, never anyone else.

**What it sends** (to dream-chasers.cc, and only when you sign in or enter a code):
the code; your RSN; the clan's name as the game shows it; the plugin's version;
"RuneLite" as the device name; and a **hashed account id**: a one-way SHA-256
of `"drm-clan-v1|"` and RuneLite's account hash. The account hash itself never
leaves your PC, and the site can't turn the hashed id back into it. It stops
someone else taking your name. Your clan rank and join date are not sent.
With posting turned on, your PBs, old PBs, collection log slots and big drops
carry the same hashed account id, so the site can tell they come from the
account that verified.

## Officer roster sync

For officers (Summoner, Illusionist, Wizard, Warlock, Necromancer and up). With
**Officer roster sync** on and the panel signed in, closing the clan
**Members** screen (Clan settings, Members: where you rank someone up) sends
the clan's member list to dream-chasers.cc, and the site updates the Wise Old
Man group. A rank-up to Gnome Child reaches the site within seconds, with no
extra button. This is the same list the Wise Old Man plugin's own group sync
reads.

- **What it sends:** each member's name and rank title, the clan's name as the
  game shows it, and your own RSN. Never locations, gear or stats.
- **When:** a moment after you close the Members screen, and only when the list
  has changed since the site last took it. At most once a minute: a change
  inside the minute is sent by itself once the minute is up, and the panel
  says when ("Changed again: sending at 12:35"). If the site was busy or
  couldn't be reached, the same list goes once more a minute later.
- The player menu (top right) shows "Roster sync is on". If the site says
  you're not an officer, the sync stops until you log in again or tick the
  setting again, and the menu shows "Roster sync is off".

### Verify pickup

The same setting verifies newcomers for you. A member on mobile gets a witness
code at dream-chasers.cc/join and says it in clan chat, or PMs it to you. The
plugin sends that code and the sender's name (which the game sets, so it
proves the account) to the site, and the site verifies them. Nobody types
anything. The panel shows "Verified Bob 12:34", or says when a request needs
an officer to look at it on the site (a new Discord account, a disputed name,
an officer-rank name and a few others).

- **What it reads:** clan chat and the PMs you receive. Only a line holding a
  code (`DRM-` and six characters) leaves your PC, and a PM only from someone
  in the clan. Every other line is ignored.
- **What it sends:** the code, the sender's name, whether it was clan chat or
  a PM, and your own RSN. At most 20 in 10 minutes, two codes per sender.
- **Online dot:** while the setting is on, the plugin tells the site every 5
  minutes that an officer's plugin is online, and that it's gone when you log
  out or turn it off. The join page shows newcomers a green dot when saying
  the code will work.

## The older sign-in routes

Before 0.5.4 the panel also had a browser-approval **Connect** (behind
"Already verified on another PC?") and a link-code field for codes from
dream-chasers.cc/plugin. Sign in with Discord now signs returning members in
too, so the panel no longer shows either.

The plugin only ever stores a token for dream-chasers.cc. **Sign out** in the
player menu (top right of the panel) revokes it, and you can also revoke it on dream-chasers.cc/plugin.

## DRM Live: get on the clan stream

With **Join DRM Live** on and the panel connected, the DRM Live section shows
whether a show is live or open for guests.

1. The first time, it shows the stream consent: your game screen and voice go
   out live and get recorded; share the OSRS window only; never type a bank PIN
   while sharing; you can leave at any time. Press **Agree**. That also gives
   you the On stream role in Discord, so the stream can hear you.
2. Press **Join the stream**. You get your own guest slot, and you wait in the
   queue until a producer puts you on air.
3. **Open my stream link** opens your personal VDO.Ninja link in the browser.
   Press Start and pick the RuneLite window only. Don't share system audio.
4. **Join the stream voice channel** opens Discord at the stream's voice
   channel. Talk there, not in the browser.
5. **Leave** frees your slot. **Withdraw consent** also removes the role.

The plugin only opens links. It never captures or streams the game itself.

## Clan splash: be the statue

Once connected, the panel shows today's featured member: their character as the
dragonstone statue holding the Compass and Heart, with "Featured: <name>". A new
member is picked once a day from everyone who opted in.

To put yourself in, turn on **Clan splash**, then:

1. Read the note in the panel: your character's 3D shape, gear included, goes
   to dream-chasers.cc, and it's shown to the clan with your RSN, in the panel,
   on the site and maybe on the DRM Live stream. The same model is also your
   profile picture and 3D view on your clan profile on the site.
2. Stand still in game (not walking or emoting) and press **Feature me on the
   clan splash**. If you're moving it says "Stand still and try again". Take
   off your weapon and shield first: your character holds the heart in both
   hands.
3. Your splash is made within about an hour. If the splash maker can't use the
   pose, the panel says why; stand still and press the **refresh** icon next to
   the featured name to send a new pose. You can send one every 10 minutes.

The **x** icon (Remove me) takes you out at any time, and the site deletes your model and
splash, and your profile stops using it. The plugin sends only your own character's shape, and only when you
press the button. The **eye** icon opens your profile on dream-chasers.cc.

## Development

Needs JDK 11. Gradle comes with the wrapper.

```
gradlew.bat build        # compile + tests
gradlew.bat run          # RuneLite dev client with this plugin loaded
gradlew.bat run -PdrmclanApi=https://staging.dream-chasers.cc/api/v1
```

Logging into the dev client with a Jagex account: see
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).

## Licence

BSD 2-Clause, see [LICENSE](LICENSE).
