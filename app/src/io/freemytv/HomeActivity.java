package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    /** Opens All Apps (a remote's All Apps button, RemoteKeys). */
    static final String EXTRA_ALL_APPS = "io.freemytv.ALL_APPS";
    private HomeView home;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    // FPS and network pills (top right, left of the round buttons)
    private final Handler statsHandler = new Handler(Looper.getMainLooper());
    private TextView fpsPill, netPill;
    private int fpsFrames;
    private long fpsFirstNs, fpsLastNs, statsAt, rxAt, txAt;
    private boolean statsOn;
    private final Runnable statsTick = new Runnable() {
        @Override public void run() {
            updateStats();
            if (statsOn) statsHandler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        SleepTimer.listen(this);
        SleepTimer.check(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        home = new HomeView(this);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
        addStatsPills();
        if (getIntent().getBooleanExtra(EXTRA_ALL_APPS, false)) home.showAllApps();
    }

    private TextView pill(float k) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        if (Ui.medium != null) t.setTypeface(Ui.medium);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26 * k);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        int h = Math.round(22 * k), v = Math.round(10 * k);
        t.setPadding(h, v, h, v);
        t.setMinHeight(Math.round(52 * k));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x73000000);
        bg.setCornerRadius(60 * k);
        t.setBackground(bg);
        return t;
    }

    /** Two glass pills in the top row: frames per second, and download/upload speed. */
    private void addStatsPills() {
        float k = getResources().getDisplayMetrics().widthPixels / 1920f;
        fpsPill = pill(k);
        netPill = pill(k);
        fpsPill.setText("● — FPS");
        netPill.setText("↓ — ↑ — Mb/s");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(fpsPill);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = Math.round(16 * k);
        row.addView(netPill, gap);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        lp.topMargin = Math.round((103 - 26) * k);
        lp.rightMargin = Math.round((1920 - 1560) * k);
        addContentView(row, lp);
        getWindow().getDecorView().getViewTreeObserver().addOnDrawListener(() -> {
            long t = System.nanoTime();
            if (fpsFrames == 0) fpsFirstNs = t;
            fpsLastNs = t;
            fpsFrames++;
        });
    }

    private void startStats() {
        if (fpsPill == null) return;
        statsOn = true;
        statsAt = SystemClock.elapsedRealtime();
        rxAt = TrafficStats.getTotalRxBytes();
        txAt = TrafficStats.getTotalTxBytes();
        fpsFrames = 0;
        statsHandler.removeCallbacks(statsTick);
        statsHandler.postDelayed(statsTick, 1000);
    }

    private void stopStats() {
        statsOn = false;
        statsHandler.removeCallbacks(statsTick);
    }

    private void updateStats() {
        if (fpsPill == null) return;
        long now = SystemClock.elapsedRealtime();
        float secs = Math.max(0.2f, (now - statsAt) / 1000f);
        statsAt = now;

        // FPS: the rate while the screen was moving. With the screen still there is nothing to measure.
        int frames = fpsFrames;
        long span = fpsLastNs - fpsFirstNs;
        fpsFrames = 0;
        boolean idle = frames <= 3 || span <= 0;
        long fps = idle ? 0 : Math.round((frames - 1) * 1e9 / span);
        int dot = idle ? 0x99FFFFFF : fps >= 55 ? 0xFF5DCAA5 : fps >= 30 ? 0xFFFAC775 : 0xFFF09595;
        SpannableStringBuilder s = new SpannableStringBuilder("● ");
        s.setSpan(new ForegroundColorSpan(dot), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.append(idle ? "— FPS" : Math.min(fps, 999) + " FPS");
        fpsPill.setText(s);

        // Network: what the TV downloaded and uploaded in the last second.
        long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes();
        if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) {
            netPill.setText("↓ — ↑ — Mb/s");
        } else {
            double down = Math.max(0, rx - rxAt) * 8.0 / 1e6 / secs;
            double up = Math.max(0, tx - txAt) * 8.0 / 1e6 / secs;
            rxAt = rx;
            txAt = tx;
            netPill.setText(String.format(Locale.getDefault(), "↓ %.1f  ↑ %.1f Mb/s", down, up));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        // TCL's Android 11 records the home activity as translucent (occludesParent=false) whatever its theme says:
        // Android then keeps the app just left visible, and home tasks are layered under all others, so that app stays
        // on screen over ours. Opaque again on every resume (setTranslucent is public from Android 11).
        if (RemoteKeys.TCL && Build.VERSION.SDK_INT >= 30) setTranslucent(false);
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
        startStats();
    }

    @Override protected void onPause() {
        stopStats();
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home == null) return;
        if (intent.getBooleanExtra(EXTRA_ALL_APPS, false)) home.showAllApps();
        else home.reset(true);
    }

    @Override protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
