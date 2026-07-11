/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.telegram.messenger.vpn.VpnController;

public class AppStartReceiver extends BroadcastReceiver {

    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            AndroidUtilities.runOnUIThread(() -> {
                SharedConfig.loadConfig();
                if (SharedConfig.passcodeHash.length() > 0) {
                    SharedConfig.appLocked = true;
                    SharedConfig.saveConfig();
                }
                ApplicationLoader.startPushService();
                // Bring the embedded VPN (and its foreground service + push connection) back up if it
                // was on, so the tunnel and background notifications survive a reboot without the user
                // opening the app. load() no-ops when the VPN wasn't enabled. specialUse foreground
                // services are allowed to start from BOOT_COMPLETED on Android 14+.
                try {
                    ApplicationLoader.postInitApplication();
                    VpnController.getInstance().load();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            });
        }
    }
}
