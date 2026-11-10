/*
 * Copyright 2026 mesflit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.woesss.j2me.installer;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.Single;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.appsdb.AppRepository;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.woesss.j2me.jar.Descriptor;

/**
 * Installs many MIDlets in one go, independently of any screen. The sources can be a
 * list of files, a ZIP archive or a folder; archives and folders are scanned first.
 *
 * The work runs on its own thread and survives the dialog and the activity: the
 * dialog and {@link ImportService} only display the {@link State} published here.
 *
 * Games that are already in the library are not installed again: the same or an
 * older version is skipped, a newer version updates the installed game.
 */
public final class BatchImporter {
	private static final String TAG = "BatchImporter";

	public static final int SOURCE_FILES = 0;
	public static final int SOURCE_ZIP = 1;
	public static final int SOURCE_FOLDER = 2;

	public static final int PHASE_IDLE = 0;
	public static final int PHASE_SCANNING = 1;
	public static final int PHASE_INSTALLING = 2;
	public static final int PHASE_FINISHED = 3;

	private static final int MAX_FAILED_LIST_SIZE = 10;
	private static final BatchImporter INSTANCE = new BatchImporter();

	/** Immutable snapshot of the import, safe to read from any thread. */
	public static final class State {
		static final State IDLE = new State(PHASE_IDLE, "", "", 0, 0, 0, 0, 0, 0,
				false, false, false, Collections.emptyList(), null, false);

		public final int phase;
		/** Details of the current file or game, shown in the dialog. */
		public final CharSequence message;
		/** Short name of the current file or game, shown in the notification. */
		public final String name;
		/** Number of installable files found so far while scanning. */
		public final int found;
		public final int processed;
		public final int total;
		public final int installed;
		public final int updated;
		public final int skipped;
		public final boolean converting;
		public final boolean cancelling;
		public final boolean cancelled;
		public final List<String> failedNames;
		/** Why the source could not be read, or {@code null}. */
		@Nullable
		public final String error;
		public final boolean nothingFound;

		State(int phase, CharSequence message, String name, int found, int processed, int total,
			  int installed, int updated, int skipped, boolean converting, boolean cancelling,
			  boolean cancelled, List<String> failedNames, @Nullable String error,
			  boolean nothingFound) {
			this.phase = phase;
			this.message = message;
			this.name = name;
			this.found = found;
			this.processed = processed;
			this.total = total;
			this.installed = installed;
			this.updated = updated;
			this.skipped = skipped;
			this.converting = converting;
			this.cancelling = cancelling;
			this.cancelled = cancelled;
			this.failedNames = failedNames;
			this.error = error;
			this.nothingFound = nothingFound;
		}

		public boolean isRunning() {
			return phase == PHASE_SCANNING || phase == PHASE_INSTALLING;
		}
	}

	private final MutableLiveData<State> state = new MutableLiveData<>(State.IDLE);
	private final AtomicBoolean cancelled = new AtomicBoolean();
	private volatile boolean running;
	private volatile boolean uiVisible;
	private volatile AppRepository repository;

	// Everything below is touched by the worker thread only.
	private final List<String> failedNames = new ArrayList<>();
	private final Map<Uri, String> uriNames = new HashMap<>();
	/** Games already handled in this batch, e.g. a JAR that was installed through its JAD. */
	private final Set<String> handledGames = new HashSet<>();
	private int phase;
	private CharSequence message = "";
	private String name = "";
	private int found;
	private int processed;
	private int total;
	private int installed;
	private int updated;
	private int skipped;
	private boolean converting;
	private String error;
	private boolean nothingFound;

	private BatchImporter() {
	}

	public static BatchImporter get() {
		return INSTANCE;
	}

	/** True while files are being scanned or installed. */
	public static boolean isRunning() {
		return INSTANCE.running;
	}

	public LiveData<State> getState() {
		return state;
	}

	@NonNull
	public State getCurrentState() {
		State value = state.getValue();
		return value == null ? State.IDLE : value;
	}

	/** Whether the import writes to this repository (and so updates its list by itself). */
	public boolean usesRepository(AppRepository appRepository) {
		return repository == appRepository;
	}

	/** The dialog tells whether it is on screen, so that no result notification is needed. */
	public void setUiVisible(boolean visible) {
		uiVisible = visible;
	}

	public boolean isUiVisible() {
		return uiVisible;
	}

