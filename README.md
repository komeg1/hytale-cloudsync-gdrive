# Cloud Save Sync for Hytale

Play the same singleplayer world on your PC, Mac and Steam Deck. Cloud Save Sync is a Hytale mod that keeps
your worlds in **your own Google Drive** and brings the newest version to whichever device you play on.

- Sign in with Google once per device. No other software, no server, no account with anyone else.
- Choose which worlds to sync. Each world is uploaded while you play and when you leave it.
- When you open a synced world, the newest version from your other devices is loaded first.
- If two devices changed the same world, nothing is overwritten: you choose which version to keep, and the
  other one stays in your cloud history.

**Website and full documentation:** https://komeg1.github.io/hytale-cloudsync-gdrive/
**Privacy policy:** https://komeg1.github.io/hytale-cloudsync-gdrive/privacy.html

---

## Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [First steps](#first-steps)
- [Commands](#commands)
- [How it works](#how-it-works)
- [Your data and privacy](#your-data-and-privacy)
- [Good to know](#good-to-know)
- [Troubleshooting](#troubleshooting)
- [For developers](#for-developers)

---

## Requirements

- Hytale with server version **0.7.x** (singleplayer).
- A Google account with some free Google Drive space. A world usually takes 50–150 MB, and up to 5 copies
  of each synced world are kept (you can change that with [`/cloudsync keep`](#cloudsync-keep)).
- Works on **Windows**, **macOS** and **Linux / Steam Deck**.

## Installation

1. Download `CloudSaveSync-<version>.jar` from the
   [Releases](https://github.com/komeg1/hytale-cloudsync-gdrive/releases) page.
2. Put it in Hytale's `Mods` folder:

   | System | Folder |
   |---|---|
   | Windows | `%APPDATA%\Hytale\UserData\Mods` |
   | macOS | `~/Library/Application Support/Hytale/UserData/Mods` |
   | Linux | `~/.local/share/Hytale/UserData/Mods` |
   | Steam Deck / Flatpak | `~/.var/app/<app id>/data/Hytale/UserData/Mods` |

   On a Steam Deck, switch to Desktop Mode, open Konsole and run
   `find ~ -type d -path '*Hytale/UserData' 2>/dev/null` to find the exact folder. If there is no `Mods`
   folder yet, create it.
3. Start Hytale. When you create or open a world, make sure **CloudSaveSync** is enabled in that world's
   mod list.

Do this on every device you want to sync.

## First steps

**On your first device:**

1. Open a world.
2. Type `/cloudsync login`. Chat shows a link and a short code: open the link on your phone or computer,
   enter the code and allow access. Chat confirms with *"Signed in as you@gmail.com."*
3. Type `/cloudsync enable` to start syncing this world. It is uploaded right away.

From now on this world is uploaded every few minutes while you play and again when you leave it.

**On your other devices:**

1. Install the mod and open any world (a new empty one is fine).
2. Type `/cloudsync login` and sign in with the **same** Google account.
3. Type `/cloudsync list` to see your worlds in Google Drive, then `/cloudsync download 1` (use the number
   from the list).
4. Go back to the main menu: the downloaded world is in your world list. If it isn't, restart the game.

Downloaded worlds keep syncing on their own. Open them and play as usual.

## Commands

Only the world's owner can use these commands. Every command starts with `/cloudsync`.

| Command | What it does |
|---|---|
| [`login`](#cloudsync-login) | Sign in to Google Drive |
| [`code`](#cloudsync-code) | Sign in with a code (for devices without a browser) |
| [`logout`](#cloudsync-logout) | Sign out on this device |
| [`enable`](#cloudsync-enable) | Start syncing this world |
| [`disable`](#cloudsync-disable) | Stop syncing this world on this device |
| [`list`](#cloudsync-list) | List your worlds in Google Drive |
| [`download`](#cloudsync-download) | Download worlds to this device |
| [`upload`](#cloudsync-upload) | Back up this world now |
| [`status`](#cloudsync-status) | Show the sync status of this world |
| [`auto`](#cloudsync-auto) | Turn uploads while playing on or off, or set how often |
| [`keep`](#cloudsync-keep) | How many copies of this world Google Drive keeps |
| [`usecloud`](#cloudsync-usecloud--keeplocal) | After a conflict: switch to the cloud version |
| [`keeplocal`](#cloudsync-usecloud--keeplocal) | After a conflict: keep this device's version |

### `/cloudsync login`

Signs you in to Google Drive. Chat shows a link and a code, or opens a browser window where available.
You only need to do this once per device.

```
/cloudsync login
[Cloud Save] On your phone or computer, go to https://www.google.com/device and enter the code
[Cloud Save] ABCD-EFGH
[Cloud Save] Signed in as you@gmail.com.
```

Signing in never starts syncing a world on its own: use [`enable`](#cloudsync-enable) for each world.

### `/cloudsync code`

The same as `login`, but always uses a code. Use it if the browser sign-in doesn't open on your device.

### `/cloudsync logout`

Signs out on this device and asks Google to revoke the mod's access. Your worlds in Google Drive stay
there. Worlds stop syncing until you sign in again.

### `/cloudsync enable`

Starts syncing the world you're in with the account you're signed in with, and uploads it right away.

```
/cloudsync enable
[Cloud Save] This world is now synced with your Google Drive. Uploading it now...
[Cloud Save] Backed up to Google Drive (88 MB).
```

If your Google Drive already has a world with the same name, nothing is overwritten: you're asked to
choose with [`usecloud` or `keeplocal`](#cloudsync-usecloud--keeplocal).

### `/cloudsync disable`

Stops syncing this world on this device. Its copies in Google Drive stay there, and your other devices
are not affected. Use `enable` to sync it again.

### `/cloudsync list`

Shows all your worlds in Google Drive, numbered.

```
/cloudsync list
[Cloud Save] Your worlds in Google Drive:
[Cloud Save] [1] My Survival - this world, saved 5 min ago on GAMING-PC, 88 MB
[Cloud Save] [2] Creative Build - not on this device, saved 2 hours ago on Steam Deck, 120 MB
[Cloud Save] To download, type /cloudsync download and the numbers, e.g. /cloudsync download 2 or /cloudsync download 1,3
```

### `/cloudsync download`

Downloads worlds from Google Drive to this device, by their number in `/cloudsync list`. The world appears
in your world list when you go back to the main menu (restart the game if it doesn't).

```
/cloudsync download 2
/cloudsync download 1,3
/cloudsync download all
```

A world that already exists on this device with the same name is never overwritten.

### `/cloudsync upload`

Backs up the world you're in right now, instead of waiting for the next automatic upload.

```
/cloudsync upload
[Cloud Save] Uploading this world...
[Cloud Save] Uploading... 50%
[Cloud Save] Backed up to Google Drive (88 MB).
```

### `/cloudsync status`

Shows the account you're signed in with, when this world was last synced, how uploads are set up, the
result of the last upload after leaving the world, and what's in the cloud.

### `/cloudsync auto`

Uploads while playing. These settings apply to all worlds on this device. The upload when you leave a
world always happens.

```
/cloudsync auto 15     upload every 15 minutes (1–240, default 7)
/cloudsync auto off    don't upload while playing
/cloudsync auto on     turn it back on
```

### `/cloudsync keep`

How many copies of this world Google Drive keeps. The setting is stored with the world, so all your
devices follow it. Lowering it deletes the oldest copies right away.

```
/cloudsync keep        show the setting and how much space the copies use
/cloudsync keep 3      keep the 3 newest copies (1–20, default 5)
```

Versions set aside in a conflict don't count toward this number; up to 5 of them are kept separately.

### `/cloudsync usecloud` / `keeplocal`

Used after a conflict: Google Drive has a newer version of the world **and** this device has changes that
were never uploaded (for example after playing offline on two devices).

- `/cloudsync usecloud`: switch to the cloud version. Quit to the menu and reopen the world to apply it.
  This device's version is saved as a local backup first.
- `/cloudsync keeplocal`: keep this device's version and upload it as the current one. The cloud version
  stays in your cloud history.

## How it works

**Opening a synced world.** Before the world loads, the mod asks Google Drive whether there's a newer
version (it waits at most 10 seconds). If there is, and this device has no changes that were never
uploaded, the newer version is downloaded and replaces your local copy, with progress shown on the loading
screen. Your local copy is backed up first. If the download takes longer than a minute, it finishes in the
background and is applied the next time you open the world.

**Playing.** The world is uploaded every 7 minutes (configurable), starting about 2 minutes after you
join. To avoid stutter, uploads run at low priority, read the save at a limited speed and don't compress
the large region files.

**Leaving.** Hytale closes its world server a few seconds after you quit, which is too short for an
upload. So the mod starts a small helper process (using the game's own Java) that waits for the game to
close, then zips the world and uploads it. Nothing extra needs to be installed.

**Keeping track.** Every device remembers which cloud version its copy of the world matches. When you play,
the world is marked as having unsynced changes; the mark is cleared only after an upload is confirmed by
Google. That's how the mod knows whether it's safe to replace a local copy, and when to ask you instead.

**What is synced.** The whole save folder of the world, except logs, telemetry, Hytale's own backup folder
and the mod's own data. When a newer version is applied, the world and player data (`universe/`) are
replaced; the world's settings files stay as they are on your device.

## Your data and privacy

- Your worlds are stored in **your** Google Drive, in a folder called **Hytale Cloud Saves**.
- The mod uses Google's most limited Drive permission (`drive.file`): it can only see files it created
  itself. It cannot see, read or change anything else in your Drive.
- There is no server run by the developer. Nothing is sent to the developer or anyone other than Google.
  No analytics, no tracking.
- Your sign-in is stored on your device only (`UserData/CloudSaveSync/google-account.json`).

Full details: [privacy policy](https://komeg1.github.io/hytale-cloudsync-gdrive/privacy.html).

## Good to know

- **Singleplayer only.** The mod does nothing on dedicated servers.
- **A world is identified by its folder name.** Worlds with the same name on two devices are treated as the
  same world; if neither was synced before, you're asked which one to keep.
- **The mod can't run in the main menu.** To see or download your cloud worlds on a new device, open any
  world first.
- **Each world is synced per account.** If you sign in with a different Google account, use `enable` again
  for the worlds you want to sync with it.
- **Offline play works.** If Google Drive can't be reached, you play your local copy and it's uploaded the
  next time you play that world online.
- **Local backups** of worlds replaced by a cloud version are in `UserData/CloudSaveSync/backups/` (the
  newest 10 per world).

## Troubleshooting

| Problem | What to do |
|---|---|
| No `/cloudsync` command | Check the jar is in `UserData/Mods` and CloudSaveSync is enabled in the world's mod list. |
| "This world isn't synced" | Type `/cloudsync enable`. |
| "Can't reach Google Drive" | Check your internet connection. Nothing is lost; the world uploads later. |
| "You've been signed out" | Type `/cloudsync login` again (access may have been removed in your Google account). |
| "Your Google Drive is full" | Free some space, or keep fewer copies with `/cloudsync keep`. |
| Downloaded world not in the menu | Restart the game. |
| "Last time, the cloud upload didn't finish" | It's retried while you play. Details are in `UserData/CloudSaveSync/worlds/<world>/helper.log`. |

Please report problems on the [issue tracker](https://github.com/komeg1/hytale-cloudsync-gdrive/issues),
with the output of `/cloudsync status` and your system (Windows, macOS, Linux or Steam Deck).

## For developers

### Project layout

| Module | Contents |
|---|---|
| `sync-core` | Sync logic without Hytale or Google dependencies: storage interface, snapshots, manifest, conflict decisions. Unit tested. |
| `gdrive` | Google sign-in and the Google Drive storage provider. |
| `plugin` | The Hytale plugin and the detached upload helper. |

Other cloud services can be added by implementing `StorageProvider` in a new module, without touching the
plugin layer.

### Building

Requires a JDK 17 or newer to run Gradle; Java 25 is downloaded automatically for compiling. The Hytale
server API comes from `https://maven.hytale.com/release`.

```
./gradlew build
```

Google sign-in needs OAuth clients from a Google Cloud project with the Google Drive API enabled. Create
`secrets.properties` in the project root (it's gitignored):

```
google.tv.client_id=...
google.tv.client_secret=...
# optional, enables browser sign-in:
google.desktop.client_id=...
google.desktop.client_secret=...
```

Without it the build still works, but the mod can't sign in.

To copy the jar into Hytale's Mods folder:

```
./gradlew :plugin:deployPlugin -PhytaleModsDir="<path to UserData/Mods>"
```

### Tests and releases

- Pull requests to `master` run the tests on Windows, macOS and Linux.
- Pushing a `v*.*.*` tag on `master` builds the jar and publishes a GitHub release. It needs the
  `GOOGLE_TV_CLIENT_ID` and `GOOGLE_TV_CLIENT_SECRET` repository secrets.

---

Cloud Save Sync is a fan-made mod and is not affiliated with Hypixel Studios or Google.
