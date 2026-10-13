package net.exitcode0.framesensors;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.widget.TextView;

/** Starts the service. Shows a status screen only when launched with the "show" extra. Config is done over adb. */
public class MainActivity extends Activity {
    private TextView text;
    private final Handler handler = new Handler();
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 2000); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        text = new TextView(this);
        text.setPadding(40, 40, 40, 40);
        text.setTextSize(18);
        setContentView(text);
        startService(new Intent(this, SensorService.class));
        // Default is start-the-service-and-go-away, so launching the package from Fully's
        // "app to run on start" or from adb never covers the kiosk. Pass --ez show true to see status.
        if (!getIntent().getBooleanExtra("show", false)) finish();
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(refresh); }

    private void render() {
        Config c = Config.load(this);
        StringBuilder sb = new StringBuilder("Frame Sensors\n\n");
        sb.append(c.isComplete() ? c.describe() : "Not configured. Send the CONFIGURE broadcast over adb.");
        sb.append("\n\nMQTT: ").append(Config.getStatus(this, "mqtt"));
        sb.append("\nLast publish: ").append(Config.getStatus(this, "last"));
        sb.append("\n\n").append(Config.getStatus(this, "readings"));
        text.setText(sb.toString());
    }
}