	/**
	 * Starts an import.
	 *
	 * @param files  the files to install for {@link #SOURCE_FILES}
	 * @param source the archive or folder for the other source types
	 * @return false if another import is still running; nothing is started then
	 */
	@MainThread
	public boolean start(Application app, AppRepository appRepository, @Nullable List<Uri> files,
						 @Nullable Uri source, int type) {
		if (running) {
			return false;
		}
		running = true;
		cancelled.set(false);
		failedNames.clear();
		uriNames.clear();
		handledGames.clear();
		phase = PHASE_SCANNING;
		message = "";
		name = "";
		found = processed = total = installed = updated = skipped = 0;
		converting = false;
		error = null;
		nothingFound = false;
		repository = appRepository;
		// Keeps the database open even if the library screen is closed meanwhile.
		appRepository.retain();
		state.setValue(snapshot());
		List<Uri> fileList = files == null ? new ArrayList<>() : new ArrayList<>(files);
		Thread thread = new Thread(() -> run(app, appRepository, fileList, source, type),
				"BatchImporter");
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		thread.start();
		return true;
	}

	/** Stops after the current game. Games installed so far stay in the library. */
	public void cancel() {
		// The worker thread publishes the change with its next step.
		cancelled.set(true);
	}

	/** Forgets the result of a finished import. */
	@MainThread
	public void acknowledge() {
		if (!running) {
			state.setValue(State.IDLE);
		}
	}

	private void run(Application app, AppRepository appRepository, List<Uri> files,
					 @Nullable Uri source, int type) {
		File stagingDir = null;
		try {
			List<Uri> uris = files;
			if (source != null) {
				stagingDir = ImportSources.newStagingDir(app);
				name = FileUtils.getDisplayName(app, source);
				message = name;
				publish();
				ImportSources.Listener listener = (count, fileName) -> {
					found = count;
					publish();
				};
				if (type == SOURCE_ZIP) {
					uris = ImportSources.fromZip(app, source, stagingDir, cancelled, listener);
				} else {
					uris = ImportSources.fromTree(app, source, stagingDir, cancelled, listener);
				}
			}
			uris = prepare(app, uris);
			if (cancelled.get()) {
				return;
			}
			if (uris.isEmpty()) {
				nothingFound = true;
				return;
			}
			total = uris.size();
			phase = PHASE_INSTALLING;
			for (Uri uri : uris) {
				if (cancelled.get()) {
					break;
				}
				processed++;
				installOne(app, appRepository, uri);
			}
		} catch (Throwable e) {
			Log.e(TAG, "Import failed", e);
			if (!cancelled.get()) {
				error = String.valueOf(e.getMessage());
			}
		} finally {
			if (stagingDir != null) {
				ImportSources.deleteRecursive(stagingDir);
			}
			phase = PHASE_FINISHED;
			converting = false;
			running = false;
			repository = null;
			state.postValue(snapshot());
			appRepository.release();
		}
	}

	/** Resolves display names, drops duplicates and puts descriptors first. */
	private List<Uri> prepare(Context context, List<Uri> source) {
		List<Uri> uris = new ArrayList<>(new LinkedHashSet<>(source));
		for (Uri uri : uris) {
			if (cancelled.get()) {
				break;
			}
			uriNames.put(uri, FileUtils.getDisplayName(context, uri));
		}
		// Descriptors first: a JAD is installed together with its JAR and adds its
		// attributes to the game, which would be lost if the JAR came first.
		Collections.sort(uris, (a, b) -> Boolean.compare(isJad(b), isJad(a)));
		return uris;
	}

