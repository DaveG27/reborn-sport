package com.rebornsport.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Shows the kick-off notification when a reminder's alarm fires. */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String id = intent.getStringExtra("id");
        String title = intent.getStringExtra("title");
        String text = intent.getStringExtra("text");
        if (id != null) Reminders.removeFired(c, id);

        Reminders.ensureChannel(c);
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, Reminders.CHANNEL)
                : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title == null ? "Kick-off soon" : title)
                .setContentText(text == null ? "" : text)
                .setStyle(new Notification.BigTextStyle().bigText(text == null ? "" : text))
                .setAutoCancel(true)
                .setContentIntent(tap);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);

        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try { nm.notify(id == null ? 0 : id.hashCode(), b.build()); } catch (SecurityException ignored) {}
        }
    }
}
