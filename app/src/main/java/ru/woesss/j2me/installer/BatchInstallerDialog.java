/*
 *  Copyright 2020-2023 Yury Kharchenko
 *  Copyright 2026 Nikita Shakarun
 *  Copyright 2026 mesflit
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ru.woesss.j2me.installer;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.applist.AppListModel;
import ru.playsoftware.j2meloader.appsdb.AppRepository;
import ru.playsoftware.j2meloader.databinding.DialogInstallerBinding;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.woesss.j2me.jar.Descriptor;

/**
 * Installs many MIDlets in one go. The sources can be a list of files,
 * a ZIP archive or a folder; archives and folders are scanned first.
 */
public class BatchInstallerDialog extends DialogFragment {
	private static final String ARG_URIS = "BatchInstallerDialog.uris";
	private static final String ARG_SOURCE = "BatchInstallerDialog.source";
	private static final String ARG_SOURCE_TYPE = "BatchInstallerDialog.sourceType";
	private static final int SOURCE_FILES = 0;
	private static final int SOURCE_ZIP = 1;
	private static final int SOURCE_FOLDER = 2;
	private static final int MAX_FAILED_LIST_SIZE = 10;

	private final CompositeDisposable compositeDisposable = new CompositeDisposable();
	private final AtomicBoolean cancelled = new AtomicBoolean();
	private final List<Uri> uriQueue = new ArrayList<>();
	private final List<String> failedNames = new ArrayList<>();
	private final Map<Uri, String> uriNames = new HashMap<>();
	private boolean started;
	private boolean finished;
	private boolean jarAttempted;
	private int total;
	private int processed;
	private int installed;
	private String currentSourceName;
	private File stagingDir;

	private AppRepository appRepository;
	private Button btnClose;
	private Button btnCopy;
	private AppInstaller installer;
	private AlertDialog mDialog;

	private DialogInstallerBinding binding;

	public static BatchInstallerDialog newInstance(List<Uri> uris) {
		BatchInstallerDialog fragment = new BatchInstallerDialog();
		Bundle args = new Bundle();
		args.putInt(ARG_SOURCE_TYPE, SOURCE_FILES);
		args.putParcelableArrayList(ARG_URIS, new ArrayList<>(uris));
		fragment.setArguments(args);
		fragment.setCancelable(false);
		return fragment;
	}

	/** Installs every game found inside a ZIP archive. */
	public static BatchInstallerDialog newInstanceForZip(Uri zip) {
		return newInstanceForSource(zip, SOURCE_ZIP);
	}

	/** Installs every game found in a folder picked with the system folder picker. */
	public static BatchInstallerDialog newInstanceForFolder(Uri tree) {
		return newInstanceForSource(tree, SOURCE_FOLDER);
	}

	private static BatchInstallerDialog newInstanceForSource(Uri uri, int type) {
		BatchInstallerDialog fragment = new BatchInstallerDialog();
		Bundle args = new Bundle();
		args.putInt(ARG_SOURCE_TYPE, type);
		args.putParcelable(ARG_SOURCE, uri);
		fragment.setArguments(args);
		fragment.setCancelable(false);
		return fragment;
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		AppListModel appListModel = new ViewModelProvider(requireActivity()).get(AppListModel.class);
		appRepository = appListModel.getAppRepository();
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (savedInstanceState != null) {
			dismissAllowingStateLoss();
		}
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		binding = DialogInstallerBinding.inflate(LayoutInflater.from(getContext()));
		mDialog = new AlertDialog.Builder(requireActivity(), getTheme())
				.setIcon(R.mipmap.ic_launcher)
				.setView(binding.getRoot())
				.setTitle(R.string.import_title)
				.setMessage("")
				.setCancelable(false)
				.setNegativeButton(android.R.string.cancel, null)
				.setNeutralButton(R.string.import_copy_failed, null)
				.create();
		return mDialog;
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		binding = null;
	}

	@Override
	public void onDestroy() {
		cancelled.set(true);
		compositeDisposable.dispose();
		cleanup();
		super.onDestroy();
	}

