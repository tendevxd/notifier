package com.ten.notifier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;

import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class ServerService extends Service {
    static final String FG = "fg";
    static final String ALERT = "alert";

    NanoHTTPD server;
    TextToSpeech tts;
    boolean ttsReady = false;
    PowerManager.WakeLock wl;
    int nid = 100;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        tts = new TextToSpeech(this, status -> ttsReady = (status == TextToSpeech.SUCCESS));
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "notifier:server");
        wl.acquire();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(FG, "Server", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(ALERT, "Alerts", NotificationManager.IMPORTANCE_HIGH));

        Notification n = new Notification.Builder(this, FG)
                .setContentTitle("Notifier running")
                .setContentText("Listening on port 5000")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }

        if (server == null) {
            server = new NanoHTTPD(5000) {
                @Override
                public Response serve(IHTTPSession session) {
                    try {
                        Map<String, String> files = new HashMap<>();
                        session.parseBody(files);
                        Map<String, String> p = session.getParms();
                        String title = p.containsKey("title") ? p.get("title") : "Alert";
                        String text = p.containsKey("text") ? p.get("text") : "";
                        boolean speak = "1".equals(p.get("speak"));
                        show(title, text, speak);
                        return newFixedLengthResponse("ok");
                    } catch (Exception e) {
                        return newFixedLengthResponse(e.toString());
                    }
                }
            };
            try {
                server.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return START_STICKY;
    }

    void show(String title, String text, boolean speak) {
        Notification n = new Notification.Builder(this, ALERT)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(nid++, n);

        if (speak && ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, null);
        }
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        if (tts != null) tts.shutdown();
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }
}
