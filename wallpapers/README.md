# Wallpaper clips

Ready-made clips: download `glass-home-wallpapers.zip` (720p) or `glass-home-wallpapers-1080p.zip` (Full HD and 4K TVs) from the [Releases page](https://github.com/diganta-svnit/Clean-TV---Glass-Launcher/releases) (PROMPT.md step 6 does it for you).

To use your own, save clips under the names below in one folder, then run `wallpapers/convert.sh <folder>` (needs ffmpeg; set `FFMPEG=` to point at one that isn't on the PATH). It writes `<folder>/tv/`: a seamless 720p loop (with `RES=1080` in front: 1080p, in `<folder>/tv1080/`), a poster and a blurred poster for each clip. Push that folder's files to `/sdcard/Android/data/tv.glasshome.launcher/files/wallpapers/`.

Mist and windy weather use the cloudy clip of that time of day. A missing clip falls back to that time of day's clear clip, then to `day-clear`.

| # | Filename | Scene | Search on Pexels / Pixabay |
|---|---|---|---|
| 1 | `morning-clear.mp4` | Sunrise over hills or a lake, warm gold/pink sky | sunrise mountains timelapse · sunrise lake static |
| 2 | `day-clear.mp4` | Bright blue sky, white clouds drifting over green hills | clouds timelapse mountains · blue sky clouds moving |
| 3 | `evening-clear.mp4` | Sunset / golden hour, orange-violet sky, silhouettes | sunset clouds timelapse · golden hour lake |
| 4 | `night-clear.mp4` | Starry or moonlit sky over a dark landscape | night sky stars timelapse · milky way mountains |
| 5 | `day-cloudy.mp4` | Overcast or big clouds rolling over hills | overcast clouds timelapse · cloudy sky hills |
| 6 | `day-rain.mp4` | Rain falling on hills, forest or a lake | rain on lake · monsoon hills rain · heavy rain forest |
| 7 | `day-mist.mp4` | Mist moving through hills or forest | fog rolling over hills · misty forest |
| 8 | `evening-cloudy.mp4` | Sunset light on the undersides of clouds | sunset cloudy sky timelapse · dramatic clouds dusk |
| 9 | `night-cloudy.mp4` | Moon behind passing clouds | moon clouds timelapse · night clouds moving |
| 10 | `night-rain.mp4` | Dark rainy night, rain on water or leaves | rain at night nature · rain drops lake night |
| 11 | `morning-mist.mp4` | Sunrise fog in a valley or over a lake | sunrise fog valley · morning mist lake |
| 12 | `morning-cloudy.mp4` | Dawn colour breaking through clouds | sunrise cloudy sky timelapse |
| 13 | `morning-rain.mp4` | Grey-blue dawn with rain | morning rain nature · rainy dawn |
| 14 | `evening-rain.mp4` | Moody dusk with rain | rain sunset · stormy evening clouds |
| 15 | `evening-mist.mp4` | Fog with dim orange/purple light | foggy sunset hills · misty dusk |
| 16 | `night-mist.mp4` | Moonlit fog over hills or water | moonlight fog · night mist forest |
| 17 | `morning-windy.mp4` | Grass or trees swaying at sunrise | wind grass sunrise · trees wind morning |
| 18 | `day-windy.mp4` | Grass, trees or waves moving in strong wind | wind field grass · windy trees |
| 19 | `evening-windy.mp4` | Fast clouds or swaying grass at sunset | windy sunset grass · fast clouds sunset |
| 20 | `night-windy.mp4` | Fast-moving clouds across the moon | fast clouds night moon |

## Tips when picking clips

- **Same kind of scenery throughout** (e.g. all green hills, or all lakes) makes the set feel like one place.
- Prefer clips tagged **timelapse** or with a still camera; skip drone flyovers, pans and zooms.
- Download the **largest** size offered; 4K is fine.
- Pexels and Pixabay licences allow personal use; avoid YouTube or Instagram downloads.
