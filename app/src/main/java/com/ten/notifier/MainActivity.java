package com.ten.notifier;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    static final String URL = "http://127.0.0.1:5000/";
    WebView web;
    String target = URL;
    ValueCallback<Uri[]> filePath;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        startForegroundService(new Intent(this, ServerService.class));

        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    v.postDelayed(() -> v.loadUrl(target), 700);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePath != null) filePath.onReceiveValue(null);
                filePath = cb;
                try {
                    startActivityForResult(params.createIntent(), 7);
                } catch (Exception e) {
                    filePath = null;
                    return false;
                }
                return true;
            }
        });
        setContentView(web);
        handle(getIntent());
    }

    void handle(Intent i) {
        String t = i.getStringExtra(Intent.EXTRA_TEXT);
        target = URL;
        if (Intent.ACTION_SEND.equals(i.getAction()) && t != null) target = URL + "?share=" + Uri.encode(t);
        web.loadUrl(target);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handle(i);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 7 && filePath != null) {
            filePath.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            filePath = null;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }
}
