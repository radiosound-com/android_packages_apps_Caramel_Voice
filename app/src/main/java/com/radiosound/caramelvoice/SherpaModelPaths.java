/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import java.io.File;
import java.io.IOException;

/** Paths for the product-installed INT8 streaming Zipformer model. */
final class SherpaModelPaths {
    static final String MODEL_NAME = "sherpa-onnx-streaming-zipformer-en-2023-06-21";
    static final String MODEL_DIRECTORY = "/product/etc/caramel_voice/models/" + MODEL_NAME;

    final String encoder = path("encoder-epoch-99-avg-1.int8.onnx");
    final String decoder = path("decoder-epoch-99-avg-1.onnx");
    final String joiner = path("joiner-epoch-99-avg-1.int8.onnx");
    final String tokens = path("tokens.txt");
    final String bpeVocab = path("bpe.vocab");

    void validate() throws IOException {
        requireFile(encoder);
        requireFile(decoder);
        requireFile(joiner);
        requireFile(tokens);
        requireFile(bpeVocab);
    }

    private static String path(String name) {
        return MODEL_DIRECTORY + "/" + name;
    }

    private static void requireFile(String path) throws IOException {
        if (!new File(path).isFile()) throw new IOException("Zipformer model file is missing: " + path);
    }
}
