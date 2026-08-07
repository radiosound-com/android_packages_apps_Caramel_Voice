package com.radiosound.caramelvoice;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
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
            String engine = Settings.Secure.getString(
                    getContentResolver(), "tts_default_synth");
            if (engine != null) {
                intent.setPackage(engine);
            }
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException exception) {
                Toast.makeText(this, R.string.tts_settings_unavailable, Toast.LENGTH_LONG).show();
            }
        });

        Button chooseTts = findViewById(R.id.choose_tts);
        chooseTts.setOnClickListener(view -> {
            try {
                startActivity(new Intent("android.settings.TTS_SETTINGS"));
            } catch (ActivityNotFoundException exception) {
                Toast.makeText(this, R.string.tts_settings_unavailable, Toast.LENGTH_LONG).show();
            }
        });
    }
}
