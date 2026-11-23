package com.rebornsport.app;

import android.app.PictureInPictureParams;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import androidx.mediarouter.app.MediaRouteButton;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

/**
 * Built-in player: plays a channel full screen, trying each stream address in turn.
 * On phones it supports picture-in-picture and casting to a Chromecast.
 */
public class PlayerActivity extends AppCompatActivity {

    private ExoPlayer player;
    private PlayerView view;
    private TextView castNote;
    private String[] urls;
    private String title;
    private int index = 0;

    private SessionManager sessions;
    private final SessionManagerListener<CastSession> castListener = new SessionManagerListener<CastSession>() {
        @Override public void onSessionStarted(CastSession s, String id) { castTo(s); }
        @Override public void onSessionResumed(CastSession s, boolean wasSuspended) { castTo(s); }
        @Override public void onSessionEnded(CastSession s, int error) { backToPhone(); }
        @Override public void onSessionStarting(CastSession s) {}
        @Override public void onSessionStartFailed(CastSession s, int error) {
            Toast.makeText(PlayerActivity.this, "Couldn't connect to the Chromecast", Toast.LENGTH_SHORT).show();
        }
        @Override public void onSessionEnding(CastSession s) {}
        @Override public void onSessionResuming(CastSession s, String id) {}
        @Override public void onSessionResumeFailed(CastSession s, int error) {}
        @Override public void onSessionSuspended(CastSession s, int reason) {}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        urls = getIntent().getStringArrayExtra("urls");
        title = getIntent().getStringExtra("title");
        if (urls == null || urls.length == 0) { finish(); return; }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new PlayerView(this);
        view.setKeepScreenOn(true);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        castNote = new TextView(this);
        castNote.setTextColor(Color.WHITE);
        castNote.setTextSize(18);
        castNote.setGravity(Gravity.CENTER);
        castNote.setVisibility(View.GONE);
        root.addView(castNote, new FrameLayout.LayoutParams(-1, -1));

        setupCast(root);
        setContentView(root);
        hideBars();

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(getIntent().getStringExtra("ua"))
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .build();
        view.setPlayer(player);
        // Controls: hidden while a stream plays, shown on a tap or remote press, and hide again after a few seconds.
        view.setControllerAutoShow(false);
        view.setControllerShowTimeoutMs(3500);
        view.setControllerHideOnTouch(true);
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying && view != null) view.hideController();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (index + 1 < urls.length) {
                    index++;
                    start();
                } else {
                    Toast.makeText(PlayerActivity.this, "This channel couldn't be played", Toast.LENGTH_LONG).show();
                    finish();
                }
            }
        });
        if (title != null) Toast.makeText(this, title, Toast.LENGTH_SHORT).show();
        start();
    }

    private boolean isTv() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    /** Adds a cast button where Google Play services exist (not on Fire TV, and not on TVs). */
    private void setupCast(FrameLayout root) {
        if (isTv()) return;
        try {
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) != ConnectionResult.SUCCESS) return;
            sessions = CastContext.getSharedInstance(this).getSessionManager();
            MediaRouteButton button = new MediaRouteButton(this);
            CastButtonFactory.setUpMediaRouteButton(getApplicationContext(), button);
            int size = (int) (48 * getResources().getDisplayMetrics().density);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
            lp.setMargins(0, size / 3, size / 3, 0);
            root.addView(button, lp);
        } catch (Exception e) {
            sessions = null;
        }
    }

    private void castTo(CastSession session) {
        RemoteMediaClient client = session == null ? null : session.getRemoteMediaClient();
        if (client == null) return;
        String url = urls[0];
        MediaMetadata meta = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        meta.putString(MediaMetadata.KEY_TITLE, title == null ? "Reborn Sport" : title);
        boolean hls = url.contains(".m3u8");
        MediaInfo info = new MediaInfo.Builder(url)
                .setStreamType(url.contains("timeshift") ? MediaInfo.STREAM_TYPE_BUFFERED : MediaInfo.STREAM_TYPE_LIVE)
                .setContentType(hls ? "application/x-mpegURL" : "video/mp2t")
                .setMetadata(meta)
                .build();
        client.load(new MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build());
        if (player != null) player.pause();
        String device = session.getCastDevice() != null ? session.getCastDevice().getFriendlyName() : "your TV";
        castNote.setText("Playing on " + device);
        castNote.setVisibility(View.VISIBLE);
    }

    private void backToPhone() {
        castNote.setVisibility(View.GONE);
        if (player != null) { player.seekToDefaultPosition(); player.play(); }
    }

    private void start() {
        player.setMediaItem(MediaItem.fromUri(urls[index]));
        player.prepare();
        player.play();
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && view != null) hideBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sessions != null) sessions.addSessionManagerListener(castListener, CastSession.class);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sessions != null && !isInPip()) sessions.removeSessionManagerListener(castListener, CastSession.class);
    }

    private boolean isInPip() {
        return Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode();
    }

    private boolean canPip() {
        return Build.VERSION.SDK_INT >= 26
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
                && !isTv();
    }

    /** Leaving the player on a phone shrinks it to a floating window instead of stopping. */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (canPip() && player != null && player.isPlaying()) {
            try {
                enterPictureInPictureMode(new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9)).build());
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPip, newConfig);
        if (view != null) view.setUseController(!inPip);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Live TV: leaving the player ends local playback (a cast keeps playing on the TV).
        if (player != null) { player.release(); player = null; }
        finish();
    }
}
