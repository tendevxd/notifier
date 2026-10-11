package com.ten.notifier;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Quick Settings tiles: pull down the shade and tap to control the PC. */
public class Tiles {

    public abstract static class Base extends TileService {
        abstract String path();

        abstract String body();

        @Override
        public void onStartListening() {
            Tile t = getQsTile();
            if (t != null) {
                t.setState(Tile.STATE_INACTIVE);
                t.updateTile();
            }
        }

        @Override
        public void onClick() {
            final String p = path();
            final String b = body();
            new Thread(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:5000" + p).openConnection();
                    c.setConnectTimeout(2000);
                    c.setReadTimeout(15000);
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    OutputStream os = c.getOutputStream();
                    os.write(b.getBytes("UTF-8"));
                    os.close();
                    c.getResponseCode();
                    c.disconnect();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }

    public static class Shot extends Base {
        String path() {
            return "/api/command";
        }

        String body() {
            return "cmd=screenshot";
        }
    }

    public static class PlayPause extends Base {
        String path() {
            return "/api/input";
        }

        String body() {
            return "msg=media+playpause";
        }
    }

    public static class Lock extends Base {
        String path() {
            return "/api/command";
        }

        String body() {
            return "cmd=power%3Alock";
        }
    }
}
