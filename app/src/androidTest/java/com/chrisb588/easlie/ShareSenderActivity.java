package com.chrisb588.easlie;

import android.app.Activity;
import android.content.Intent;
import android.content.ClipData;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import java.util.ArrayList;
import java.util.Arrays;

/** A framework-only activity in the test APK, emulating an external browser/gallery sender. */
public class ShareSenderActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // The untyped overload is required on Android versions before API 33.
        @SuppressWarnings("deprecation")
        Intent share = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? getIntent().getParcelableExtra("share", Intent.class)
            : getIntent().getParcelableExtra("share");
        String mode = getIntent().getStringExtra("mode");
        if (share == null && ("single".equals(mode) || "multiple".equals(mode))) {
            Uri first = Uri.parse("content://com.chrisb588.easlie.test.images/task-first.png");
            Uri second = Uri.parse("content://com.chrisb588.easlie.test.images/task-second.png");
            boolean multiple = "multiple".equals(mode);
            share = new Intent(multiple ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND)
                .setType("image/png")
                .setClassName("com.chrisb588.easlie", "com.chrisb588.easlie.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (multiple) share.putParcelableArrayListExtra(Intent.EXTRA_STREAM,
                new ArrayList<>(Arrays.asList(first, second)));
            else share.putExtra(Intent.EXTRA_STREAM, first);
            ClipData clip = ClipData.newRawUri("images", first);
            if (multiple) clip.addItem(new ClipData.Item(second));
            share.setClipData(clip);
        }
        if (share == null) throw new IllegalArgumentException("Missing share fixture");
        startActivity(share);
        finish();
    }
}
