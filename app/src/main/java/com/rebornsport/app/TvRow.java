package com.rebornsport.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;

import androidx.tvprovider.media.tv.PreviewChannel;
import androidx.tvprovider.media.tv.PreviewChannelHelper;
import androidx.tvprovider.media.tv.PreviewProgram;
import androidx.tvprovider.media.tv.TvContractCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Android TV / Google TV home screen: a "Reborn Sport" row listing the next live games.
 * Fire TV doesn't support these rows, so this quietly does nothing there.
 */
final class TvRow {
    private TvRow() {}

    static void update(Context c, String gamesJson) {
        if (Build.VERSION.SDK_INT < 26) return;
        if (!c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return;
        try {
            SharedPreferences prefs = c.getSharedPreferences("tvrow", Context.MODE_PRIVATE);
            PreviewChannelHelper helper = new PreviewChannelHelper(c);
            Uri open = Uri.parse("rebornsport://open");

            long channelId = prefs.getLong("channel", -1);
            if (channelId == -1 || helper.getPreviewChannel(channelId) == null) {
                Bitmap logo = BitmapFactory.decodeResource(c.getResources(), R.drawable.tv_logo);
                PreviewChannel channel = new PreviewChannel.Builder()
                        .setDisplayName("Reborn Sport · live sport")
                        .setAppLinkIntentUri(open)
                        .setLogo(logo)
                        .build();
                channelId = helper.publishChannel(channel);
                TvContractCompat.requestChannelBrowsable(c, channelId);
                prefs.edit().putLong("channel", channelId).putString("programs", "[]").apply();
            }

            // Replace the previous programmes with the current list.
            JSONArray old = new JSONArray(prefs.getString("programs", "[]"));
            for (int i = 0; i < old.length(); i++) {
                try { helper.deletePreviewProgram(old.getLong(i)); } catch (Exception ignored) {}
            }

            SimpleDateFormat fmt = new SimpleDateFormat("EEE HH:mm", Locale.UK);
            fmt.setTimeZone(TimeZone.getTimeZone("Europe/London"));
            Uri poster = Uri.parse("android.resource://" + c.getPackageName() + "/drawable/tv_poster");
            long now = System.currentTimeMillis();
            JSONArray games = new JSONArray(gamesJson == null ? "[]" : gamesJson);
            JSONArray ids = new JSONArray();
            for (int i = 0; i < games.length() && ids.length() < 10; i++) {
                JSONObject g = games.getJSONObject(i);
                long s = g.optLong("s"), e = g.optLong("e");
                if (e <= now) continue;
                boolean onNow = s <= now;
                PreviewProgram p = new PreviewProgram.Builder()
                        .setChannelId(channelId)
                        .setType(TvContractCompat.PreviewPrograms.TYPE_EVENT)
                        .setTitle(g.optString("title"))
                        .setDescription((onNow ? "On now" : fmt.format(new Date(s))) + " · " + g.optString("ch"))
                        .setPosterArtUri(poster)
                        .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                        .setLive(onNow)
                        .setIntentUri(open)
                        .build();
                ids.put(helper.publishPreviewProgram(p));
            }
            prefs.edit().putString("programs", ids.toString()).apply();
        } catch (Exception ignored) {
            // The TV launcher may not support preview channels; the app works without them.
        }
    }
}
