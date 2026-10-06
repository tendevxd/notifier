package com.ten.notifier;

import android.app.Activity;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }

        startForegroundService(new Intent(this, ServerService.class));

        int ip = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE))
                .getConnectionInfo().getIpAddress();
        String addr = (ip & 255) + "." + ((ip >> 8) & 255) + "." + ((ip >> 16) & 255) + "." + ((ip >> 24) & 255);

        TextView t = new TextView(this);
        t.setTextSize(20);
        t.setPadding(40, 80, 40, 40);
        t.setText("Running.\n\nSend to:\nhttp://" + addr + ":5000/notify");
        setContentView(t);
    }
}
