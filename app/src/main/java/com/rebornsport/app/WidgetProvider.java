package com.rebornsport.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Home-screen widget: the next few live games, from the list the app last saved. */
public class WidgetProvider extends AppWidgetProvider {

    static void refreshAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, WidgetProvider.class));
        if (ids.length > 0) new WidgetProvider().onUpdate(c, m, ids);
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        String body = buildBody(c);
        Intent open = new Intent(c, MainActivity.class);
        PendingIntent tap = PendingIntent.getActivity(c, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
            v.setTextViewText(R.id.widget_body, body);
            v.setOnClickPendingIntent(R.id.widget_root, tap);
            m.updateAppWidget(id, v);
        }
    }

    private static String buildBody(Context c) {
        String raw = c.getSharedPreferences("widget", Context.MODE_PRIVATE).getString("games", "");
        if (raw.isEmpty()) return "Open Reborn Sport to load listings";
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.UK);
        fmt.setTimeZone(TimeZone.getTimeZone("Europe/London"));
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length() && n < 5; i++) {
                JSONObject g = a.getJSONObject(i);
                long s = g.optLong("s"), e = g.optLong("e");
                if (e <= now) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(s <= now ? "ON NOW" : fmt.format(new Date(s)))
                        .append("  ").append(g.optString("title"))
                        .append(" · ").append(g.optString("ch"));
                n++;
            }
        } catch (Exception ignored) {}
        return n == 0 ? "No more live games today" : sb.toString();
    }
}
