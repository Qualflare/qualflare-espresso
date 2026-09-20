package com.qualflare.espresso.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One screen with one piece of text, built in code so the fixture needs no
 * resources: a spike about output plumbing should not also be a test of Android's
 * resource pipeline.
 */
public class MainActivity extends Activity {
    public static final String GREETING = "Qualflare fixture";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText(GREETING);
        text.setContentDescription(GREETING);
        LinearLayout root = new LinearLayout(this);
        root.addView(text);
        setContentView(root);
    }
}
