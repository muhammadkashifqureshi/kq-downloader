# KQ Downloader

Android app — paste a YouTube, TikTok or Instagram video link, pick a quality, download it to your Downloads folder.

**Developed by Kashif Qureshi** · v1.0

## How it works

- **YouTube:** reads the video page's player data on-device and lists the progressive
  streams that carry a direct download URL (typically up to 720p HD), plus an
  audio-only track (m4a) from the adaptive formats.
- **TikTok:** resolves the share link and reads the direct video file address from the page.
- **Instagram:** reads the direct video URL from the public post/reel page.

No third-party download API, no account, no server — everything happens on the phone.
Downloads run in-app with real browser headers (User-Agent + Referer) and a live
progress bar, because video CDNs silently reject Android's system downloader.

## Honest limits

- YouTube actively changes its page format to block downloaders. Age-restricted,
  DRM-protected, live, or signature-ciphered-only videos may fail — the app will say so.
- TikTok private/deleted videos can't be fetched.
- Instagram (Oct 2026) serves a login wall to anonymous requests — even public
  reels can't be fetched without an Instagram login. In-app login is not yet implemented.
- Stream URLs expire quickly, so download right after fetching.
- For personal use: only download videos you have the right to save.

## Build

Manual build, no Gradle needed (same approach as the AK AI / AK Calculator apps):

```bash
./build-manual.sh
```

Requires the Android SDK + JDK 17 (paths are set for the author's build machine;
adjust `JAVA_HOME`, `SDK` and `BT` at the top of the script for yours).

Output: `app/build/manual/yt-tiktok-downloader.apk` (release-signed, sideload-ready).

## Release key

The APK is signed with a release key (`release.keystore` at the project root,
alias `kashif-release`, identity "Kashif Qureshi / KQ DevOps / PK"). Keep this
file safe — every future update must be signed with the same key or Android
will refuse to install it over the old version. The build script reuses it
automatically; it is never stored inside `app/build/` (wiped on rebuild).

## Structure

```
app/src/main/
  AndroidManifest.xml
  java/com/mkq/ytdownloader/
    MainActivity.java     # UI: paste link, pick quality, in-app download with progress bar
    VideoExtractor.java   # on-device stream-URL extraction (YouTube + TikTok)
    VideoInfo.java        # video metadata + stream options
    StreamOption.java     # one quality option
  res/layout/activity_main.xml
  res/values/             # strings, colors, theme
```
