
/*
 * Copyright 2026
 * Licensed under the Apache License, Version 2.0.
 */

package ru.woesss.j2me.installer;

import android.app.Application;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;

import io.reactivex.Completable;
import io.reactivex.Single;
import ru.playsoftware.j2meloader.appsdb.AppRepository;

public final class BundledGamesInstaller {

    private static final String TAG = "BundledGamesInstaller";
    private static final String ASSET_DIRECTORY = "games";

    private BundledGamesInstaller() {
    }

    /**
     * Imports all bundled JAR games.
     *
     * This operation must run on a background thread.
     */
    public static Completable install(
            Application application,
            AppRepository repository) {

        return Completable.fromAction(
                () -> installGames(application, repository)
        );
    }

    private static void installGames(
            Application application,
            AppRepository repository) throws Exception {

        String[] games = application.getAssets().list(ASSET_DIRECTORY);

        if (games == null || games.length == 0) {
            Log.i(TAG, "No bundled games found.");
            return;
        }

        Arrays.sort(games, String.CASE_INSENSITIVE_ORDER);

        File stagingDirectory = new File(
                application.getCacheDir(),
                "bundled-games"
        );

        if (!stagingDirectory.exists()
                && !stagingDirectory.mkdirs()) {
            throw new IOException("Cannot create staging directory");
        }

        int installed = 0;
        int skipped = 0;
        int failed = 0;

        try {
            for (String game : games) {

                if (!game.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    continue;
                }

                File stagedJar = new File(stagingDirectory, game);
                AppInstaller installer = null;

                try {
                    copyAsset(
                            application,
                            ASSET_DIRECTORY + "/" + game,
                            stagedJar
                    );

                    installer = new AppInstaller(
                            stagedJar.getAbsolutePath(),
                            Uri.fromFile(stagedJar),
                            application,
                            repository
                    );

                    int status = Single.<Integer>create(
                            installer::loadInfo
                    ).blockingGet();

                    if (status == AppInstaller.STATUS_NEW
                            || status == AppInstaller.STATUS_NEWEST) {

                        int result = Single.<Integer>create(
                                installer::install
                        ).blockingGet();

                        if (result == AppInstaller.STATUS_SUCCESS) {
                            installed++;
                            Log.i(TAG, "Installed: " + game);
                        } else {
                            failed++;
                            Log.w(TAG, "Installation unsuccessful: " + game
                                    + ", status=" + result);
                        }

                    } else {
                        skipped++;
                        Log.i(TAG, "Already installed or not newer: " + game
                                + ", status=" + status);
                    }

                } catch (Exception e) {
                    failed++;
                    Log.e(TAG, "Failed to import: " + game, e);

                } finally {
                    if (installer != null) {
                        installer.clearCache();
                        installer.deleteTemp();
                    }

                    if (stagedJar.exists() && !stagedJar.delete()) {
                        Log.w(TAG, "Cannot delete temporary JAR: "
                                + stagedJar);
                    }
                }
            }
        } finally {
            deleteDirectory(stagingDirectory);
        }

        Log.i(TAG, "Import finished. Installed=" + installed
                + ", skipped=" + skipped
                + ", failed=" + failed);
    }

    private static void copyAsset(
            Application application,
            String assetPath,
            File destination) throws IOException {

        try (
                InputStream input =
                        application.getAssets().open(assetPath);

                FileOutputStream output =
                        new FileOutputStream(destination)
        ) {
            byte[] buffer = new byte[8192];
            int length;

            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }

            output.flush();
            output.getFD().sync();
        }
    }

    private static void deleteDirectory(File directory) {
        if (directory == null || !directory.exists()) {
            return;
        }

        File[] files = directory.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else if (!file.delete()) {
                    Log.w(TAG, "Cannot delete: " + file);
                }
            }
        }

        if (!directory.delete()) {
            Log.w(TAG, "Cannot delete directory: " + directory);
        }
    }
}
