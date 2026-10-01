package com.chrisb588.easlie;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** A framework-only activity in the test APK, emulating an external browser/gallery sender. */
public class ShareSenderActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent share = getIntent().getParcelableExtra("share");
        if (share == null) throw new IllegalArgumentException("Missing share fixture");
        startActivity(share);
        finish();
    }
}
