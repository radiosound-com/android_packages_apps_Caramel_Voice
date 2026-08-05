package com.radiosound.caramelvoice;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

public final class SettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings);

        Button configureTts = findViewById(R.id.configure_tts);
        configureTts.setOnClickListener(view -> {
            Intent intent = new Intent("android.speech.tts.engine.CONFIGURE_ENGINE");
            intent.setPackage("com.reecedunn.espeak");
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException exception) {
                Toast.makeText(this, R.string.tts_settings_unavailable, Toast.LENGTH_LONG).show();
            }
        });
    }
}
