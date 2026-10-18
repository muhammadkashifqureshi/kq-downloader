package com.mkq.videodownloader;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MK Video Downloader — developed by Kashif Qureshi.
 * Paste a link, pick a quality, download to the Downloads folder.
 *
 * Downloads run in-app with real browser headers (User-Agent + Referer)
 * because video CDNs reject Android's system downloader (it sends a
 * generic UA and no Referer, so the file silently never arrives).
 */
public class MainActivity extends Activity {

    private static final int REQ_STORAGE = 1001;

    private EditText urlInput;
    private Button pasteBtn, fetchBtn, downloadBtn;
    private ProgressBar progressBar; // spinner while fetching video info
    private ProgressBar downloadProgress; // horizontal bar while downloading
    private TextView downloadProgressText;
    private ImageView thumbView;
    private TextView titleView, statusView, qualityLabel;
    private Spinner qualitySpinner;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private VideoInfo currentVideo;
    private volatile boolean downloading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.urlInput);
        pasteBtn = findViewById(R.id.pasteBtn);
        fetchBtn = findViewById(R.id.fetchBtn);
        downloadBtn = findViewById(R.id.downloadBtn);
        progressBar = findViewById(R.id.progressBar);
        downloadProgress = findViewById(R.id.downloadProgress);
        downloadProgressText = findViewById(R.id.downloadProgressText);
        thumbView = findViewById(R.id.thumbView);
        titleView = findViewById(R.id.titleView);
        statusView = findViewById(R.id.statusView);
        qualityLabel = findViewById(R.id.qualityLabel);
        qualitySpinner = findViewById(R.id.qualitySpinner);

        pasteBtn.setOnClickListener(v -> pasteFromClipboard());
        fetchBtn.setOnClickListener(v -> fetchVideo());
        downloadBtn.setOnClickListener(v -> downloadSelected());
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).getText();
                if (text != null) urlInput.setText(text.toString().trim());
            }
        }
    }

    private void fetchVideo() {
        final String url = urlInput.getText().toString().trim();
        hideResult();
        progressBar.setVisibility(View.VISIBLE);
        setStatus("Fetching video info…");
        fetchBtn.setEnabled(false);

        executor.execute(() -> {
            try {
                final VideoInfo info = VideoExtractor.extract(url);
                currentVideo = info;
                main.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    fetchBtn.setEnabled(true);
                    showResult(info);
                });
            } catch (final Exception e) {
                main.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    fetchBtn.setEnabled(true);
                    setStatus("Error: " + e.getMessage());
                });
            }
        });
    }

    private void showResult(VideoInfo info) {
        titleView.setText(info.title);
        titleView.setVisibility(View.VISIBLE);

        if (info.thumbnailUrl != null && !info.thumbnailUrl.isEmpty()) {
            thumbView.setVisibility(View.VISIBLE);
            loadThumbnail(info.thumbnailUrl);
        }

        ArrayAdapter<StreamOption> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, info.options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        qualitySpinner.setAdapter(adapter);
        qualityLabel.setVisibility(View.VISIBLE);
        qualitySpinner.setVisibility(View.VISIBLE);

        downloadBtn.setVisibility(View.VISIBLE);
        setStatus(info.platform + " video ready — pick a quality and download.");
    }

    private void hideResult() {
        currentVideo = null;
        thumbView.setVisibility(View.GONE);
        titleView.setVisibility(View.GONE);
        qualityLabel.setVisibility(View.GONE);
        qualitySpinner.setVisibility(View.GONE);
        downloadBtn.setVisibility(View.GONE);
        downloadProgress.setVisibility(View.GONE);
        downloadProgressText.setVisibility(View.GONE);
    }

    private void loadThumbnail(final String thumbUrl) {
        executor.execute(() -> {
            try {
                InputStream in = new URL(thumbUrl).openStream();
                final Bitmap bmp = BitmapFactory.decodeStream(in);
                in.close();
                main.post(() -> {
                    if (bmp != null) thumbView.setImageBitmap(bmp);
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void downloadSelected() {
        if (currentVideo == null || downloading) return;
        Object sel = qualitySpinner.getSelectedItem();
        if (!(sel instanceof StreamOption)) return;
        final StreamOption opt = (StreamOption) sel;

        // Android 9 and below need the storage permission for the public Downloads dir.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            setStatus("Storage permission needed — allow it, then tap Download again.");
            return;
        }

        final String fileName = sanitize(currentVideo.title) + "." + opt.ext;
        downloading = true;
        downloadBtn.setEnabled(false);
        downloadProgress.setVisibility(View.VISIBLE);
        downloadProgress.setProgress(0);
        downloadProgressText.setVisibility(View.VISIBLE);
        downloadProgressText.setText("Starting…");
        setStatus("Downloading " + fileName + " …");

        executor.execute(() -> {
            try {
                downloadToFile(opt.url, fileName);
                main.post(() -> {
                    downloading = false;
                    downloadBtn.setEnabled(true);
                    downloadProgress.setProgress(100);
                    downloadProgressText.setText("100% — done");
                    setStatus("Saved to Downloads: " + fileName);
                });
            } catch (final Exception e) {
                main.post(() -> {
                    downloading = false;
                    downloadBtn.setEnabled(true);
                    downloadProgress.setVisibility(View.GONE);
                    downloadProgressText.setVisibility(View.GONE);
                    setStatus("Download failed: " + e.getMessage());
                });
            }
        });
    }

    /**
     * Downloads the file with browser headers so CDNs serve it,
     * reporting progress to the UI. Package-visible for testing.
     */
    void downloadToFile(String fileUrl, String fileName) throws Exception {
        URL url = new URL(fileUrl);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestProperty("User-Agent", VideoExtractor.browserUserAgent());
        c.setRequestProperty("Referer", VideoExtractor.refererFor(url.getHost()));
        c.setRequestProperty("Accept", "*/*");
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.connect();

        int code = c.getResponseCode();
        if (code / 100 != 2) {
            c.disconnect();
            throw new Exception("server refused the download (HTTP " + code + ")");
        }
        long total = c.getContentLengthLong();

        OutputStream out = openDownloadOutput(fileName);
        InputStream in = c.getInputStream();
        byte[] buf = new byte[32768];
        long done = 0;
        int n;
        long lastUi = 0;
        try {
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastUi > 250) {
                    lastUi = now;
                    final long fDone = done, fTotal = total;
                    main.post(() -> updateProgress(fDone, fTotal));
                }
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            try {
                out.close();
            } catch (Exception ignored) {
            }
            c.disconnect();
        }
        if (total > 0 && done < total) {
            throw new Exception("download stopped early — try again");
        }
    }

    private void updateProgress(long done, long total) {
        if (total > 0) {
            int pct = (int) (done * 100 / total);
            downloadProgress.setProgress(pct);
            downloadProgressText.setText(pct + "% · " +
                    (done / 1048576) + " MB of " + (total / 1048576) + " MB");
        } else {
            downloadProgressText.setText((done / 1048576) + " MB downloaded…");
        }
    }

    private OutputStream openDownloadOutput(String fileName) throws Exception {
        String mime = "video/mp4";
        if (fileName.endsWith(".m4a")) mime = "audio/mp4";
        else if (fileName.endsWith(".weba")) mime = "audio/webm";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("could not create the file");
            OutputStream out = getContentResolver().openOutputStream(uri);
            if (out == null) throw new Exception("could not open the file");
            return out;
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new Exception("could not access the Downloads folder");
            }
            return new FileOutputStream(new File(dir, fileName));
        }
    }

    private static String sanitize(String name) {
        String s = name.replaceAll("[^a-zA-Z0-9 _-]", "").trim();
        if (s.isEmpty()) s = "video";
        if (s.length() > 60) s = s.substring(0, 60);
        return s;
    }

    private void setStatus(String msg) {
        statusView.setText(msg);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