	@Override
	public void onStart() {
		super.onStart();
		if (started) {
			return;
		}
		started = true;
		Window window = mDialog.getWindow();
		if (window != null) {
			window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}
		btnClose = mDialog.getButton(DialogInterface.BUTTON_NEGATIVE);
		btnClose.setOnClickListener(v -> onCloseClicked());
		btnCopy = mDialog.getButton(DialogInterface.BUTTON_NEUTRAL);
		btnCopy.setVisibility(View.GONE);
		btnCopy.setOnClickListener(v -> copyFailedList());

		Bundle args = requireArguments();
		int type = args.getInt(ARG_SOURCE_TYPE, SOURCE_FILES);
		binding.installationStatus.setText(R.string.loading_info);
		showProgress(true);
		if (type == SOURCE_FILES) {
			ArrayList<Uri> uris = args.getParcelableArrayList(ARG_URIS);
			if (uris == null || uris.isEmpty()) {
				dismissAllowingStateLoss();
				return;
			}
			startInstall(uris);
		} else {
			Uri source = args.getParcelable(ARG_SOURCE);
			if (source == null) {
				dismissAllowingStateLoss();
				return;
			}
			scanSource(source, type);
		}
	}

	/** Looks for installable files in a ZIP archive or folder off the main thread. */
	private void scanSource(Uri source, int type) {
		Context context = requireContext().getApplicationContext();
		stagingDir = ImportSources.newStagingDir(context);
		File dir = stagingDir;
		mDialog.setMessage(FileUtils.getDisplayName(context, source));
		binding.installationStatus.setText(R.string.import_scanning);
		View root = binding.getRoot();
		ImportSources.Listener listener = (count, name) ->
				root.post(() -> {
					if (binding != null && isAdded() && !cancelled.get()) {
						binding.installationStatus.setText(
								getString(R.string.import_scanning_found, count));
					}
				});
		Disposable disposable = Single.fromCallable(() -> {
					if (type == SOURCE_ZIP) {
						return ImportSources.fromZip(context, source, dir, cancelled, listener);
					} else {
						return ImportSources.fromTree(context, source, dir, cancelled, listener);
					}
				})
				.subscribeOn(Schedulers.io())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(uris -> {
					if (!isAdded()) {
						return;
					}
					if (cancelled.get()) {
						onFinished();
					} else if (uris.isEmpty()) {
						showResult(getString(R.string.import_nothing_found));
					} else {
						startInstall(uris);
					}
				}, e -> {
					e.printStackTrace();
					if (!isAdded()) {
						return;
					}
					if (cancelled.get()) {
						onFinished();
					} else {
						showResult(getString(R.string.import_read_error,
								String.valueOf(e.getMessage())));
					}
				});
		compositeDisposable.add(disposable);
	}

	private void startInstall(List<Uri> uris) {
		total = uris.size();
		uriQueue.addAll(uris);
		binding.installationProgress.setIndeterminate(false);
		binding.installationProgress.setMax(total);
		binding.installationProgress.setProgress(0);
		Context context = requireContext();
		for (Uri uri : uris) {
			uriNames.put(uri, FileUtils.getDisplayName(context, uri));
		}
		nextFile();
	}

	private void onCloseClicked() {
		if (finished) {
			dismiss();
			return;
		}
		if (!cancelled.getAndSet(true)) {
			binding.installationStatus.setText(R.string.import_cancelling);
			btnClose.setEnabled(false);
		}
	}

	private void nextFile() {
		if (installer != null) {
			installer.clearCache();
		}
		if (uriQueue.isEmpty() || cancelled.get()) {
			onFinished();
			return;
		}
		Uri uri = uriQueue.remove(0);
		processed++;
		jarAttempted = false;
		currentSourceName = uriNames.get(uri);
		binding.installationStatus.setText(getString(R.string.install_multiple_progress,
				processed, total));
		binding.installationProgress.setProgress(processed - 1);
		mDialog.setMessage(currentSourceName);
		showProgress(true);
		installer = new AppInstaller(null, uri, requireActivity().getApplication(), appRepository);
		Disposable disposable = Single.create(installer::loadInfo)
				.subscribeOn(Schedulers.computation())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(this::onProgress, this::onError);
		compositeDisposable.add(disposable);
	}

	private void onProgress(@NonNull Integer status) {
		if (!isAdded()) {
			return;
		}
		Descriptor nd = installer.getNewDescriptor();
		if (nd != null) {
			mDialog.setMessage(nd.getInfo(requireActivity()));
		}
		switch (status) {
			case AppInstaller.STATUS_SUCCESS:
				installed++;
				nextFile();
				return;
			case AppInstaller.STATUS_NEW:
			case AppInstaller.STATUS_OLDEST:
			case AppInstaller.STATUS_EQUAL:
			case AppInstaller.STATUS_NEWEST:
				convert();
				return;
			default:
				if ((status == AppInstaller.STATUS_NEED_JAD || status == AppInstaller.STATUS_UNMATCHED)
						&& installFromSelectedJar()) {
					return;
				}
				failCurrentFile();
		}
	}

