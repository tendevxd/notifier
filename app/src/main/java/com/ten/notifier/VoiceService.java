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
    static final long HEARTBEAT_MS = 90_000L;     // if no result in 90s, restart the recognizer
    static final long MAX_BACKOFF_MS = 30_000L;   // cap the retry delay
    static final int PERM_ERROR_LIMIT = 2;        // give up after this many permission errors

    SpeechRecognizer sr;
    Intent recIntent;
    Handler h = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    volatile boolean running = false;
    long lastFire = 0;
    long lastResultAt = 0;
    long backoff = 300;
    int permErrors = 0;
    Runnable heartbeat;

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
            startHeartbeat();
        }
        return START_STICKY;   // F5c: ask Android to restart us if it kills us
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // F5c: if the user swipes the app away but voice is still wanted, restart the service.
        if (prefs.getBoolean("voice_on", false)) {
            Intent restart = new Intent(getApplicationContext(), VoiceService.class);
            restart.setPackage(getPackageName());
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(restart);
                } else {
                    startService(restart);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        super.onTaskRemoved(rootIntent);
    }

    // F5c: heartbeat watchdog. If the recognizer goes quiet for HEARTBEAT_MS, tear it down
    // and rebuild. Keeps the service alive through weird OEM speech-engine states.
    void startHeartbeat() {
        if (heartbeat != null) h.removeCallbacks(heartbeat);
        heartbeat = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                long silence = System.currentTimeMillis() - lastResultAt;
                if (lastResultAt > 0 && silence > HEARTBEAT_MS) {
                    restartRecognizer();
                }
                h.postDelayed(this, 30_000L);
            }
        };
        h.postDelayed(heartbeat, HEARTBEAT_MS);
    }

    void restartRecognizer() {
        try {
            if (sr != null) {
                sr.cancel();
                sr.destroy();
            }
        } catch (Exception ignored) {
        }
        sr = null;
        backoff = 300;
        lastResultAt = System.currentTimeMillis();
        begin();
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
                    permErrors++;
                    if (permErrors >= PERM_ERROR_LIMIT) {
                        prefs.edit().putBoolean("voice_on", false).apply();
                        stopSelf();
                        return;
                    }
                    again(5000);
                    return;
                }

                // Classify the error to pick a sensible retry delay.
                long delay;
                if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_CLIENT) {
                    delay = backoff;
                    backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
                } else if (e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                    delay = 5000;
                } else if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    delay = 400;   // normal silence, reset the backoff
                    backoff = 300;
                } else if (e == SpeechRecognizer.ERROR_AUDIO) {
                    delay = 3000;
                } else {
                    delay = backoff;
                    backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
                }
                again(delay);
            }

            @Override
            public void onResults(Bundle b) {
                lastResultAt = System.currentTimeMillis();
                backoff = 300;
                permErrors = 0;
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
        lastResultAt = System.currentTimeMillis();
        again(0);
    }

    void again(long delay) {
        if (!running) return;
        h.postDelayed(() -> {
            if (!running) return;
            try {
                if (sr == null) return;
                sr.cancel();
                sr.startListening(recIntent);
            } catch (Exception e) {
                // If the recognizer itself is broken, rebuild it.
                if (backoff >= MAX_BACKOFF_MS) {
                    restartRecognizer();
                } else {
                    backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
                    again(backoff);
                }
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
        if (sr != null) {
            try {
                sr.cancel();
                sr.destroy();
            } catch (Exception ignored) {
            }
            sr = null;
        }
        super.onDestroy();
    }
}