	private boolean isJad(Uri uri) {
		String fileName = uriNames.get(uri);
		return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".jad");
	}

	private void installOne(Application app, AppRepository appRepository, Uri uri) {
		String sourceName = uriNames.get(uri);
		if (sourceName == null) {
			sourceName = "";
		}
		name = sourceName;
		message = sourceName;
		converting = false;
		publish();
		AppInstaller installer = new AppInstaller(null, uri, app, appRepository);
		boolean jarAttempted = false;
		try {
			int status = Single.<Integer>create(installer::loadInfo).blockingGet();
			while (true) {
				Descriptor nd = installer.getNewDescriptor();
				if (nd != null) {
					showDescriptor(app, nd, false);
				}
				switch (status) {
					case AppInstaller.STATUS_NEW:
					case AppInstaller.STATUS_NEWEST:
						if (handledGames.contains(gameKey(nd))) {
							// A second copy of a game that this batch has already dealt with.
							return;
						}
						boolean updating = status == AppInstaller.STATUS_NEWEST;
						if (nd != null && installer.getJar() == null) {
							showDescriptor(app, nd, true);
						}
						converting = true;
						publish();
						status = Single.<Integer>create(installer::install).blockingGet();
						converting = false;
						if (status == AppInstaller.STATUS_SUCCESS) {
							if (updating) {
								updated++;
							} else {
								installed++;
							}
							handledGames.add(gameKey(installer.getNewDescriptor()));
							publish();
							return;
						}
						// The downloaded JAR does not match its JAD: handled below.
						continue;
					case AppInstaller.STATUS_EQUAL:
					case AppInstaller.STATUS_OLDEST:
						// The game is already in the library: do not install it again.
						if (handledGames.add(gameKey(nd))) {
							skipped++;
						}
						return;
					case AppInstaller.STATUS_NEED_JAD:
					case AppInstaller.STATUS_UNMATCHED:
						if (!jarAttempted) {
							jarAttempted = true;
							Uri jar = findSelectedJar(nd, sourceName);
							if (jar != null) {
								// The JAR stays in the list. If this JAD turns out to be broken
								// the JAR is installed on its own, otherwise it is recognized
								// as already handled and ignored.
								status = installer.updateInfo(jar).blockingGet();
								continue;
							}
						}
						addFailedName(installer, sourceName);
						return;
					default:
						addFailedName(installer, sourceName);
						return;
				}
			}
		} catch (Throwable e) {
			Log.w(TAG, "Can't install " + sourceName, e);
			addFailedName(installer, sourceName);
		} finally {
			converting = false;
			installer.deleteTemp();
			installer.clearCache();
		}
	}

	private void showDescriptor(Context context, Descriptor descriptor, boolean fromNet) {
		SpannableStringBuilder info = descriptor.getInfo(context);
		if (fromNet) {
			info.append(context.getString(R.string.warn_install_from_net));
		}
		message = info;
		if (descriptor.getName() != null && !descriptor.getName().isEmpty()) {
			name = descriptor.getName();
		}
		publish();
	}

	private static String gameKey(@Nullable Descriptor descriptor) {
		if (descriptor == null) {
			return "";
		}
		return descriptor.getName() + '\n' + descriptor.getVendor();
	}

	@Nullable
	private Uri findSelectedJar(@Nullable Descriptor nd, String sourceName) {
		if (nd == null) {
			return null;
		}
		Set<String> candidates = new HashSet<>();
		String jarUrl = nd.getJarUrl();
		if (jarUrl != null) {
			int i = Math.max(jarUrl.lastIndexOf('/'), jarUrl.lastIndexOf('\\'));
			candidates.add(jarUrl.substring(i + 1).toLowerCase(Locale.ROOT));
		}
		String current = sourceName.toLowerCase(Locale.ROOT);
		int dot = current.lastIndexOf('.');
		if (dot > 0) {
			candidates.add(current.substring(0, dot) + ".jar");
		}
		for (Map.Entry<Uri, String> entry : uriNames.entrySet()) {
			String fileName = entry.getValue().toLowerCase(Locale.ROOT);
			if (fileName.endsWith(".jar") && candidates.contains(fileName)) {
				return entry.getKey();
			}
		}
		return null;
	}

	private void addFailedName(AppInstaller installer, String sourceName) {
		String failed = sourceName;
		Descriptor nd = installer.getNewDescriptor();
		if (nd != null && nd.getName() != null && !nd.getName().isEmpty()) {
			failed = nd.getName();
		}
		failedNames.add(failed);
	}

	private void publish() {
		state.postValue(snapshot());
	}

	private State snapshot() {
		boolean stop = cancelled.get();
		return new State(phase, message, name, found, processed, total, installed, updated,
				skipped, converting, stop && phase != PHASE_FINISHED, stop,
				Collections.unmodifiableList(new ArrayList<>(failedNames)), error, nothingFound);
	}

	/**
	 * Describes the result of a finished import.
	 *
	 * @param listFailed whether to append the names of the files that failed
	 */
	public static CharSequence getSummary(Context context, State s, boolean listFailed) {
		if (s.error != null) {
			return context.getString(R.string.import_read_error, s.error);
		}
		if (s.nothingFound) {
			return context.getString(R.string.import_nothing_found);
		}
		SpannableStringBuilder text = new SpannableStringBuilder();
		if (s.cancelled) {
			text.append(context.getString(R.string.import_cancelled)).append('\n');
		}
		text.append(context.getString(R.string.install_multiple_done, s.installed));
		if (s.updated > 0) {
			text.append('\n').append(context.getString(R.string.install_multiple_updated, s.updated));
		}
		if (s.skipped > 0) {
			text.append('\n').append(context.getString(R.string.install_multiple_skipped, s.skipped));
		}
		if (!s.failedNames.isEmpty()) {
			text.append('\n');
			text.append(context.getString(R.string.install_multiple_failed, s.failedNames.size()));
			if (listFailed) {
				int count = Math.min(s.failedNames.size(), MAX_FAILED_LIST_SIZE);
				for (int i = 0; i < count; i++) {
					text.append('\n').append(s.failedNames.get(i));
				}
				if (s.failedNames.size() > count) {
					text.append('\n').append(context.getString(R.string.install_multiple_more,
							s.failedNames.size() - count));
				}
			}
		}
		return text;
	}
}
