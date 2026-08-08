/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.File;
import java.io.IOException;

/** Paths for the product-installed INT8 streaming Zipformer model. */
final class SherpaModelPaths {
    static final String MODEL_NAME = "sherpa-onnx-streaming-zipformer-en-2023-06-21";
    static final String MODEL_DIRECTORY = "/product/etc/caramel_voice/models/" + MODEL_NAME;

    private final String modelDirectory;
    final String encoder;
    final String decoder;
    final String joiner;
    final String tokens;
    final String bpeVocab;

    SherpaModelPaths(Context context) {
        modelDirectory = selectModelDirectory(context);
        encoder = path("encoder-epoch-99-avg-1.int8.onnx");
        decoder = path("decoder-epoch-99-avg-1.onnx");
        joiner = path("joiner-epoch-99-avg-1.int8.onnx");
        tokens = path("tokens.txt");
        bpeVocab = path("bpe.vocab");
    }

    void validate() throws IOException {
        requireFile(encoder);
        requireFile(decoder);
        requireFile(joiner);
        requireFile(tokens);
        requireFile(bpeVocab);
    }

    private String path(String name) {
        return modelDirectory + "/" + name;
    }

    private static String selectModelDirectory(Context context) {
        if (isDebuggable(context)) {
            File external = context.getExternalFilesDir(null);
            if (external != null) {
                File override = new File(new File(external, "models"), MODEL_NAME);
                if (containsModel(override)) return override.getAbsolutePath();
            }
        }
        return MODEL_DIRECTORY;
    }

    private static boolean isDebuggable(Context context) {
        return context != null
                && (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private static boolean containsModel(File directory) {
        return new File(directory, "encoder-epoch-99-avg-1.int8.onnx").isFile()
                && new File(directory, "decoder-epoch-99-avg-1.onnx").isFile()
                && new File(directory, "joiner-epoch-99-avg-1.int8.onnx").isFile()
                && new File(directory, "tokens.txt").isFile()
                && new File(directory, "bpe.vocab").isFile();
    }

    private static void requireFile(String path) throws IOException {
        if (!new File(path).isFile()) throw new IOException("Zipformer model file is missing: " + path);
    }
}
