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

import android.Manifest;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import java.util.ArrayList;
import java.util.List;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.applist.AppListModel;
import ru.playsoftware.j2meloader.appsdb.AppRepository;
import ru.playsoftware.j2meloader.databinding.DialogInstallerBinding;

/**
 * Shows the progress and the result of {@link BatchImporter}. The import itself does
 * not depend on this dialog: it can be sent to the background and keeps running,
 * with a notification, even when the app is left.
 */
public class BatchInstallerDialog extends DialogFragment {
	private static final String ARG_URIS = "BatchInstallerDialog.uris";
	private static final String ARG_SOURCE = "BatchInstallerDialog.source";
	private static final String ARG_SOURCE_TYPE = "BatchInstallerDialog.sourceType";
	/** Only display the import that is already running. */
	private static final int SOURCE_NONE = -1;

	private final BatchImporter importer = BatchImporter.get();
	/** Android 13+ shows notifications only after the user allows them. */
	private final ActivityResultLauncher<String> notificationPermissionLauncher =
			registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
			});
	private boolean cancelRequested;
	private boolean askNotificationPermission;
	private List<String> failedNames = new ArrayList<>();

	private AppRepository appRepository;
	private Button btnClose;
	private Button btnCopy;
	private Button btnBackground;
	private AlertDialog mDialog;

	private DialogInstallerBinding binding;

	public static BatchInstallerDialog newInstance(List<Uri> uris) {
		BatchInstallerDialog fragment = new BatchInstallerDialog();
		Bundle args = new Bundle();
		args.putInt(ARG_SOURCE_TYPE, BatchImporter.SOURCE_FILES);
		args.putParcelableArrayList(ARG_URIS, new ArrayList<>(uris));
		fragment.setArguments(args);
		fragment.setCancelable(false);
		return fragment;
	}

	/** Installs every game found inside a ZIP archive. */
	public static BatchInstallerDialog newInstanceForZip(Uri zip) {
		return newInstanceForSource(zip, BatchImporter.SOURCE_ZIP);
	}

	/** Installs every game found in a folder picked with the system folder picker. */
	public static BatchInstallerDialog newInstanceForFolder(Uri tree) {
		return newInstanceForSource(tree, BatchImporter.SOURCE_FOLDER);
	}

	/** Shows the import that runs in the background, or its result. */
	public static BatchInstallerDialog newInstanceForRunning() {
		return newInstanceForSource(null, SOURCE_NONE);
	}

	private static BatchInstallerDialog newInstanceForSource(@Nullable Uri uri, int type) {
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
		// A recreated dialog (e.g. after a theme change) only shows the import again.
		if (savedInstanceState == null) {
			startImport();
		}
		importer.getState().observe(this, this::render);
	}

	private void startImport() {
		Bundle args = requireArguments();
		int type = args.getInt(ARG_SOURCE_TYPE, SOURCE_NONE);
		if (type == SOURCE_NONE) {
			return;
		}
		ArrayList<Uri> uris = args.getParcelableArrayList(ARG_URIS);
		Uri source = args.getParcelable(ARG_SOURCE);
		if (type == BatchImporter.SOURCE_FILES ? (uris == null || uris.isEmpty()) : source == null) {
			return;
		}
		Context context = requireContext();
		if (!importer.start(requireActivity().getApplication(), appRepository, uris, source, type)) {
			Toast.makeText(context, R.string.import_already_running, Toast.LENGTH_SHORT).show();
			return;
		}
		ImportService.start(context.getApplicationContext());
		askNotificationPermission = true;
	}

	/** The import does not wait for the answer; the notification appears once allowed. */
	private void requestNotificationPermission() {
		Context context = requireContext();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
				&& ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
				!= PackageManager.PERMISSION_GRANTED) {
			try {
				notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
			} catch (RuntimeException e) {
				e.printStackTrace();
			}
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
				.setPositiveButton(R.string.import_background, null)
				.setNegativeButton(android.R.string.cancel, null)
				.setNeutralButton(R.string.import_copy_failed, null)
				.create();
		return mDialog;
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		// Drop the views so that a hidden import does not keep the dialog window alive.
		binding = null;
		mDialog = null;
		btnClose = null;
		btnCopy = null;
		btnBackground = null;
	}

	@Override
	public void onStart() {
		super.onStart();
		importer.setUiVisible(true);
		ImportService.clearResult(requireContext());
		btnClose = mDialog.getButton(DialogInterface.BUTTON_NEGATIVE);
		btnClose.setOnClickListener(v -> onCloseClicked());
		btnCopy = mDialog.getButton(DialogInterface.BUTTON_NEUTRAL);
		btnCopy.setOnClickListener(v -> copyFailedList());
		btnBackground = mDialog.getButton(DialogInterface.BUTTON_POSITIVE);
		// The import keeps running; its notification brings this dialog back.
		btnBackground.setOnClickListener(v -> dismissAllowingStateLoss());
		render(importer.getCurrentState());
		if (askNotificationPermission) {
			askNotificationPermission = false;
			requestNotificationPermission();
		}
	}

	@Override
	public void onStop() {
		importer.setUiVisible(false);
		super.onStop();
	}

	private void onCloseClicked() {
		BatchImporter.State s = importer.getCurrentState();
		if (s.isRunning()) {
			cancelRequested = true;
			importer.cancel();
			render(s);
		} else {
			importer.acknowledge();
			dismissAllowingStateLoss();
		}
	}

	private void render(BatchImporter.State s) {
		if (binding == null || mDialog == null || btnClose == null) {
			return;
		}
		if (s.phase == BatchImporter.PHASE_IDLE) {
			// Nothing to show, e.g. the result was already closed elsewhere.
			dismissAllowingStateLoss();
			return;
		}
		boolean running = s.isRunning();
		setKeepScreenOn(running);
		binding.installationProgress.setVisibility(running ? View.VISIBLE : View.GONE);
		binding.installationStatus.setVisibility(running ? View.VISIBLE : View.GONE);
		btnBackground.setVisibility(running ? View.VISIBLE : View.GONE);
		if (!running) {
			cancelRequested = false;
			failedNames = s.failedNames;
			mDialog.setMessage(BatchImporter.getSummary(requireContext(), s, true));
			btnClose.setEnabled(true);
			btnClose.setText(R.string.close);
			btnCopy.setVisibility(failedNames.isEmpty() ? View.GONE : View.VISIBLE);
			return;
		}
		mDialog.setMessage(s.message);
		btnCopy.setVisibility(View.GONE);
		btnClose.setText(android.R.string.cancel);
		boolean cancelling = cancelRequested || s.cancelling;
		btnClose.setEnabled(!cancelling);
		if (s.phase == BatchImporter.PHASE_INSTALLING) {
			binding.installationProgress.setIndeterminate(false);
			binding.installationProgress.setMax(Math.max(1, s.total));
			binding.installationProgress.setProgress(Math.max(0, s.processed - 1));
		} else {
			binding.installationProgress.setIndeterminate(true);
		}
		if (cancelling) {
			binding.installationStatus.setText(R.string.import_cancelling);
		} else if (s.converting) {
			binding.installationStatus.setText(R.string.converting_wait);
		} else if (s.phase == BatchImporter.PHASE_INSTALLING) {
			binding.installationStatus.setText(getString(R.string.install_multiple_progress,
					s.processed, s.total));
		} else if (s.found > 0) {
			binding.installationStatus.setText(getString(R.string.import_scanning_found, s.found));
		} else {
			binding.installationStatus.setText(R.string.import_scanning);
		}
	}

	private void setKeepScreenOn(boolean keepOn) {
		Window window = mDialog.getWindow();
		if (window == null) {
			return;
		}
		if (keepOn) {
			window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		} else {
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
}
