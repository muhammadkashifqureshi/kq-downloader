package com.mkq.videodownloader;

/** One downloadable stream (quality option) for a video. */
public class StreamOption {
    public final String label;
    public final String url;
    public final String ext;

    public StreamOption(String label, String url, String ext) {
        this.label = label;
        this.url = url;
        this.ext = ext;
    }

    @Override
    public String toString() {
        return label;
    }
}
