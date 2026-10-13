package net.exitcode0.framesensors;

import android.content.Context;
import android.content.SharedPreferences;

/** All runtime configuration. Nothing here is compiled in; see ConfigReceiver for how it is set. */
public final class Config {
    static final String PREFS = "config";
    static final String STATUS_PREFS = "status";

    public final String host;
    public final int port;
    public final String user;
    public final String pass;
    public final int interval;      // seconds between full publishes
    public final String name;       // HA device name
    public final String id;         // HA object id / topic segment
    public final String discoveryPrefix;

    private Config(SharedPreferences p) {
        host = p.getString("host", "");
        port = p.getInt("port", 1883);
        user = p.getString("user", "");
        pass = p.getString("pass", "");
        interval = Math.max(5, p.getInt("interval", 30));
        name = p.getString("name", "Photo Frame");
        id = p.getString("id", "photo_frame");
        discoveryPrefix = p.getString("discovery_prefix", "homeassistant");
    }

    public static Config load(Context c) {
        return new Config(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    public boolean isComplete() {
        return host != null && host.length() > 0;
    }

    public String baseTopic() {
        return "frame-sensors/" + id;
    }

    /** Human-readable summary with the password masked. */
    public String describe() {
        return "host=" + host + ":" + port
            + " user=" + (user.isEmpty() ? "(none)" : user)
            + " pass=" + (pass.isEmpty() ? "(none)" : "********")
            + " interval=" + interval + "s"
            + " name=\"" + name + "\" id=" + id
            + " discovery_prefix=" + discoveryPrefix;
    }

    static void setStatus(Context c, String key, String value) {
        c.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE).edit().putString(key, value).apply();
    }

    static String getStatus(Context c, String key) {
        return c.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE).getString(key, "");
    }
}
