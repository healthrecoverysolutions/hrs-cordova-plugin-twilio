package com.phonegap.plugins.twiliovoice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

public class AudioForegroundService extends Service {

    private static boolean isServiceRunning = false;

    public static boolean isRunning() {
        return isServiceRunning;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isServiceRunning = true;
        startForeground(1, createNotification());
    }

    private Notification createNotification() {
        String channelId = "ForegroundServiceChannel";
        NotificationChannel channel = new NotificationChannel(
            channelId, "Foreground Service", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        return new NotificationCompat.Builder(this, channelId)
            .setContentTitle("Voice call")
            .setContentText("Voice call is in progress")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isServiceRunning = false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
