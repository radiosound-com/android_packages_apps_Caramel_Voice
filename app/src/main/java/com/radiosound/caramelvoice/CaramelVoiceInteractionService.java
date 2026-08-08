package com.radiosound.caramelvoice;

import android.service.voice.VoiceInteractionService;
import android.util.Log;

import java.util.Collections;
import java.util.Set;

public final class CaramelVoiceInteractionService extends VoiceInteractionService {
    private static final String TAG = "CaramelVoice";

    @Override
    public void onReady() {
        super.onReady();
        RecognitionContextRepository.preload(this);
        if (RecognitionBackendProfile.load().engine
                == RecognitionBackendProfile.Engine.ZIPFORMER) {
            SherpaModelRepository.preload(this);
        } else {
            VoskModelRepository.preload(this);
        }
        Log.i(TAG, "Caramel Voice is ready");
    }

    @Override
    public Set<String> onGetSupportedVoiceActions(Set<String> voiceActions) {
        return Collections.emptySet();
    }
}
