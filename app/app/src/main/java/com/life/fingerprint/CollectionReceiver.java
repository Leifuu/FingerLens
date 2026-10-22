package com.life.fingerprint;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 唯一的 ADB 自动化入口；只接受本机 adb shell UID 发出的显式广播。 */
public final class CollectionReceiver extends BroadcastReceiver {
    public static final String ACTION_COLLECT = "com.life.fingerprint.action.COLLECT";
    public static final String ACTION_CANCEL = "com.life.fingerprint.action.CANCEL";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String requestId = intent.getStringExtra("requestId");
        if (requestId == null || !requestId.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) { setResultCode(3); return; }
        CollectionController controller = CollectionController.get(context);
        if (ACTION_COLLECT.equals(intent.getAction())) setResultCode(controller.start(requestId) ? -1 : 2);
        else if (ACTION_CANCEL.equals(intent.getAction())) { controller.cancel(requestId); setResultCode(-1); }
    }
}
