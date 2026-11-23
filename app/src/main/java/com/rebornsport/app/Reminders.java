package com.rebornsport.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.util.Iterator;

/** Stores kick-off reminders and schedules them with AlarmManager. */
final class Reminders {
    static final String CHANNEL = "kickoff";
    private static final String PREFS = "reminders";

    private Reminders() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static JSONObject all(Context c) {
        try { return new JSONObject(prefs(c).getString("all", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    private static void save(Context c, JSONObject o) {
        prefs(c).edit().putString("all", o.toString()).apply();
    }

    static void set(Context c, String id, long at, String title, String text) {
        try {
            JSONObject o = all(c);
            JSONObject r = new JSONObject();
            r.put("at", at); r.put("title", title); r.put("text", text);
            o.put(id, r);
            save(c, o);
            schedule(c, id, at, title, text);
        } catch (Exception ignored) {}
    }

    static void cancel(Context c, String id) {
        JSONObject o = all(c);
        o.remove(id);
        save(c, o);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c, id, "", ""));
    }

    static void removeFired(Context c, String id) {
        JSONObject o = all(c);
        o.remove(id);
        save(c, o);
    }

    /** Re-arm every saved reminder that is still in the future (after a reboot). */
    static void rescheduleAll(Context c) {
        JSONObject o = all(c);
        long now = System.currentTimeMillis();
        Iterator<String> keys = o.keys();
        JSONObject keep = new JSONObject();
        while (keys.hasNext()) {
            String id = keys.next();
            JSONObject r = o.optJSONObject(id);
            if (r == null) continue;
            long at = r.optLong("at");
            if (at <= now) continue;
            try { keep.put(id, r); } catch (Exception ignored) {}
            schedule(c, id, at, r.optString("title"), r.optString("text"));
        }
        save(c, keep);
    }

    private static PendingIntent pending(Context c, String id, String title, String text) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.setAction("com.rebornsport.app.REMIND." + id.hashCode());
        i.putExtra("id", id);
        i.putExtra("title", title);
        i.putExtra("text", text);
        return PendingIntent.getBroadcast(c, id.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void schedule(Context c, String id, long at, String title, String text) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        // Inexact but battery-friendly; Android may deliver it a few minutes early or late.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, id, title, text));
    }

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Kick-off reminders", NotificationManager.IMPORTANCE_HIGH));
            }
        }
    }
}
