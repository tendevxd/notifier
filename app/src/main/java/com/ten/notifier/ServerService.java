package com.ten.notifier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
        IntentFilter bf = new IntentFilter(Intent.ACTION_BATTERY_LOW);
        bf.addAction(Intent.ACTION_BATTERY_OKAY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(battery, bf, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(battery, bf);
        }
    }

    void makeChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(FG, "Server", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel("quiet", "Quiet", NotificationManager.IMPORTANCE_LOW));
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
        if (udpThread == null) startDiscovery();
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
                Map<String, String> files = new HashMap<String, String>();
                if (s.getMethod() == Method.POST) s.parseBody(files);
                Map<String, String> p = s.getParms();
                String pin = prefs.getString("pin", "");
                if (uri.startsWith("/api/") && !uri.equals("/api/unlock") && !pin.isEmpty()
                        && !"127.0.0.1".equals(s.getRemoteIpAddress()) && !pin.equals(p.get("pin"))) {
                    return json("{\"locked\":true}");
                }

                switch (uri) {
                    case "/":
                        return page();
                    case "/notify":
                        show(arg(p, "title", "Alert"), arg(p, "text", ""),
                                "1".equals(p.get("speak")), arg(p, "type", "info"), arg(p, "lang", ""));
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
                    case "/api/timer": {
                        final String tx = arg(p, "text", "Time is up.");
                        long ms = (long) (Double.parseDouble(arg(p, "minutes", "1")) * 60000);
                        handler.postDelayed(() -> show("Timer", tx, true, "info"), ms);
                        return json("{\"ok\":true}");
                    }
                    case "/api/deadlines":
                        new JSONArray(arg(p, "list", "[]"));
                        prefs.edit().putString("deadlines", arg(p, "list", "[]")).apply();
                        return json("{\"ok\":true}");
                    case "/api/unlock": {
                        JSONObject r = new JSONObject();
                        r.put("set", !pin.isEmpty());
                        r.put("ok", pin.isEmpty() || pin.equals(p.get("pin")));
                        return json(r.toString());
                    }
                    case "/api/prefs": {
                        prefs.edit()
                                .putBoolean("q_on", "1".equals(p.get("q_on")))
                                .putString("q_from", arg(p, "q_from", "23:00"))
                                .putString("q_to", arg(p, "q_to", "07:00"))
                                .putString("tts_lang", arg(p, "tts_lang", ""))
                                .putFloat("tts_rate", Float.parseFloat(arg(p, "tts_rate", "1")))
                                .apply();
                        if (p.containsKey("newpin")) prefs.edit().putString("pin", p.get("newpin")).apply();
                        return json("{\"ok\":true}");
                    }
                    case "/stats": {
                        JSONObject st = new JSONObject();
                        for (Map.Entry<String, String> e : p.entrySet()) st.put(e.getKey(), e.getValue());
                        st.put("t", System.currentTimeMillis());
                        stats = st;
                        return json("{\"ok\":true}");
                    }
                    case "/api/photo": {
                        String b64 = files.get("postData");
                        JSONObject r = new JSONObject();
                        if (b64 == null) {
                            r.put("msg", "No photo received.");
                        } else {
                            r.put("msg", post("/photo?name=" + URLEncoder.encode(arg(p, "name", "photo.jpg"), "UTF-8"), b64));
                        }
                        return json(r.toString());
                    }
                    case "/api/clip": {
                        JSONObject r = new JSONObject();
                        r.put("msg", post("/clip", "text=" + URLEncoder.encode(arg(p, "text", ""), "UTF-8")));
                        return json(r.toString());
                    }
                    case "/clip": {
                        final String t = arg(p, "text", "");
                        handler.post(() -> ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                                .setPrimaryClip(ClipData.newPlainText("Notifier", t)));
                        show("Clipboard", "Copied from your PC: " + (t.length() > 80 ? t.substring(0, 80) + "..." : t), false, "info");
                        return json("{\"ok\":true}");
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
        show(title, text, speak, type, null);
    }

    void show(String title, String text, boolean speak, String type, String lang) {
        if (!type.equals("warn") && !type.equals("error")) type = "info";
        boolean quiet = !type.equals("error") && quietNow();
        String chan = quiet ? "quiet" : type;

        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, chan)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(nid++, n);

        addHistory(title, text, type);
        if (speak && ttsReady && !quiet) say(text, lang);
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

    List<Runnable> tasks = new ArrayList<>();

    void scheduleReminder() {
        for (Runnable r : tasks) handler.removeCallbacks(r);
        tasks.clear();
        try {
            JSONArray a = new JSONArray(prefs.getString("reminders", "[]"));
            for (int i = 0; i < a.length(); i++) {
                final JSONObject o = a.getJSONObject(i);
                final long every = o.optInt("every", 0) * 60000L;
                final String at = o.optString("at", "");
                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        show("Reminder", o.optString("text"), o.optBoolean("speak", true), "info");
                        handler.postDelayed(this, every > 0 ? every : 86400000L);
                    }
                };
                tasks.add(r);
                handler.postDelayed(r, every > 0 ? every : untilMs(at));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    long untilMs(String hhmm) {
        try {
            String[] t = hhmm.split(":");
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(t[0]));
            c.set(Calendar.MINUTE, Integer.parseInt(t[1]));
            c.set(Calendar.SECOND, 0);
            if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1);
            return c.getTimeInMillis() - System.currentTimeMillis();
        } catch (Exception e) {
            return 86400000L;
        }
    }

    void saveReminder(Map<String, String> p) throws Exception {
        String list = arg(p, "list", "[]");
        new JSONArray(list);
        prefs.edit().putString("reminders", list).apply();
        handler.post(this::scheduleReminder);
    }

    Thread udpThread;
    volatile JSONObject stats = new JSONObject();

    BroadcastReceiver battery = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            final String name = Intent.ACTION_BATTERY_LOW.equals(i.getAction()) ? "battery_low" : "battery_ok";
            new Thread(() -> post("/event", "name=" + name)).start();
        }
    };

    void startDiscovery() {
        udpThread = new Thread(() -> {
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                wm.createMulticastLock("notifier").acquire();
                DatagramSocket s = new DatagramSocket(null);
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(5001));
                byte[] buf = new byte[64];
                while (true) {
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    s.receive(in);
                    if (new String(in.getData(), 0, in.getLength()).startsWith("NOTIFIER?")) {
                        byte[] r = "NOTIFIER 5000".getBytes();
                        s.send(new DatagramPacket(r, r.length, in.getAddress(), in.getPort()));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        udpThread.start();
    }

    String discoverPc() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(1500);
            byte[] q = "PC?".getBytes();
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName("255.255.255.255"), 5002));
            DatagramPacket in = new DatagramPacket(new byte[64], 64);
            s.receive(in);
            String[] t = new String(in.getData(), 0, in.getLength()).trim().split(" ");
            if (t[0].equals("PC")) return in.getAddress().getHostAddress() + ":" + t[1];
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    void say(String text, String lang) {
        String l = (lang == null || lang.isEmpty()) ? prefs.getString("tts_lang", "") : lang;
        tts.setLanguage(l.isEmpty() ? Locale.getDefault() : new Locale(l));
        tts.setSpeechRate(prefs.getFloat("tts_rate", 1.0f));
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, null);
    }

    boolean quietNow() {
        if (!prefs.getBoolean("q_on", false)) return false;
        try {
            int f = mins(prefs.getString("q_from", "23:00"));
            int t = mins(prefs.getString("q_to", "07:00"));
            Calendar c = Calendar.getInstance();
            int n = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            return f <= t ? (n >= f && n < t) : (n >= f || n < t);
        } catch (Exception e) {
            return false;
        }
    }

    int mins(String s) {
        String[] p = s.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
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
        o.put("reminders", new JSONArray(prefs.getString("reminders", "[]")));
        o.put("deadlines", new JSONArray(prefs.getString("deadlines", "[]")));
        JSONArray ak = new JSONArray();
        for (JSONObject a : asks.values()) ak.put(a);
        o.put("asks", ak);
        o.put("stats", stats);
        o.put("statsAge", System.currentTimeMillis() - stats.optLong("t", 0));
        JSONObject pf = new JSONObject();
        pf.put("q_on", prefs.getBoolean("q_on", false));
        pf.put("q_from", prefs.getString("q_from", "23:00"));
        pf.put("q_to", prefs.getString("q_to", "07:00"));
        pf.put("tts_lang", prefs.getString("tts_lang", ""));
        pf.put("tts_rate", (double) prefs.getFloat("tts_rate", 1.0f));
        pf.put("pin", !prefs.getString("pin", "").isEmpty());
        o.put("prefs", pf);
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
        if (pc.isEmpty()) {
            pc = discoverPc();
            if (pc.isEmpty()) return "Couldn't find the PC. Is pc_remote.py running?";
            prefs.edit().putString("pc", pc).apply();
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + pc + path).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(15000);
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
            String d = discoverPc();
            if (!d.isEmpty() && !d.equals(pc)) {
                prefs.edit().putString("pc", d).apply();
                return post(path, body);
            }
            return "Couldn't reach the PC. Is pc_remote.py running?";
        }
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(battery);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (server != null) server.stop();
        if (tts != null) tts.shutdown();
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }
}
