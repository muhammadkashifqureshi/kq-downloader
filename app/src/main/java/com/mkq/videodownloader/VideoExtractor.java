package com.mkq.videodownloader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts direct video stream URLs on-device, no third-party API needed.
 *
 * YouTube: parses ytInitialPlayerResponse from the watch page and keeps the
 * progressive formats that carry a direct URL (signature-ciphered formats are
 * skipped). TikTok: resolves the share link and reads the play address from
 * the page's embedded JSON. Instagram: reads the video_url from the public
 * post/reel page's embedded JSON.
 */
public class VideoExtractor {

    static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final Pattern YT_ID =
            Pattern.compile("(?:youtube\\.com/(?:watch\\?[^#]*v=|shorts/|embed/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})");

    public static VideoInfo extract(String url) throws Exception {
        String u = url.trim();
        if (u.isEmpty()) throw new Exception("Paste a video link first.");
        String low = u.toLowerCase();
        if (low.contains("youtube.com/") || low.contains("youtu.be/")) {
            return extractYouTube(u);
        }
        if (low.contains("tiktok.com/")) {
            return extractTikTok(u);
        }
        if (low.contains("instagram.com/")) {
            return extractInstagram(u);
        }
        throw new Exception("Unsupported link. Paste a YouTube, TikTok or Instagram video link.");
    }

    // ---------------- YouTube ----------------

    private static VideoInfo extractYouTube(String url) throws Exception {
        Matcher m = YT_ID.matcher(url);
        if (!m.find()) throw new Exception("Could not find a YouTube video ID in that link.");
        String id = m.group(1);

        String html = fetchHtml("https://www.youtube.com/watch?v=" + id);
        return parseYouTubePage(html);
    }

    /** Parses a YouTube watch page's HTML. Package-visible for testing. */
    static VideoInfo parseYouTubePage(String html) throws Exception {
        String json = extractJsonObject(html, "ytInitialPlayerResponse");
        JSONObject root = new JSONObject(json);

        String status = root.optJSONObject("playabilityStatus").optString("status", "");
        if (!"OK".equals(status)) {
            String reason = root.optJSONObject("playabilityStatus").optString("reason", status);
            throw new Exception("This video can't be downloaded (" + reason + ").");
        }

        JSONObject details = root.optJSONObject("videoDetails");
        String title = details != null ? details.optString("title", "youtube_video") : "youtube_video";
        String thumb = null;
        if (details != null) {
            JSONObject thumbObj = details.optJSONObject("thumbnail");
            JSONArray thumbs = thumbObj != null ? thumbObj.optJSONArray("thumbnails") : null;
            if (thumbs != null && thumbs.length() > 0) {
                thumb = thumbs.getJSONObject(thumbs.length() - 1).optString("url", null);
            }
        }

        List<StreamOption> opts = new ArrayList<>();
        JSONObject streaming = root.optJSONObject("streamingData");
        if (streaming != null) {
            JSONArray formats = streaming.optJSONArray("formats");
            if (formats != null) {
                for (int i = 0; i < formats.length(); i++) {
                    JSONObject f = formats.getJSONObject(i);
                    String durl = f.optString("url", "");
                    if (durl.isEmpty()) continue; // ciphered stream, skip
                    int itag = f.optInt("itag", 0);
                    String mime = f.optString("mimeType", "");
                    String ext = mime.contains("webm") ? "webm" : "mp4";
                    opts.add(new StreamOption(itagLabel(itag, f.optString("qualityLabel", "")), durl, ext));
                }
            }
            // Audio-only streams (SnapTube-style audio download) live in adaptiveFormats.
            String audioUrl = null, audioExt = "m4a";
            JSONArray adaptive = streaming.optJSONArray("adaptiveFormats");
            if (adaptive != null) {
                for (int i = 0; i < adaptive.length(); i++) {
                    JSONObject f = adaptive.getJSONObject(i);
                    String durl = f.optString("url", "");
                    if (durl.isEmpty()) continue;
                    if (!f.optString("mimeType", "").startsWith("audio/")) continue;
                    int itag = f.optInt("itag", 0);
                    String ext = f.optString("mimeType", "").contains("webm") ? "weba" : "m4a";
                    if (audioUrl == null) { audioUrl = durl; audioExt = ext; }
                    if (itag == 140) { audioUrl = durl; audioExt = ext; break; } // best audio track
                }
            }
            if (audioUrl != null) {
                opts.add(new StreamOption("Audio only (" + audioExt + ")", audioUrl, audioExt));
            }
        }
        if (opts.isEmpty()) {
            throw new Exception("No downloadable streams found. This video may be age-restricted or protected.");
        }
        sortByQuality(opts);
        return new VideoInfo("YouTube", title, thumb, opts);
    }

    private static String itagLabel(int itag, String qualityLabel) {
        switch (itag) {
            case 22: return "720p HD";
            case 18: return "360p";
            case 43: return "360p (WebM)";
            case 36: return "240p";
            case 17: return "144p";
            default: return qualityLabel.isEmpty() ? ("Quality " + itag) : qualityLabel;
        }
    }

