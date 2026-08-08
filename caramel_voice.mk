# Copyright 2026 Radio Sound, Inc.
# Licensed under the Apache License, Version 2.0.
#
# Offline speech stack for the Caramel Vanilla AAOS product.

# Products select a reproducible recognition profile before inheriting this
# file. Zipformer is the high-quality streaming profile for 4 GB and larger
# Raspberry Pi 5 boards; Vosk remains available as a compact fallback.
CARAMEL_VOICE_ASR_MODEL ?= small
ifeq ($(CARAMEL_VOICE_ASR_MODEL),small)
CARAMEL_VOICE_RECOGNITION_CONFIG := recognition-small.properties
else ifeq ($(CARAMEL_VOICE_ASR_MODEL),lgraph)
CARAMEL_VOICE_RECOGNITION_CONFIG := recognition-lgraph.properties
else ifeq ($(CARAMEL_VOICE_ASR_MODEL),zipformer-int8)
CARAMEL_VOICE_RECOGNITION_CONFIG := recognition-zipformer-int8.properties
else ifeq ($(CARAMEL_VOICE_ASR_MODEL),zipformer-int8-highmem)
CARAMEL_VOICE_RECOGNITION_CONFIG := recognition-zipformer-int8-highmem.properties
else
$(error Unsupported CARAMEL_VOICE_ASR_MODEL '$(CARAMEL_VOICE_ASR_MODEL)'; use small, lgraph, zipformer-int8, or zipformer-int8-highmem)
endif

CARAMEL_VOICE_TTS ?= espeak
ifeq ($(CARAMEL_VOICE_TTS),espeak)
CARAMEL_VOICE_TTS_CONFIG := tts-espeak.properties
else ifeq ($(CARAMEL_VOICE_TTS),kokoro)
CARAMEL_VOICE_TTS_CONFIG := tts-kokoro.properties
else
$(error Unsupported CARAMEL_VOICE_TTS '$(CARAMEL_VOICE_TTS)'; use espeak or kokoro)
endif

PRODUCT_PACKAGES += \
    CaramelVoiceAssistant \
    CaramelEspeakTts

ifeq ($(CARAMEL_VOICE_TTS),kokoro)
PRODUCT_PACKAGES += \
    CaramelKokoroTts
endif

PRODUCT_COPY_FILES += \
    vendor/radiosound/voiceassistant/config/$(CARAMEL_VOICE_RECOGNITION_CONFIG):$(TARGET_COPY_OUT_PRODUCT)/etc/caramel_voice/recognition.properties

PRODUCT_COPY_FILES += \
    vendor/radiosound/voiceassistant/config/$(CARAMEL_VOICE_TTS_CONFIG):$(TARGET_COPY_OUT_PRODUCT)/etc/caramel_voice/tts.properties

# Keep human-readable notices and the exact source/binary lockfile beside the
# installed offline models. Soong also records the APK license modules above.
PRODUCT_COPY_FILES += \
    vendor/radiosound/voiceassistant/LICENSES/README.md:$(TARGET_COPY_OUT_PRODUCT)/etc/caramel_voice/licenses/README.md \
    vendor/radiosound/voiceassistant/LICENSES/Apache-2.0.txt:$(TARGET_COPY_OUT_PRODUCT)/etc/caramel_voice/licenses/Apache-2.0.txt \
    vendor/radiosound/voiceassistant/LICENSES/BSD-2-Clause.txt:$(TARGET_COPY_OUT_PRODUCT)/etc/caramel_voice/licenses/BSD-2-Clause.txt \
    vendor/radiosound/voiceassistant/LICENSES/GPL-3.0-only.txt:$(TARGET_COPY_OUT_PRODUCT)/etc/caramel_voice/licenses/GPL-3.0-only.txt \
    vendor/radiosound/voiceassistant/LICENSES/JNA-Apache-2.0.txt:$(TARGET_COPY_OUT_PRODUCT)/etc/caramel_voice/licenses/JNA-Apache-2.0.txt \
    vendor/radiosound/voiceassistant/LICENSES/MIT.txt:$(TARGET_COPY_OUT_PRODUCT)/etc/caramel_voice/licenses/MIT.txt \
    vendor/radiosound/voiceassistant/LICENSES/Unicode-DFS-2015.txt:$(TARGET_COPY_OUT_PRODUCT)/etc/caramel_voice/licenses/Unicode-DFS-2015.txt \
    vendor/radiosound/voiceassistant/LICENSES/Vosk-Apache-2.0.txt:$(TARGET_COPY_OUT_PRODUCT)/etc/caramel_voice/licenses/Vosk-Apache-2.0.txt \
    vendor/radiosound/voiceassistant/provenance/SOURCES.lock:$(TARGET_COPY_OUT_PRODUCT)/etc/caramel_voice/SOURCES.lock

ifeq ($(CARAMEL_VOICE_ASR_MODEL),lgraph)
PRODUCT_COPY_FILES += \
    vendor/radiosound/voiceassistant/app/model/vosk-model-en-us-0.22-lgraph.zip:$(TARGET_COPY_OUT_PRODUCT)/etc/caramel_voice/models/vosk-model-en-us-0.22-lgraph.zip
endif

ifneq ($(filter zipformer-int8 zipformer-int8-highmem,$(CARAMEL_VOICE_ASR_MODEL)),)
CARAMEL_ZIPFORMER_MODEL_DIR := sherpa-onnx-streaming-zipformer-en-2023-06-21
PRODUCT_COPY_FILES += \
    vendor/radiosound/voiceassistant/app/model/$(CARAMEL_ZIPFORMER_MODEL_DIR)/encoder-epoch-99-avg-1.int8.onnx:$(TARGET_COPY_OUT_PRODUCT)/etc/caramel_voice/models/$(CARAMEL_ZIPFORMER_MODEL_DIR)/encoder-epoch-99-avg-1.int8.onnx \
    vendor/radiosound/voiceassistant/app/model/$(CARAMEL_ZIPFORMER_MODEL_DIR)/decoder-epoch-99-avg-1.onnx:$(TARGET_COPY_OUT_PRODUCT)/etc/caramel_voice/models/$(CARAMEL_ZIPFORMER_MODEL_DIR)/decoder-epoch-99-avg-1.onnx \
    vendor/radiosound/voiceassistant/app/model/$(CARAMEL_ZIPFORMER_MODEL_DIR)/joiner-epoch-99-avg-1.int8.onnx:$(TARGET_COPY_OUT_PRODUCT)/etc/caramel_voice/models/$(CARAMEL_ZIPFORMER_MODEL_DIR)/joiner-epoch-99-avg-1.int8.onnx \
    vendor/radiosound/voiceassistant/app/model/$(CARAMEL_ZIPFORMER_MODEL_DIR)/tokens.txt:$(TARGET_COPY_OUT_PRODUCT)/etc/caramel_voice/models/$(CARAMEL_ZIPFORMER_MODEL_DIR)/tokens.txt \
    vendor/radiosound/voiceassistant/app/model/$(CARAMEL_ZIPFORMER_MODEL_DIR)/bpe.vocab:$(TARGET_COPY_OUT_PRODUCT)/etc/caramel_voice/models/$(CARAMEL_ZIPFORMER_MODEL_DIR)/bpe.vocab \
    vendor/radiosound/voiceassistant/app/model/$(CARAMEL_ZIPFORMER_MODEL_DIR)/README.md:$(TARGET_COPY_OUT_PRODUCT)/etc/caramel_voice/models/$(CARAMEL_ZIPFORMER_MODEL_DIR)/README.md
endif
