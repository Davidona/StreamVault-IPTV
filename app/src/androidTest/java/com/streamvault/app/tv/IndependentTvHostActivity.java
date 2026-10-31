package com.streamvault.app.tv;

import android.app.Activity;
import android.content.Intent;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceView;
import android.view.WindowManager;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Plain platform host in the test APK: remains alive when the product is SIGKILLed. */
public final class IndependentTvHostActivity extends Activity {
    private TvView view;
    private File events;
    private String run;
    private Uri channel;
    private String input;
    private int tunes;
    private boolean available;

    private void event(String name, String detail) {
        try {
            JSONObject row = new JSONObject().put("event", name).put("detail", detail)
                .put("elapsedMs", SystemClock.elapsedRealtime()).put("pid", Process.myPid())
                .put("tunes", tunes).put("available", available);
            try (FileOutputStream out = new FileOutputStream(events, true)) {
                out.write((row.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            Log.i("IndependentTvHost", row.toString());
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        Intent intent = getIntent();
        run = intent.getStringExtra("runId");
        if (!getPackageName().equals("com.streamvault.app.debug.test") ||
            !intent.getBooleanExtra("tvDeathFixture", false) || run == null ||
            !run.matches("[A-Za-z0-9_-]{1,40}")) { finish(); return; }
        channel = Uri.parse(intent.getStringExtra("channelUri"));
        if (!"content".equals(channel.getScheme()) || !"android.media.tv".equals(channel.getAuthority())) {
            finish(); return;
        }
        input = "com.streamvault.app.debug/com.streamvault.app.tvinput.StreamVaultTvInputService";
        events = new File(getFilesDir(), "independent-tif-" + run + ".jsonl");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new TvView(this);
        view.setCallback(new TvView.TvInputCallback() {
            @Override public void onVideoAvailable(String id) { available = true; event("available", ""); }
            @Override public void onVideoUnavailable(String id, int reason) { available = false; event("unavailable", Integer.toString(reason)); }
            @Override public void onConnectionFailed(String id) { available = false; event("connection-failed", ""); }
            @Override public void onDisconnected(String id) { available = false; event("disconnected", ""); }
            @Override public void onChannelRetuned(String id, Uri uri) { event("retuned", uri.toString()); }
        });
        // Only this opted-in clear loopback fixture permits screenshots.
        ((SurfaceView) view.getChildAt(0)).setSecure(false);
        setContentView(view);
        event("host-created", "");
        tune("initial-tune");
    }

    private void tune(String reason) {
        available = false; tunes++;
        event(reason, channel.toString());
        view.tune(input, channel);
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (events == null || !run.equals(intent.getStringExtra("runId"))) return;
        String command = intent.getStringExtra("command");
        if ("retune".equals(command)) {
            view.reset();
            // reset() replaces TvView's SurfaceView and restores its default secure flag.
            ((SurfaceView) view.getChildAt(0)).setSecure(false);
            tune("explicit-retune");
        }
        else if ("capture-begin".equals(command) || "capture-end".equals(command) || "checkpoint".equals(command)) event(command, "");
        else if ("release".equals(command)) { event("release-start", ""); view.reset(); available = false; event("release-return", ""); finish(); }
    }

    @Override public void onDestroy() {
        if (events != null) { if (view != null) view.reset(); event("host-destroyed", ""); }
        super.onDestroy();
    }
}
