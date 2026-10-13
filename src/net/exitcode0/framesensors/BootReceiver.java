package net.exitcode0.framesensors;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Log.i(ConfigReceiver.TAG, "boot completed, starting service");
        // This firmware's BackgroundManagerService refuses to start a service for a package
        // until one of its activities has started, so go through MainActivity, which finishes itself.
        Intent i = new Intent(ctx, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
}
