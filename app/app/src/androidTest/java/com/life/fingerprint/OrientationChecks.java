package com.life.fingerprint;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class OrientationChecks {
    @Test public void switchRetainsActivityAndRestoresChoice() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("display", Context.MODE_PRIVATE);
        boolean hadChoice = preferences.contains("orientation");
        int oldChoice = preferences.getInt("orientation", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        preferences.edit().putInt("orientation", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT).commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitOrientation(scenario, Configuration.ORIENTATION_PORTRAIT);
            AtomicReference<MainActivity> original = new AtomicReference<>();
            scenario.onActivity(original::set);
            CollectionController controller = CollectionController.get(context);
            onView(withText("切换为横屏")).perform(click());
            awaitOrientation(scenario, Configuration.ORIENTATION_LANDSCAPE);
            scenario.onActivity(activity -> {
                assertSame("切屏应保留页面和进行中的操作", original.get(), activity);
                assertSame(controller, CollectionController.get(activity));
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, activity.getRequestedOrientation());
            });
            scenario.recreate();
            awaitOrientation(scenario, Configuration.ORIENTATION_LANDSCAPE);
            scenario.onActivity(activity -> {
                assertNotSame(original.get(), activity);
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, activity.getRequestedOrientation());
            });
            onView(withText("切换为竖屏")).perform(click());
            awaitOrientation(scenario, Configuration.ORIENTATION_PORTRAIT);
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    preferences.getInt("orientation", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
        } finally {
            SharedPreferences.Editor restore = preferences.edit();
            if (hadChoice) restore.putInt("orientation", oldChoice);
            else restore.remove("orientation");
            restore.commit();
        }
    }

    private void awaitOrientation(ActivityScenario<MainActivity> scenario, int expected) {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        AtomicReference<Integer> actual = new AtomicReference<>();
        do {
            scenario.onActivity(activity -> actual.set(activity.getResources().getConfiguration().orientation));
            if (actual.get() == expected) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertEquals("屏幕方向未在10秒内切换", Integer.valueOf(expected), actual.get());
    }
}
