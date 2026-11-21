# Set up an Android TV with Glass Home

You are a coding agent setting up the person's Android TV: debloat it safely, then install the Glass Home
launcher from this repo with its wallpapers. Work on the real TV over ADB, step by step, and show the person
what you did. Ask before anything this file says to ask about.

## Rules that are not negotiable

- **Debloating follows [cleanyourtv](https://github.com/Gathin23/cleanyourtv)'s `PROMPT.md` and every safety
  rule in it**: disable, never uninstall system apps; back up first; never touch the packages it protects;
  never start a factory or test package; keep its Home guard; ask before disabling anything the family uses.
  Where this file and cleanyourtv's differ, cleanyourtv's safety rules win.
- **Never print, log or commit the TMDB key** (`tmdb_key.txt`) or `glass.properties`. Both are in `.gitignore`.
- **No identifiers in anything shared**: no IP addresses, Wi-Fi names, serials or account names in commits,
  issues or screenshots you post.
- **Uninstall only apps the person installed and names**, after showing them the list and the space each one
  uses. Built-in apps are disabled instead, through cleanyourtv's procedure.
- **Close ADB at the end** exactly as cleanyourtv describes, and confirm with its `tools/adb-closed.py`.
- **Stop and say so** when the TV refuses something; don't retry in a loop or look for a way around it.

## 1. Ask the person (one short message)

1. The TV's IP address (Settings → Network) and that **Developer options → USB debugging / Network debugging**
   is on. They will need to accept an "Allow debugging?" prompt on the TV the first time.
2. Their **city** (for weather, sunrise and sunset) and **country** (for where films are streaming).
3. **Debloat** the TV too, or only install the launcher?
4. Their **six main apps** for the tray, in order. You'll match them to installed packages in step 3.
5. A **TMDB API Read Access Token** for Trending (free: themoviedb.org → Settings → API), or skip Trending.
   Ask them to paste it into `tmdb_key.txt` in this repo's root themselves, or paste it to you so you write the
   file; never echo it back.
6. A preferred **language** for Trending, or all languages.

## 2. Tools

```bash
git clone https://github.com/Gathin23/cleanyourtv.git cleanyourtv       # inside this repo; it's ignored by git
python3 -m venv cleanyourtv/tools/venv
cleanyourtv/tools/venv/bin/pip install -r cleanyourtv/tools/requirements.txt
export TV_HOST=<tv ip>
cleanyourtv/tools/venv/bin/python -I cleanyourtv/tools/tv.py --probe
```

`tools/tv.py` is the ADB client (no `adb` binary needed): `tv.py "<shell command>"`, `tv.py --screenshot
shots/NN.png [KEY ...]`. `tools/b64push.py <local> <remote>` copies files to the TV. Read the first lines of
each script for its options. Keep screenshots in `shots/` (ignored by git).

The TV needs Android 8.0 or newer (`getprop ro.build.version.sdk` ≥ 26). Below that, stop and tell the person.

## 3. Debloat (if they said yes)

Follow `cleanyourtv/PROMPT.md` from the start, with all its rules and its checklist question, **with one
change**: wherever it installs its own launcher (`com.gathin.tvhome`), install Glass Home instead (steps 4 and
5 below), and confirm Glass Home is Home before cleanyourtv's step that disables the stock launcher.

Stop before cleanyourtv's last step (closing ADB): that comes at the very end of this file.

## 4. Configure and build

1. List the installed apps and match the person's six to package names:
   `tv.py "cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER"`.
   Prefer TV builds: Netflix is `com.netflix.ninja` (not `com.netflix.mediaclient`, a phone build), YouTube is
   `com.google.android.youtube.tv`.
2. Find the city's coordinates:
   `curl -s "https://geocoding-api.open-meteo.com/v1/search?count=5&format=json&name=<city>"` and confirm the
   right one with the person if there are several.
3. `cp glass.properties.example glass.properties` and fill in `city`, `latitude`, `longitude`, `region` (two
   letters), `language` and `tray`.
4. Build. It needs JDK 17 and the Android SDK with `platforms;android-34` and `build-tools;34.0.0`. If either is
   missing, install them the usual way for this computer (on macOS: a JDK 17 such as Temurin, and Google's
   command-line tools with `sdkmanager`), then write `sdk.dir=<path>` to `launcher/local.properties`.
   ```bash
   cd launcher && ./gradlew --no-daemon assembleRelease
   ```
   The APK is `launcher/app/build/outputs/apk/release/app-release.apk`.

## 5. Install and make it Home

```bash
P=cleanyourtv/tools/venv/bin/python
$P -I cleanyourtv/tools/b64push.py launcher/app/build/outputs/apk/release/app-release.apk /data/local/tmp/glass.apk
$P -I cleanyourtv/tools/tv.py "pm install -r /data/local/tmp/glass.apk; rm /data/local/tmp/glass.apk"
$P -I cleanyourtv/tools/tv.py "cmd package set-home-activity --user 0 tv.glasshome.launcher/.MainActivity"
$P -I cleanyourtv/tools/tv.py "input keyevent HOME; sleep 3; dumpsys activity activities | grep mFocusedApp"
```

`mFocusedApp` must name `tv.glasshome.launcher`. On some TVs the stock launcher takes Home back until it is
disabled; that's cleanyourtv's Home-guard step, done only after Glass Home answers Home.

Optional extras, each one a single command. Tell the person what each one is for:

| Grant | What it gives | Command |
|---|---|---|
| Usage access | "Frequently used" apps in the tray and recent apps in Continue watching | `appops set tv.glasshome.launcher GET_USAGE_STATS allow` |
| TV listings | Netflix's own Continue watching card (exact episode) | `pm grant tv.glasshome.launcher android.permission.READ_TV_LISTINGS` |
| Display over other apps | The volume light (a streak of light shown on volume changes, over any app) | `appops set tv.glasshome.launcher SYSTEM_ALERT_WINDOW allow` |
| DUMP (developer permission) | What was last playing, for TVs that refuse notification access | `pm grant tv.glasshome.launcher android.permission.DUMP` |

Undo any of them with `appops set … default` or `pm revoke …`.

## 6. Wallpapers

Open Glass Home once (step 5 did), so its folder exists. The release has two packs of the same twelve clips:

| Pack | For | Size |
|---|---|---|
| `glass-home-wallpapers.zip` | TVs whose home screen runs at 1280×720 | ~49 MB |
| `glass-home-wallpapers-1080p.zip` | Full HD and 4K TVs (their home screen runs at 1920×1080) | ~109 MB |

Pick by the home screen's size, not the panel's: `tv.py "wm size"` prints `Physical size` and, if set, `Override
size`; use the override when there is one. Width 1920 or more: the 1080p pack. Otherwise: the 720p pack. A 4K
panel usually reports 1920×1080 here, because Android TV draws its home screen at 1080p; the 1080p pack is the
sharpest the launcher can show on it, and 4K clips would only cost decoding power.

**Budget TVs keep the 720p pack**, whatever their screen size. Use the 720p pack if any of these is true:
- `getprop ro.config.low_ram` says `true`;
- `MemTotal` in `/proc/meminfo` is under 1,600,000 kB (about 1.5 GB of RAM; good TVs have 2 GB or more);
- less than about 1 GB of storage is free: `df -k /data` shows under 1,000,000 kB available.

It runs smoothly on a 1 GB TV, takes 49 MB instead of 109 MB, and leaves memory and space for the apps.

```bash
PACK=glass-home-wallpapers.zip            # or glass-home-wallpapers-1080p.zip, from wm size above
mkdir -p wallpapers/tv
curl -L -o "wallpapers/$PACK" \
  "https://github.com/diganta-svnit/Clean-TV---Glass-Launcher/releases/latest/download/$PACK"
unzip -o "wallpapers/$PACK" -d wallpapers/tv
for f in wallpapers/tv/*; do
  $P -I cleanyourtv/tools/b64push.py "$f" "/sdcard/Android/data/tv.glasshome.launcher/files/wallpapers/$(basename "$f")"
done
```

Check the pack's SHA-256 against the one in the release notes before unzipping. Each clip is three files: the
loop (`.mp4`), its first frame (`.jpg`) and a blurred frame (`-blur.jpg`). Press Home; the wallpaper appears within
a minute. If the person brings their own clips, run `wallpapers/convert.sh <folder>` (needs ffmpeg; add `RES=1080`
in front for a 1080p TV) and push the `tv/` (or `tv1080/`) folder it makes instead.

## 7. Check it on the TV

Take a screenshot after each of these and look at it:
- Home: wallpaper moving, clock and weather for their city, the six apps in order, All apps last.
- Down: seven more apps and All apps as the 14th tile. Down again: Trending and Continue watching.
- Up, Left and Right from every edge: focus never disappears.
- Settings → City, Wallpaper, Trending language; Back closes each menu.
- Hold OK on an app: Open, Uninstall (installed apps only), App info. Back cancels.

`input keyevent --longpress DPAD_CENTER` simulates holding OK. Don't press OK on Uninstall's confirmation;
press Back.

## 8. Finish

1. Ask the person to press a few buttons on the **real remote**, since an injected key is not proof.
2. Close ADB exactly as `cleanyourtv/PROMPT.md` and `AGENTS.md` describe: Wireless and Network/USB debugging off,
   authorisations revoked, reboot, then `cleanyourtv/tools/adb-closed.py <tv ip>` must say closed.
3. Tell the person what changed, what was disabled or uninstalled, and how to undo it (README → Undo).
