package com.ten.notifier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Locale;

public class VoiceService extends Service {
    SpeechRecognizer sr;
    Intent recIntent;
    Handler h = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    boolean running = false;
    long lastFire = 0;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        prefs = getSharedPreferences("notifier", MODE_PRIVATE);
        if (intent != null && "stop".equals(intent.getAction())) {
            prefs.edit().putBoolean("voice_on", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }

        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("voice", "Voice commands", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, VoiceService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "voice")
                .setContentTitle("Listening for your voice commands")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .addAction(new Notification.Action.Builder(0, "Turn off", stop).build())
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(2, n);
            }
        } catch (Exception e) {
            e.printStackTrace();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!running) {
            running = true;
            h.post(this::begin);
        }
        return START_STICKY;
    }

    void begin() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            prefs.edit().putBoolean("voice_on", false).apply();
            stopSelf();
            return;
        }
        sr = SpeechRecognizer.createSpeechRecognizer(this);
        sr.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onPartialResults(Bundle b) { }
            @Override public void onEvent(int t, Bundle b) { }

            @Override
            public void onError(int e) {
                if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    stopSelf();
                    return;
                }
                again(e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_CLIENT ? 1200 : 300);
            }

            @Override
            public void onResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null) match(r);
                again(300);
            }
        });
        recIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.getString("voice_lang", "en-US"));
        recIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        recIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        recIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        again(0);
    }

    void again(long delay) {
        if (!running) return;
        h.postDelayed(() -> {
            try {
                sr.cancel();
                sr.startListening(recIntent);
            } catch (Exception e) {
                again(1500);
            }
        }, delay);
    }

    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", "").trim().replaceAll("\\s+", " ");
    }

    void match(ArrayList<String> heard) {
        try {
            JSONArray list = new JSONArray(prefs.getString("kv_voice", "[]"));
            for (String one : heard) {
                String said = norm(one);
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.getJSONObject(i);
                    if (!said.isEmpty() && said.equals(norm(o.optString("phrase", "")))) {
                        fire(said, o.optString("cmd", ""));
                        return;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void fire(final String said, final String cmd) {
        long now = System.currentTimeMillis();
        if (cmd.isEmpty() || now - lastFire < 1500) return;
        lastFire = now;
        new Thread(() -> {
            if (cmd.startsWith("input:")) {
                local("/api/input", "msg=" + enc(cmd.substring(6)));
            } else {
                local("/api/command", "cmd=" + enc(cmd));
            }
            local("/voice", "text=" + enc("Heard: " + said));
        }).start();
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    void local(String path, String body) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:5000" + path).openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
            c.getResponseCode();
            c.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        h.removeCallbacksAndMessages(null);
        if (sr != null) sr.destroy();
        super.onDestroy();
    }
}
