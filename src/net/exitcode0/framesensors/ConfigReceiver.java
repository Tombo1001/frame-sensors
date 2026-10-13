package net.exitcode0.framesensors;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Configure the app from adb without touching the screen or recompiling:
 *
 *   adb shell am broadcast -a net.exitcode0.framesensors.CONFIGURE \
 *       --es host 192.168.1.10 --ei port 1883 --es user mqtt --es pass secret \
 *       --ei interval 30 --es name "Photo Frame" --es id photo_frame
 *
 * Only the extras given are changed. The service restarts with the new config.
 *
 *   adb shell am broadcast -a net.exitcode0.framesensors.STATUS   # config + last status to logcat
 *   adb shell am broadcast -a net.exitcode0.framesensors.RESTART
 */
public class ConfigReceiver extends BroadcastReceiver {
    static final String TAG = "FrameSensors";
    static final String ACTION_CONFIGURE = "net.exitcode0.framesensors.CONFIGURE";
    static final String ACTION_STATUS = "net.exitcode0.framesensors.STATUS";
    static final String ACTION_RESTART = "net.exitcode0.framesensors.RESTART";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (ACTION_CONFIGURE.equals(action)) {
            SharedPreferences.Editor e = ctx.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE).edit();
            int changed = 0;
            for (String k : new String[]{"host", "user", "pass", "name", "id", "discovery_prefix"}) {
                if (intent.hasExtra(k)) { e.putString(k, intent.getStringExtra(k)); changed++; }
            }
            for (String k : new String[]{"port", "interval"}) {
                if (intent.hasExtra(k)) { e.putInt(k, intent.getIntExtra(k, 0)); changed++; }
            }
            if (intent.getBooleanExtra("clear", false)) { e.clear(); changed++; }
            e.apply();
            Config c = Config.load(ctx);
            Log.i(TAG, "config updated (" + changed + " keys): " + c.describe());
            setResultData("ok: " + c.describe());
            restart(ctx);
        } else if (ACTION_STATUS.equals(action)) {
            Config c = Config.load(ctx);
            String s = c.describe() + " | " + Config.getStatus(ctx, "mqtt") + " | last=" + Config.getStatus(ctx, "last");
            Log.i(TAG, "status: " + s);
            setResultData(s);
        } else if (ACTION_RESTART.equals(action)) {
            restart(ctx);
            setResultData("restarted");
        }
    }

    static void restart(Context ctx) {
        Intent svc = new Intent(ctx, SensorService.class);
        ctx.stopService(svc);
        ctx.startService(svc);
    }
}
