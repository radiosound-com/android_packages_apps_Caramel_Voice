/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/** Product-selected Vosk model metadata. */
final class VoskModelProfile {
    static final String CONFIG_PATH = "/product/etc/caramel_voice/recognition.properties";

    static final String SMALL_MODEL = "vosk-model-small-en-us-0.15";
    static final String LGRAPH_MODEL = "vosk-model-en-us-0.22-lgraph";
    static final String LGRAPH_ARCHIVE =
            "/product/etc/caramel_voice/models/vosk-model-en-us-0.22-lgraph.zip";

    enum Source {
        APK_ASSET,
        PRODUCT_ARCHIVE
    }

    final String modelDirectory;
    final Source source;
    final String productArchive;

    private VoskModelProfile(String modelDirectory, Source source, String productArchive) {
        this.modelDirectory = modelDirectory;
        this.source = source;
        this.productArchive = productArchive;
    }

    static VoskModelProfile compact() {
        return new VoskModelProfile(SMALL_MODEL, Source.APK_ASSET, null);
    }

    static VoskModelProfile largeGraph() {
        return new VoskModelProfile(LGRAPH_MODEL, Source.PRODUCT_ARCHIVE, LGRAPH_ARCHIVE);
    }

    static VoskModelProfile load() {
        Properties properties = new Properties();
        File config = new File(CONFIG_PATH);
        if (!config.isFile()) return compact();
        try (FileInputStream input = new FileInputStream(config)) {
            properties.load(input);
            return fromProperties(properties);
        } catch (IOException exception) {
            return compact();
        }
    }

    static VoskModelProfile fromProperties(Properties properties) {
        String model = properties.getProperty("model", "small").trim();
        if ("lgraph".equals(model)) return largeGraph();
        return compact();
    }
}
