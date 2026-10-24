package com.life.fingerprint;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import androidx.appcompat.widget.AppCompatButton;

final class OrientationButton extends AppCompatButton {
    private final Activity activity;
    private final SharedPreferences preferences;

    OrientationButton(Activity activity) {
        super(activity);
        this.activity = activity;
        preferences = activity.getSharedPreferences("display", Activity.MODE_PRIVATE);
        setAllCaps(false);
        setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density));
        int saved = preferences.getInt("orientation", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (saved == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                || saved == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            activity.setRequestedOrientation(saved);
        }
        updateLabel();
        setOnClickListener(view -> {
            int requested = isLandscape() ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            preferences.edit().putInt("orientation", requested).apply();
            activity.setRequestedOrientation(requested);
        });
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void updateLabel() {
        setText(isLandscape() ? "切换为竖屏" : "切换为横屏");
    }

    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        updateLabel();
    }
}
