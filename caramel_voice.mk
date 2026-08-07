# Copyright 2026 Radio Sound, Inc.
# Licensed under the Apache License, Version 2.0.
#
# Offline speech stack for the Caramel Vanilla AAOS product.

# The compact profile is the safe default for 4 GB Raspberry Pi 5 boards. A
# named 16 GB product can set CARAMEL_VOICE_ASR_MODEL=lgraph before inheriting
# this file; the larger archive is then copied to /product and loaded lazily.
CARAMEL_VOICE_ASR_MODEL ?= small
ifeq ($(CARAMEL_VOICE_ASR_MODEL),small)
CARAMEL_VOICE_RECOGNITION_CONFIG := recognition-small.properties
else ifeq ($(CARAMEL_VOICE_ASR_MODEL),lgraph)
CARAMEL_VOICE_RECOGNITION_CONFIG := recognition-lgraph.properties
else
$(error Unsupported CARAMEL_VOICE_ASR_MODEL '$(CARAMEL_VOICE_ASR_MODEL)'; use small or lgraph)
endif

PRODUCT_PACKAGES += \
    CaramelVoiceAssistant \
    CaramelEspeakTts

PRODUCT_COPY_FILES += \
    vendor/radiosound/voiceassistant/config/$(CARAMEL_VOICE_RECOGNITION_CONFIG):$(TARGET_COPY_OUT_PRODUCT)/etc/caramel_voice/recognition.properties

ifeq ($(CARAMEL_VOICE_ASR_MODEL),lgraph)
PRODUCT_COPY_FILES += \
    vendor/radiosound/voiceassistant/app/model/vosk-model-en-us-0.22-lgraph.zip:$(TARGET_COPY_OUT_PRODUCT)/etc/caramel_voice/models/vosk-model-en-us-0.22-lgraph.zip
endif