	private boolean installFromSelectedJar() {
		if (jarAttempted) {
			return false;
		}
		jarAttempted = true;
		Uri jar = findSelectedJar();
		if (jar == null) {
			return false;
		}
		if (uriQueue.remove(jar)) {
			processed++;
			binding.installationProgress.setProgress(processed - 1);
		}
		Disposable disposable = installer.updateInfo(jar)
				.subscribeOn(Schedulers.computation())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(this::onProgress, this::onError);
		compositeDisposable.add(disposable);
		return true;
	}

	@Nullable
	private Uri findSelectedJar() {
		Descriptor nd = installer.getNewDescriptor();
		if (nd == null) {
			return null;
		}
		Set<String> candidates = new HashSet<>();
		String jarUrl = nd.getJarUrl();
		if (jarUrl != null) {
			int i = Math.max(jarUrl.lastIndexOf('/'), jarUrl.lastIndexOf('\\'));
			candidates.add(jarUrl.substring(i + 1).toLowerCase());
		}
		String current = currentSourceName.toLowerCase();
		int dot = current.lastIndexOf('.');
		if (dot > 0) {
			candidates.add(current.substring(0, dot) + ".jar");
		}
		Uri processedUri = null;
		for (Map.Entry<Uri, String> entry : uriNames.entrySet()) {
			String name = entry.getValue().toLowerCase();
			if (!name.endsWith(".jar") || !candidates.contains(name)) {
				continue;
			}
			Uri uri = entry.getKey();
			if (uriQueue.contains(uri)) {
				return uri;
			}
			if (processedUri == null) {
				processedUri = uri;
			}
		}
		return processedUri;
	}

	private void convert() {
		if (installer.getJar() == null) {
			SpannableStringBuilder info = installer.getNewDescriptor().getInfo(requireActivity());
			info.append(getString(R.string.warn_install_from_net));
			mDialog.setMessage(info);
		}
		binding.installationStatus.setText(R.string.converting_wait);
		showProgress(true);
		Disposable disposable = Single.create(installer::install)
				.subscribeOn(Schedulers.computation())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(this::onProgress, this::onError);
		compositeDisposable.add(disposable);
	}

	private void failCurrentFile() {
		addFailedName();
		installer.deleteTemp();
		installer.clearCache();
		nextFile();
	}

	private void addFailedName() {
		String name = currentSourceName;
		Descriptor nd = installer.getNewDescriptor();
		if (nd != null && nd.getName() != null && !nd.getName().isEmpty()) {
			name = nd.getName();
		}
		failedNames.add(name);
	}

	private void onError(Throwable e) {
		e.printStackTrace();
		if (!isAdded()) {
			return;
		}
		addFailedName();
		installer.deleteTemp();
		installer.clearCache();
		nextFile();
	}

	private void onFinished() {
		SpannableStringBuilder message = new SpannableStringBuilder();
		if (cancelled.get()) {
			message.append(getString(R.string.import_cancelled)).append('\n');
		}
		message.append(getString(R.string.install_multiple_done, installed));
		if (!failedNames.isEmpty()) {
			message.append('\n');
			message.append(getString(R.string.install_multiple_failed, failedNames.size()));
			int count = Math.min(failedNames.size(), MAX_FAILED_LIST_SIZE);
			for (int i = 0; i < count; i++) {
				message.append('\n').append(failedNames.get(i));
			}
			if (failedNames.size() > count) {
				message.append('\n').append(getString(R.string.install_multiple_more,
						failedNames.size() - count));
			}
			btnCopy.setVisibility(View.VISIBLE);
		}
		showResult(message);
	}

	private void showResult(CharSequence message) {
		finished = true;
		cleanup();
		mDialog.setMessage(message);
		showProgress(false);
		btnClose.setEnabled(true);
		btnClose.setText(R.string.close);
		Window window = mDialog.getWindow();
		if (window != null) {
			window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}
	}

	private void copyFailedList() {
		Context context = requireContext();
		ClipboardManager clipboard =
				(ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
		if (clipboard == null) {
			return;
		}
		String text = TextUtils.join("\n", failedNames);
		clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.import_title), text));
		Toast.makeText(context, R.string.import_copied, Toast.LENGTH_SHORT).show();
	}

	private void cleanup() {
		if (stagingDir != null) {
			File dir = stagingDir;
			stagingDir = null;
			Schedulers.io().scheduleDirect(() -> ImportSources.deleteRecursive(dir));
		}
	}

	private void showProgress(boolean show) {
		int visibility = show ? View.VISIBLE : View.GONE;
		binding.installationProgress.setVisibility(visibility);
		binding.installationStatus.setVisibility(visibility);
	}
}
