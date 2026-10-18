package com.mkq.videodownloader;

import java.util.List;

/** Video metadata plus its downloadable stream options. */
public class VideoInfo {
    public final String platform;
    public final String title;
    public final String thumbnailUrl;
    public final List<StreamOption> options;

    public VideoInfo(String platform, String title, String thumbnailUrl, List<StreamOption> options) {
        this.platform = platform;
        this.title = title;
        this.thumbnailUrl = thumbnailUrl;
        this.options = options;
    }
}
