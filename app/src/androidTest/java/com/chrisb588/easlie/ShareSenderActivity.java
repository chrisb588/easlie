package com.chrisb588.easlie;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;

/** A framework-only activity in the test APK, emulating an external browser/gallery sender. */
public class ShareSenderActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // The untyped overload is required on Android versions before API 33.
        @SuppressWarnings("deprecation")
        Intent share = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? getIntent().getParcelableExtra("share", Intent.class)
            : getIntent().getParcelableExtra("share");
        if (share == null) throw new IllegalArgumentException("Missing share fixture");
        startActivity(share);
        finish();
    }
}