    private static void sortByQuality(List<StreamOption> opts) {
        Collections.sort(opts, new Comparator<StreamOption>() {
            int rank(String l) {
                if (l.startsWith("720p")) return 0;
                if (l.startsWith("360p")) return 1;
                if (l.startsWith("240p")) return 2;
                if (l.startsWith("144p")) return 3;
                return 4;
            }
            @Override public int compare(StreamOption a, StreamOption b) {
                return rank(a.label) - rank(b.label);
            }
        });
    }

    // ---------------- TikTok ----------------

    private static VideoInfo extractTikTok(String url) throws Exception {
        String html = fetchHtml(resolveUrl(url));
        return parseTikTokPage(html);
    }

    /** Parses a TikTok video page's HTML. Package-visible for testing. */
    static VideoInfo parseTikTokPage(String html) throws Exception {
        String play = regexGroup(html, "\"playAddr\":\"(https:[^\"]+)\"");
        if (play == null) play = regexGroup(html, "\"downloadAddr\":\"(https:[^\"]+)\"");
        if (play == null) {
            throw new Exception("Couldn't find the video file. The link may be invalid, private or deleted.");
        }
        play = play.replace("\\/", "/").replace("\\u0026", "&");

        String title = regexGroup(html, "<meta property=\"og:title\" content=\"([^\"]+)\"");
        if (title == null) title = regexGroup(html, "\"desc\":\"([^\"]{1,120})");
        if (title != null) title = unescapeJson(title);
        else title = "tiktok_video";

        String thumb = regexGroup(html, "<meta property=\"og:image\" content=\"([^\"]+)\"");

        List<StreamOption> opts = new ArrayList<>();
        opts.add(new StreamOption("Original quality", play, "mp4"));
        return new VideoInfo("TikTok", title, thumb, opts);
    }

    // ---------------- Instagram ----------------

    private static VideoInfo extractInstagram(String url) throws Exception {
        String html = fetchHtml(url);
        return parseInstagramPage(html);
    }

    /** Parses an Instagram post/reel page's HTML. Package-visible for testing. */
    static VideoInfo parseInstagramPage(String html) throws Exception {
        String video = regexGroup(html, "\"video_url\":\"(https:[^\"]+)\"");
        if (video == null) {
            // Instagram now serves a login wall to anonymous requests (verified Oct 2026):
            // public pages return HTTP 200 but contain no video data without a session.
            throw new Exception("Instagram blocked this video: it now requires an Instagram login to fetch videos, even public ones. YouTube and TikTok links work without login.");
        }
        video = video.replace("\\/", "/").replace("\\u0026", "&");

        String title = regexGroup(html, "<meta property=\"og:title\" content=\"([^\"]+)\"");
        if (title != null) title = unescapeJson(title);
        else title = "instagram_video";

        String thumb = regexGroup(html, "<meta property=\"og:image\" content=\"([^\"]+)\"");

        List<StreamOption> opts = new ArrayList<>();
        opts.add(new StreamOption("Original quality", video, "mp4"));
        return new VideoInfo("Instagram", title, thumb, opts);
    }

    // ---------------- HTTP helpers ----------------
    private static String resolveUrl(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.connect();
        String finalUrl = c.getURL().toString();
        c.disconnect();
        return finalUrl;
    }

    /** Referer header expected by each site's video CDN. Package-visible for testing. */
    static String refererFor(String host) {
        if (host == null) return "https://www.google.com/";
        if (host.contains("tiktok")) return "https://www.tiktok.com/";
        if (host.contains("youtube") || host.contains("googlevideo"))
            return "https://www.youtube.com/";
        if (host.contains("instagram") || host.contains("fbcdn")
                || host.contains("cdninstagram"))
            return "https://www.instagram.com/";
        return "https://" + host + "/";
    }

    /** Browser User-Agent string used for page fetches and file downloads. */
    static String browserUserAgent() {
        return UA;
    }

    private static String fetchHtml(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(25000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("Couldn't load the video page (HTTP " + code + ").");
        InputStream in = c.getInputStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        r.close();
        return sb.toString();
    }

    /** Extracts a {...} JSON object that follows the given key, brace-aware. */
    private static String extractJsonObject(String html, String key) throws Exception {
        int ki = html.indexOf(key);
        if (ki < 0) throw new Exception("Video data not found (page format changed or video unavailable).");
        int start = html.indexOf('{', ki);
        if (start < 0) throw new Exception("Video data not found.");
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = start; i < html.length(); i++) {
            char ch = html.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (ch == '\\') esc = true;
                else if (ch == '"') inStr = false;
            } else {
                if (ch == '"') inStr = true;
                else if (ch == '{') depth++;
                else if (ch == '}') {
                    depth--;
                    if (depth == 0) return html.substring(start, i + 1);
                }
            }
        }
        throw new Exception("Video data not found.");
    }

    private static String regexGroup(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String unescapeJson(String s) {
        return s.replace("\\/", "/").replace("\\u0026", "&").replace("\\n", " ").replace("\\\"", "\"");
    }
}
