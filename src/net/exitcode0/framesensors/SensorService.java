package net.exitcode0.framesensors;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service. Subscribes to the hardware sensors, samples a few system values,
 * and publishes one JSON state message to MQTT every {@code interval} seconds, plus an
 * early publish when the light level moves a lot. Publishes Home Assistant MQTT discovery
 * for every value on each (re)connect.
 */
public class SensorService extends Service implements SensorEventListener {
    private static final String TAG = ConfigReceiver.TAG;

    private Config cfg;
    private SensorManager sm;
    private ScheduledExecutorService exec;
    private ScheduledFuture<?> timer;
    private MqttClient mqtt;

    // Latest sensor readings. Written on the sensor thread, read on the executor.
    private volatile float lux = Float.NaN;
    private volatile float tilt = Float.NaN;       // degrees from vertical
    private volatile long lastLuxPublishMs = 0;
    private volatile float lastPublishedLux = Float.NaN;
    private volatile boolean screenOn = true;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            screenOn = Intent.ACTION_SCREEN_ON.equals(i.getAction());
            schedulePublish(0);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, new Notification.Builder(this)
            .setContentTitle("Frame Sensors").setContentText("Publishing to MQTT")
            .setSmallIcon(android.R.drawable.ic_menu_info_details).build());

        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        register(Sensor.TYPE_LIGHT);
        register(Sensor.TYPE_ACCELEROMETER);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        screenOn = pm.isInteractive();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, f);
    }

    private void register(int type) {
        Sensor s = sm.getDefaultSensor(type);
        if (s == null) { Log.w(TAG, "no sensor of type " + type); return; }
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL);
        Log.i(TAG, "listening to " + s.getName());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        cfg = Config.load(this);
        Log.i(TAG, "start: " + cfg.describe());
        if (exec != null) exec.shutdownNow();
        exec = Executors.newSingleThreadScheduledExecutor();
        if (!cfg.isComplete()) {
            Config.setStatus(this, "mqtt", "not configured");
            return START_STICKY;
        }
        exec.execute(new Runnable() { @Override public void run() { connect(); } });
        timer = exec.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { publish(); }
        }, cfg.interval, cfg.interval, TimeUnit.SECONDS);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        sm.unregisterListener(this);
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        if (exec != null) exec.shutdownNow();
        try {
            if (mqtt != null && mqtt.isConnected()) {
                mqtt.publish(cfg.baseTopic() + "/availability", "offline".getBytes(), 1, true);
                mqtt.disconnect();
            }
            if (mqtt != null) mqtt.close();
        } catch (Exception e) { Log.w(TAG, "disconnect: " + e); }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- sensors

    @Override
    public void onSensorChanged(SensorEvent e) {
        switch (e.sensor.getType()) {
            case Sensor.TYPE_LIGHT:
                lux = e.values[0];
                // Publish early if the level moved by more than 25% (or from/to dark), at most every 5 s.
                long now = SystemClock.elapsedRealtime();
                float ref = lastPublishedLux;
                boolean big = Float.isNaN(ref) || Math.abs(lux - ref) > Math.max(2f, ref * 0.25f);
                if (big && now - lastLuxPublishMs > 5000 && cfg != null && cfg.isComplete()) {
                    lastLuxPublishMs = now;
                    schedulePublish(0);
                }
                break;
            case Sensor.TYPE_ACCELEROMETER:
                float x = e.values[0], y = e.values[1], z = e.values[2];
                double g = Math.sqrt(x * x + y * y + z * z);
                if (g > 0.5) tilt = (float) Math.toDegrees(Math.acos(Math.min(1.0, Math.abs(z) / g)));
                break;
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void schedulePublish(long delayMs) {
        if (exec == null || exec.isShutdown()) return;
        exec.schedule(new Runnable() { @Override public void run() { publish(); } }, delayMs, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ mqtt

    private void connect() {
        try {
            String url = "tcp://" + cfg.host + ":" + cfg.port;
            mqtt = new MqttClient(url, "frame-sensors-" + cfg.id, new MemoryPersistence());
            MqttConnectOptions o = new MqttConnectOptions();
            o.setCleanSession(true);
            o.setAutomaticReconnect(true);
            o.setConnectionTimeout(10);
            o.setKeepAliveInterval(60);
            if (!cfg.user.isEmpty()) { o.setUserName(cfg.user); o.setPassword(cfg.pass.toCharArray()); }
            o.setWill(cfg.baseTopic() + "/availability", "offline".getBytes(), 1, true);
            mqtt.setCallback(new MqttCallbackExtended() {
                @Override public void connectComplete(boolean reconnect, String serverURI) {
                    Log.i(TAG, (reconnect ? "re" : "") + "connected to " + serverURI);
                    Config.setStatus(SensorService.this, "mqtt", "connected to " + serverURI);
                    exec.execute(new Runnable() { @Override public void run() { announce(); publish(); } });
                }
                @Override public void connectionLost(Throwable cause) {
                    Log.w(TAG, "connection lost: " + cause);
                    Config.setStatus(SensorService.this, "mqtt", "lost: " + cause);
                }
                @Override public void messageArrived(String topic, MqttMessage message) {}
                @Override public void deliveryComplete(IMqttDeliveryToken token) {}
            });
            Config.setStatus(this, "mqtt", "connecting to " + url);
            mqtt.connect(o);
        } catch (Exception e) {
            Log.w(TAG, "connect failed: " + e);
            Config.setStatus(this, "mqtt", "connect failed: " + e.getMessage());
            // Paho only auto-reconnects after a first successful connect, so retry ourselves.
            if (exec != null && !exec.isShutdown())
                exec.schedule(new Runnable() { @Override public void run() { connect(); } }, 30, TimeUnit.SECONDS);
        }
    }

    private void pub(String topic, String payload, boolean retain) throws Exception {
        mqtt.publish(topic, payload.getBytes("UTF-8"), 0, retain);
    }

    /** Home Assistant MQTT discovery, one retained config per value, all under one device. */
    private void announce() {
        try {
            String dev = "\"device\":{\"identifiers\":[\"frame-sensors-" + cfg.id + "\"],\"name\":\"" + esc(cfg.name)
                + "\",\"manufacturer\":\"YHK\",\"model\":\"PF1007L photo frame\",\"sw_version\":\"frame-sensors 1.0\"}";
            String common = ",\"state_topic\":\"" + cfg.baseTopic() + "/state\",\"availability_topic\":\"" + cfg.baseTopic()
                + "/availability\",\"expire_after\":" + (cfg.interval * 4) + "," + dev;
            sensor("illuminance", "Illuminance", "lx", "illuminance", "measurement", common);
            sensor("soc_temperature", "SoC temperature", "°C", "temperature", "measurement", common);
            sensor("memory_available", "Memory available", "MB", "data_size", "measurement", common);
            sensor("wifi_rssi", "Wi-Fi signal", "dBm", "signal_strength", "measurement", common);
            sensor("screen_brightness", "Screen brightness", null, null, "measurement", common);
            sensor("tilt", "Tilt", "°", null, "measurement", common);
            sensor("load_1m", "Load (1 min)", null, null, "measurement", common);
            sensor("uptime", "Uptime", "s", "duration", "total_increasing", common);
            pub(cfg.discoveryPrefix + "/binary_sensor/frame_sensors_" + cfg.id + "/screen_on/config",
                "{\"name\":\"Screen on\",\"unique_id\":\"frame_sensors_" + cfg.id + "_screen_on\",\"value_template\":\"{{ value_json.screen_on }}\","
                + "\"payload_on\":\"on\",\"payload_off\":\"off\"" + common + "}", true);
            pub(cfg.baseTopic() + "/availability", "online", true);
        } catch (Exception e) {
            Log.w(TAG, "announce failed: " + e);
        }
    }

    private void sensor(String key, String name, String unit, String devClass, String stateClass, String common) throws Exception {
        StringBuilder b = new StringBuilder("{\"name\":\"").append(name).append("\",\"unique_id\":\"frame_sensors_")
            .append(cfg.id).append('_').append(key).append("\",\"value_template\":\"{{ value_json.").append(key).append(" }}\"");
        if (unit != null) b.append(",\"unit_of_measurement\":\"").append(unit).append('"');
        if (devClass != null) b.append(",\"device_class\":\"").append(devClass).append('"');
        if (stateClass != null) b.append(",\"state_class\":\"").append(stateClass).append('"');
        if (key.equals("uptime") || key.equals("load_1m") || key.equals("tilt") || key.equals("soc_temperature"))
            b.append(",\"entity_category\":\"diagnostic\"");
        b.append(common).append('}');
        pub(cfg.discoveryPrefix + "/sensor/frame_sensors_" + cfg.id + "/" + key + "/config", b.toString(), true);
    }

    private void publish() {
        if (mqtt == null || !mqtt.isConnected()) return;
        try {
            StringBuilder j = new StringBuilder("{");
            num(j, "illuminance", lux, 1);
            num(j, "soc_temperature", readSocTemp(), 1);
            num(j, "memory_available", readMemAvailableMb(), 0);
            num(j, "wifi_rssi", readRssi(), 0);
            num(j, "screen_brightness", Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1), 0);
            num(j, "tilt", tilt, 0);
            num(j, "load_1m", readLoad(), 2);
            num(j, "uptime", SystemClock.elapsedRealtime() / 1000f, 0);
            j.append("\"screen_on\":\"").append(screenOn ? "on" : "off").append("\"}");
            String payload = j.toString();
            pub(cfg.baseTopic() + "/state", payload, false);
            lastPublishedLux = lux;
            Config.setStatus(this, "last", java.text.DateFormat.getTimeInstance().format(new java.util.Date()));
            Config.setStatus(this, "readings", payload);
        } catch (Exception e) {
            Log.w(TAG, "publish failed: " + e);
            Config.setStatus(this, "mqtt", "publish failed: " + e.getMessage());
        }
    }

    private static void num(StringBuilder j, String key, float v, int decimals) {
        if (Float.isNaN(v)) return;
        j.append('"').append(key).append("\":").append(String.format(Locale.US, "%." + decimals + "f", v)).append(',');
    }

    // --------------------------------------------------------------- readers

    private float readSocTemp() {
        String s = readFile("/sys/class/thermal/thermal_zone0/temp");
        if (s == null) return Float.NaN;
        try {
            float t = Float.parseFloat(s.trim());
            return t > 1000 ? t / 1000f : t;   // some kernels report millidegrees
        } catch (NumberFormatException e) { return Float.NaN; }
    }

    private float readMemAvailableMb() {
        // Old kernels (this one is 3.4) have no MemAvailable line; fall back to free + buffers + cached.
        float avail = Float.NaN, free = 0, buffers = 0, cached = 0;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                float kb = line.indexOf(':') > 0 ? Float.parseFloat(line.substring(line.indexOf(':') + 1).replaceAll("[^0-9]", "")) : 0;
                if (line.startsWith("MemAvailable:")) avail = kb;
                else if (line.startsWith("MemFree:")) free = kb;
                else if (line.startsWith("Buffers:")) buffers = kb;
                else if (line.startsWith("Cached:")) cached = kb;
            }
        } catch (Exception ignored) { return Float.NaN; }
        return (Float.isNaN(avail) ? free + buffers + cached : avail) / 1024f;
    }

    private float readLoad() {
        String s = readFile("/proc/loadavg");
        if (s == null) return Float.NaN;
        try { return Float.parseFloat(s.trim().split("\\s+")[0]); } catch (Exception e) { return Float.NaN; }
    }

    private float readRssi() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo i = wm.getConnectionInfo();
            return i == null ? Float.NaN : i.getRssi();
        } catch (Exception e) { return Float.NaN; }
    }

    private static String readFile(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) { return r.readLine(); }
        catch (Exception e) { return null; }
    }

    private static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
