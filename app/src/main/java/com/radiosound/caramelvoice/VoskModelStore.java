/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import android.content.Context;

import org.vosk.android.StorageService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs a product-selected Vosk model without writing outside app-private storage. */
final class VoskModelStore {
    private static final String READY_FILE = ".caramel-model-ready";

    private VoskModelStore() {}

    static String sync(Context context, VoskModelProfile profile) throws IOException {
        if (profile.source == VoskModelProfile.Source.APK_ASSET) {
            return StorageService.sync(context, profile.modelDirectory, profile.modelDirectory);
        }
        return extractProductArchive(context, profile);
    }

    private static String extractProductArchive(Context context, VoskModelProfile profile)
            throws IOException {
        File base = new File(context.getNoBackupFilesDir(), "vosk-models");
        if (!base.exists() && !base.mkdirs()) {
            throw new IOException("Unable to create model directory: " + base);
        }

        File target = new File(base, profile.modelDirectory);
        File ready = new File(target, READY_FILE);
        if (ready.isFile()) return target.getAbsolutePath();

        File temporary = new File(base, profile.modelDirectory + ".partial");
        deleteRecursively(temporary);
        if (!temporary.mkdirs()) {
            throw new IOException("Unable to create temporary model directory: " + temporary);
        }

        File archive = new File(profile.productArchive);
        if (!archive.isFile()) {
            deleteRecursively(temporary);
            throw new IOException("Product Vosk archive is missing: " + archive);
        }

        String root = profile.modelDirectory + "/";
        boolean modelRootSeen = false;
        try (InputStream input = new FileInputStream(archive);
                ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!name.startsWith(root) || name.contains("../") || name.endsWith("/..")) {
                    throw new IOException("Unexpected Vosk archive entry: " + name);
                }
                modelRootSeen = true;
                String relativeName = name.substring(root.length());
                // ZIPs conventionally contain an explicit top-level directory
                // entry. It maps to the temporary directory itself and must
                // not be rejected by the child-path containment check.
                if (relativeName.isEmpty()) continue;
                File output = new File(temporary, relativeName);
                String temporaryPath = temporary.getCanonicalPath() + File.separator;
                String outputPath = output.getCanonicalPath();
                if (!outputPath.startsWith(temporaryPath)) {
                    throw new IOException("Archive entry escapes model directory: " + name);
                }
                if (entry.isDirectory()) {
                    if (!output.isDirectory() && !output.mkdirs()) {
                        throw new IOException("Unable to create model directory: " + output);
                    }
                    continue;
                }
                File parent = output.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Unable to create model parent: " + parent);
                }
                try (OutputStream file = new FileOutputStream(output)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = zip.read(buffer)) != -1) file.write(buffer, 0, count);
                }
            }
        } catch (IOException exception) {
            deleteRecursively(temporary);
            throw exception;
        }

        if (!modelRootSeen || !new File(temporary, "am/final.mdl").isFile()) {
            deleteRecursively(temporary);
            throw new IOException("Product Vosk archive is incomplete: " + archive);
        }
        writeReadyFile(temporary, profile.modelDirectory);
        if (target.exists()) deleteRecursively(target);
        if (!temporary.renameTo(target)) {
            deleteRecursively(temporary);
            throw new IOException("Unable to publish Vosk model: " + target);
        }
        return target.getAbsolutePath();
    }

    private static void writeReadyFile(File directory, String modelDirectory) throws IOException {
        File ready = new File(directory, READY_FILE);
        try (FileOutputStream output = new FileOutputStream(ready)) {
            output.write(modelDirectory.getBytes(StandardCharsets.UTF_8));
            output.write('\n');
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        if (!file.delete()) throw new IOException("Unable to delete: " + file);
    }
}
