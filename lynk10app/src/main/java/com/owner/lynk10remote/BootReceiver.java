package com.owner.lynk10remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) return;
        AppSettings settings = new AppSettings(context);
        if (!settings.guardEnabled()) return;

        Intent serviceIntent = new Intent(context, RemoteGuardService.class)
                .setAction(RemoteGuardService.ACTION_BOOT_RECONNECT);
        context.startForegroundService(serviceIntent);
    }
}
