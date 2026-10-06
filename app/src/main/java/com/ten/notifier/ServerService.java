package com.ten.notifier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD;

public class ServerService extends Service {
    static final String FG = "fg";

    NanoHTTPD server;
    TextToSpeech tts;
    boolean ttsReady = false;
    PowerManager.WakeLock wl;
    SharedPreferences prefs;
    Handler handler = new Handler(Looper.getMainLooper());
    Runnable reminderTask;
    int nid = 100;
    ConcurrentHashMap<String, JSONObject> asks = new ConcurrentHashMap<>();
    ConcurrentHashMap<String, String> answers = new ConcurrentHashMap<>();

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("notifier", MODE_PRIVATE);
        tts = new TextToSpeech(this, status -> ttsReady = (status == TextToSpeech.SUCCESS));
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "notifier:server");
        wl.acquire();
        makeChannels();
    }

    void makeChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(FG, "Server", NotificationManager.IMPORTANCE_LOW));
        channel(nm, "info", "Info", Settings.System.DEFAULT_NOTIFICATION_URI,
                AudioAttributes.USAGE_NOTIFICATION, new long[]{0, 200});
        channel(nm, "warn", "Warning", Settings.System.DEFAULT_RINGTONE_URI,
                AudioAttributes.USAGE_NOTIFICATION_RINGTONE, new long[]{0, 400, 200, 400});
        channel(nm, "error", "Error", Settings.System.DEFAULT_ALARM_ALERT_URI,
                AudioAttributes.USAGE_ALARM, new long[]{0, 800, 300, 800, 300, 800});
    }

    void channel(NotificationManager nm, String id, String name, Uri sound, int usage, long[] vib) {
        NotificationChannel c = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH);
        c.setSound(sound, new AudioAttributes.Builder().setUsage(usage).build());
        c.enableVibration(true);
        c.setVibrationPattern(vib);
        nm.createNotificationChannel(c);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
            server = new Api();
            try {
                server.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        scheduleReminder();
        return START_STICKY;
    }

    static String arg(Map<String, String> p, String k, String d) {
        String v = p.get(k);
        return v == null ? d : v;
    }

    class Api extends NanoHTTPD {
        Api() {
            super(5000);
        }

        Response json(String s) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", s);
        }

        Response page() throws Exception {
            InputStream in = getAssets().open("index.html");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return newFixedLengthResponse(Response.Status.OK, "text/html", out.toString("UTF-8"));
        }

        @Override
        public Response serve(IHTTPSession s) {
            try {
                String uri = s.getUri();
                if (s.getMethod() == Method.POST) s.parseBody(new HashMap<String, String>());
                Map<String, String> p = s.getParms();

                switch (uri) {
                    case "/":
                        return page();
                    case "/notify":
                        show(arg(p, "title", "Alert"), arg(p, "text", ""),
                                "1".equals(p.get("speak")), arg(p, "type", "info"));
                        return json("{\"ok\":true}");
                    case "/api/state":
                        return json(state().toString());
                    case "/api/reminder":
                        saveReminder(p);
                        return json("{\"ok\":true}");
                    case "/api/settings": {
                        new JSONArray(arg(p, "commands", "[]"));
                        prefs.edit()
                                .putString("pc", arg(p, "pc", "").trim())
                                .putString("commands", arg(p, "commands", "[]"))
                                .apply();
                        return json("{\"ok\":true}");
                    }
                    case "/api/command": {
                        JSONObject r = new JSONObject();
                        r.put("msg", sendCommand(arg(p, "cmd", "")));
                        return json(r.toString());
                    }
                    case "/ask": {
                        String id = String.valueOf(System.currentTimeMillis());
                        JSONObject a = new JSONObject();
                        a.put("id", id);
                        a.put("title", arg(p, "title", "Question"));
                        a.put("text", arg(p, "text", ""));
                        JSONArray opts = new JSONArray();
                        for (String o : arg(p, "options", "Yes,No").split(",")) opts.put(o.trim());
                        a.put("options", opts);
                        asks.put(id, a);
                        show(a.getString("title"), a.getString("text"), true, "warn");
                        JSONObject r = new JSONObject();
                        r.put("id", id);
                        return json(r.toString());
                    }
                    case "/api/answer":
                        answers.put(arg(p, "id", ""), arg(p, "answer", ""));
                        asks.remove(arg(p, "id", ""));
                        return json("{\"ok\":true}");
                    case "/answer": {
                        String ans = answers.get(arg(p, "id", ""));
                        JSONObject r = new JSONObject();
                        r.put("answer", ans == null ? JSONObject.NULL : ans);
                        return json(r.toString());
                    }
                    case "/api/value": {
                        JSONObject r = new JSONObject();
                        r.put("msg", sendValue(arg(p, "name", ""), arg(p, "value", "")));
                        return json(r.toString());
                    }
                    case "/api/clear":
                        prefs.edit().putString("history", "[]").apply();
                        return json("{\"ok\":true}");
                    default:
                        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found");
                }
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.toString());
            }
        }
    }

    void show(String title, String text, boolean speak, String type) {
        if (!type.equals("warn") && !type.equals("error")) type = "info";

        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, type)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(nid++, n);

        addHistory(title, text, type);
        if (speak && ttsReady) tts.speak(text, TextToSpeech.QUEUE_ADD, null, null);
        if (type.equals("error")) flash();
    }

    synchronized void addHistory(String title, String text, String type) {
        try {
            JSONArray old = new JSONArray(prefs.getString("history", "[]"));
            JSONArray arr = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("text", text);
            o.put("type", type);
            o.put("t", System.currentTimeMillis());
            arr.put(o);
            for (int i = 0; i < old.length() && i < 49; i++) arr.put(old.get(i));
            prefs.edit().putString("history", arr.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void flash() {
        new Thread(() -> {
            try {
                CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
                String id = null;
                for (String c : cm.getCameraIdList()) {
                    Boolean f = cm.getCameraCharacteristics(c).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (f != null && f) {
                        id = c;
                        break;
                    }
                }
                if (id == null) return;
                for (int i = 0; i < 6; i++) {
                    cm.setTorchMode(id, true);
                    Thread.sleep(200);
                    cm.setTorchMode(id, false);
                    Thread.sleep(200);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    void scheduleReminder() {
        if (reminderTask != null) handler.removeCallbacks(reminderTask);
        reminderTask = null;
        if (!prefs.getBoolean("rem_on", false)) return;

        final long ms = Math.max(1, prefs.getInt("rem_min", 60)) * 60000L;
        reminderTask = new Runnable() {
            @Override
            public void run() {
                show("Reminder", prefs.getString("rem_text", "Stretch and drink water."),
                        prefs.getBoolean("rem_speak", true), "info");
                handler.postDelayed(this, ms);
            }
        };
        handler.postDelayed(reminderTask, ms);
    }

    void saveReminder(Map<String, String> p) {
        int min = 60;
        try {
            min = Integer.parseInt(arg(p, "minutes", "60"));
        } catch (Exception e) {
            e.printStackTrace();
        }
        prefs.edit()
                .putBoolean("rem_on", "1".equals(p.get("on")))
                .putInt("rem_min", Math.max(1, min))
                .putString("rem_text", arg(p, "text", "Stretch and drink water."))
                .putBoolean("rem_speak", "1".equals(p.get("speak")))
                .apply();
        handler.post(this::scheduleReminder);
    }

    String ip() {
        int ip = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE))
                .getConnectionInfo().getIpAddress();
        return (ip & 255) + "." + ((ip >> 8) & 255) + "." + ((ip >> 16) & 255) + "." + ((ip >> 24) & 255);
    }

    JSONObject state() throws Exception {
        JSONObject o = new JSONObject();
        o.put("ip", ip());
        o.put("history", new JSONArray(prefs.getString("history", "[]")));
        JSONObject r = new JSONObject();
        r.put("on", prefs.getBoolean("rem_on", false));
        r.put("minutes", prefs.getInt("rem_min", 60));
        r.put("text", prefs.getString("rem_text", "Stretch and drink water."));
        r.put("speak", prefs.getBoolean("rem_speak", true));
        o.put("reminder", r);
        JSONArray ak = new JSONArray();
        for (JSONObject a : asks.values()) ak.put(a);
        o.put("asks", ak);
        o.put("pc", prefs.getString("pc", ""));
        o.put("commands", new JSONArray(prefs.getString("commands", "[]")));
        return o;
    }

    String sendCommand(String cmd) {
        try {
            return post("/cmd", "cmd=" + URLEncoder.encode(cmd, "UTF-8"));
        } catch (Exception e) {
            return "Error.";
        }
    }

    String sendValue(String name, String v) {
        try {
            return post("/val", "name=" + URLEncoder.encode(name, "UTF-8") + "&value=" + URLEncoder.encode(v, "UTF-8"));
        } catch (Exception e) {
            return "Error.";
        }
    }

    String post(String path, String body) {
        String pc = prefs.getString("pc", "");
        if (pc.isEmpty()) return "Set the PC address in Setup first.";
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + pc + path).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200 ? "Sent." : "PC answered " + code + ".";
        } catch (Exception e) {
            return "Couldn't reach the PC. Is pc_remote.py running?";
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
