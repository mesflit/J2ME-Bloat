/*
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2017-2020 Nikita Shakarun
 * Copyright 2018-2022 Yury Kharchenko
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

package ru.playsoftware.j2meloader.applist;

import static ru.playsoftware.j2meloader.util.Constants.KEY_APP_URI;
import static ru.playsoftware.j2meloader.util.Constants.KEY_MIDLET_NAME;
import static ru.playsoftware.j2meloader.util.Constants.PREF_APPS_VIEW;
import static ru.playsoftware.j2meloader.util.Constants.PREF_APP_SORT;
import static ru.playsoftware.j2meloader.util.Constants.PREF_LAST_PATH;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.sqlite.SQLiteDiskIOException;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.SearchView;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.util.Consumer;
import androidx.core.view.MenuHost;
import androidx.core.view.MenuProvider;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.appsdb.AppRepository;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.config.ConfigActivity;
import ru.playsoftware.j2meloader.config.ProfilesActivity;
import ru.playsoftware.j2meloader.databinding.BottomSheetImportBinding;
import ru.playsoftware.j2meloader.databinding.FragmentAppsListBinding;
import ru.playsoftware.j2meloader.databinding.ItemImportOptionBinding;
import ru.playsoftware.j2meloader.info.AboutDialogFragment;
import ru.playsoftware.j2meloader.info.HelpDialogFragment;
import ru.playsoftware.j2meloader.settings.SettingsActivity;
import ru.playsoftware.j2meloader.util.AppUtils;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.util.LogUtils;
import ru.woesss.j2me.installer.BatchImporter;
import ru.woesss.j2me.installer.BatchInstallerDialog;
import ru.woesss.j2me.installer.ImportSources;
import ru.woesss.j2me.installer.InstallerDialog;

public class AppsListFragment extends Fragment implements MenuProvider {
	private static final String TAG = AppsListFragment.class.getSimpleName();

	private final ActivityResultLauncher<String> openFileLauncher = registerForActivityResult(
			FileUtils.getFilePicker(),
			this::onPickFileResult);
	private final ActivityResultLauncher<String[]> openZipLauncher = registerForActivityResult(
			new ActivityResultContracts.OpenDocument(),
			this::onPickZipResult);
	private final ActivityResultLauncher<Uri> openFolderLauncher = registerForActivityResult(
			new ActivityResultContracts.OpenDocumentTree(),
			this::onPickFolderResult);
	private static final String[] ZIP_MIME_TYPES = {
			"application/zip",
			"application/x-zip",
			"application/x-zip-compressed",
			"multipart/x-zip",
			"application/octet-stream"
	};
	/** Approximate width of a grid cell, used to compute the number of columns. */
	private static final int GRID_CELL_WIDTH_DP = 96;
	private final AppsListAdapter adapter = new AppsListAdapter(this);
	/** Keeps the grid column count in sync with the window width. */
	private final Consumer<Configuration> configurationListener = configuration -> {
		GridLayoutManager manager = this.layoutManager;
		if (manager != null && this.adapter.getItemViewType(0) == AppsListAdapter.LAYOUT_TYPE_GRID) {
			manager.setSpanCount(Math.max(1, configuration.screenWidthDp / GRID_CELL_WIDTH_DP));
		}
	};
	private Uri appUri;
	private SharedPreferences preferences;
	private AppRepository appRepository;
	private Disposable searchViewDisposable;
	private GridLayoutManager layoutManager;
	private FragmentAppsListBinding binding;
	private int importedGames;
	private int importPhase;

	public static AppsListFragment newInstance(Uri data) {
		AppsListFragment fragment = new AppsListFragment();
		Bundle args = new Bundle();
		args.putParcelable(KEY_APP_URI, data);
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Bundle args = requireArguments();
		appUri = args.getParcelable(KEY_APP_URI);
		args.remove(KEY_APP_URI);
		FragmentActivity activity = requireActivity();
		preferences = PreferenceManager.getDefaultSharedPreferences(activity);
		AppListModel appListModel = new ViewModelProvider(activity).get(AppListModel.class);
		appRepository = appListModel.getAppRepository();
		appRepository.observeErrors(this, this::alertDbError);
		appRepository.observeApps(this, this::onDbUpdated);
		BatchImporter.get().getState().observe(this, this::onImportProgress);
	}

	/**
	 * A background import can outlive the screen that started it. It then writes through
	 * the database of that old screen, so this list has to be told to read again.
	 */
	private void onImportProgress(BatchImporter.State s) {
		int games = s.installed + s.updated;
		boolean changed = games != importedGames || s.phase != importPhase;
		importedGames = games;
		importPhase = s.phase;
		if (changed && s.phase != BatchImporter.PHASE_IDLE
				&& !BatchImporter.get().usesRepository(appRepository)) {
			appRepository.refresh();
		}
	}

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		binding = FragmentAppsListBinding.inflate(inflater, container, false);
		return binding.getRoot();
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		MenuHost menuHost = requireActivity();
		menuHost.addMenuProvider(this, getViewLifecycleOwner());

		adapter.setEmptyStateListener(this::onEmptyStateChanged);
		int viewType = preferences.getInt(PREF_APPS_VIEW, AppsListAdapter.LAYOUT_TYPE_GRID);
		adapter.setLayout(viewType);
		layoutManager = new GridLayoutManager(requireContext(), getSpanCount(viewType));
		requireActivity().addOnConfigurationChangedListener(configurationListener);
		binding.list.setLayoutManager(layoutManager);
		binding.list.setAdapter(adapter);
		binding.list.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
				if (binding == null) {
					return;
				}
				if (dy > 0) {
					binding.floatingActionButton.shrink();
				} else if (dy < 0) {
					binding.floatingActionButton.extend();
				}
			}
		});
		binding.fastScroller.attach(binding.list);
		binding.floatingActionButton.setOnClickListener(v -> showImportSheet());
		binding.emptyAction.setOnClickListener(v -> showImportSheet());
	}

	private int getSpanCount(int viewType) {
		if (viewType != AppsListAdapter.LAYOUT_TYPE_GRID) {
			return 1;
		}
		return Math.max(1, getResources().getConfiguration().screenWidthDp / GRID_CELL_WIDTH_DP);
	}

	private void onEmptyStateChanged(int shown, int total, CharSequence query) {
		if (binding == null) {
			return;
		}
		showGameCount(shown, total);
		boolean empty = shown == 0;
		boolean libraryEmpty = total == 0;
		binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
		if (!empty) {
			return;
		}
		if (libraryEmpty) {
			binding.emptyTitle.setText(R.string.library_empty_title);
			binding.emptyMessage.setText(R.string.library_empty_message);
			binding.emptyAction.setVisibility(View.VISIBLE);
		} else {
			binding.emptyTitle.setText(R.string.no_data_for_display);
			binding.emptyMessage.setText(getString(R.string.msg_no_matches, query));
			binding.emptyAction.setVisibility(View.GONE);
		}
	}

	/** Shows the number of installed games under the title in the toolbar. */
	private void showGameCount(int shown, int total) {
		FragmentActivity activity = getActivity();
		if (!(activity instanceof AppCompatActivity)) {
			return;
		}
		ActionBar actionBar = ((AppCompatActivity) activity).getSupportActionBar();
		if (actionBar == null) {
			return;
		}
		if (total == 0) {
			actionBar.setSubtitle(null);
		} else if (shown == total) {
			actionBar.setSubtitle(getResources().getQuantityString(R.plurals.games_count, total, total));
		} else {
			actionBar.setSubtitle(getString(R.string.games_count_filtered, shown, total));
		}
	}

	/** Lets the user choose how to add games: files, a ZIP archive or a folder. */
	private void showImportSheet() {
		BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
		BottomSheetImportBinding sheet = BottomSheetImportBinding.inflate(getLayoutInflater());
		bindImportOption(sheet.importFiles, R.drawable.ic_import_file,
				R.string.import_option_files, R.string.import_option_files_desc, () -> {
					dialog.dismiss();
					pickFiles();
				});
		bindImportOption(sheet.importZip, R.drawable.ic_import_zip,
				R.string.import_option_zip, R.string.import_option_zip_desc, () -> {
					dialog.dismiss();
					launchPicker(() -> openZipLauncher.launch(ZIP_MIME_TYPES));
				});
		bindImportOption(sheet.importFolder, R.drawable.ic_import_folder,
				R.string.import_option_folder, R.string.import_option_folder_desc, () -> {
					dialog.dismiss();
					launchPicker(() -> openFolderLauncher.launch(null));
				});
		dialog.setContentView(sheet.getRoot());
		dialog.show();
	}

	private static void bindImportOption(ItemImportOptionBinding option, int icon, int title,
										 int subtitle, Runnable action) {
		option.optionIcon.setImageResource(icon);
		option.optionTitle.setText(title);
		option.optionSubtitle.setText(subtitle);
		option.getRoot().setOnClickListener(v -> action.run());
	}

	private void launchPicker(Runnable launch) {
		try {
			launch.run();
		} catch (ActivityNotFoundException e) {
			Toast.makeText(getContext(), R.string.error_no_picker, Toast.LENGTH_SHORT).show();
			e.printStackTrace();
		}
	}

	private void pickFiles() {
		String path = preferences.getString(PREF_LAST_PATH, null);
		if (path == null) {
			File dir = Environment.getExternalStorageDirectory();
			if (dir.canRead()) {
				path = dir.getAbsolutePath();
			}
		}
		String startPath = path;
		launchPicker(() -> openFileLauncher.launch(startPath));
	}

	private void onPickZipResult(Uri uri) {
		if (uri != null) {
			BatchInstallerDialog.newInstanceForZip(uri).show(getParentFragmentManager(), "installer");
		}
	}

	private void onPickFolderResult(Uri uri) {
		if (uri != null) {
			BatchInstallerDialog.newInstanceForFolder(uri).show(getParentFragmentManager(), "installer");
		}
	}

	private void alertDbError(Throwable throwable) {
		Activity activity = getActivity();
		if (activity == null) {
			Log.e(TAG, "Db error detected", throwable);
			return;
		}
		if (throwable instanceof SQLiteDiskIOException) {
			Toast.makeText(activity, R.string.error_disk_io, Toast.LENGTH_SHORT).show();
		} else {
			String msg = activity.getString(R.string.error) + ": " + throwable.getMessage();
			Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
		}
	}

	private void onPickFileResult(List<Uri> uris) {
		if (uris == null || uris.isEmpty()) {
			return;
		}
		preferences.edit()
				.putString(PREF_LAST_PATH, uris.get(0).getPath())
				.apply();
		if (uris.size() == 1 && ImportSources.isZip(FileUtils.getDisplayName(requireContext(), uris.get(0)))) {
			BatchInstallerDialog.newInstanceForZip(uris.get(0)).show(getParentFragmentManager(), "installer");
		} else if (uris.size() == 1) {
			InstallerDialog.newInstance(uris.get(0)).show(getParentFragmentManager(), "installer");
		} else {
			BatchInstallerDialog.newInstance(uris).show(getParentFragmentManager(), "installer");
		}
	}

	private void alertRename(AppItem item) {
		FragmentActivity activity = requireActivity();
		EditText editText = new EditText(activity);
		editText.setText(item.getTitle());
		float density = getResources().getDisplayMetrics().density;
		LinearLayout linearLayout = new LinearLayout(activity);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		int margin = (int) (density * 20);
		params.setMargins(margin, 0, margin, 0);
		linearLayout.addView(editText, params);
		int paddingVertical = (int) (density * 16);
		int paddingHorizontal = (int) (density * 8);
		editText.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical);
		AlertDialog.Builder builder = new AlertDialog.Builder(activity)
				.setTitle(R.string.action_context_rename)
				.setView(linearLayout)
				.setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {
					String title = editText.getText().toString().trim();
					if (title.equals("")) {
						Toast.makeText(getActivity(), R.string.error, Toast.LENGTH_SHORT).show();
					} else {
						item.setTitle(title);
						appRepository.update(item);
					}
				})
				.setNegativeButton(android.R.string.cancel, null);
		builder.show();
	}

	private void alertDelete(AppItem item) {
		AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity())
				.setTitle(android.R.string.dialog_alert_title)
				.setMessage(R.string.message_delete)
				.setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {
					AppUtils.deleteApp(item);
					appRepository.delete(item);
				})
				.setNegativeButton(android.R.string.cancel, null);
		builder.show();
	}

	@Override
	public void onCreateContextMenu(@NonNull ContextMenu menu, @NonNull View v,
									ContextMenu.ContextMenuInfo menuInfo) {
		MenuInflater inflater = requireActivity().getMenuInflater();
		inflater.inflate(R.menu.context_main, menu);
		if (!ShortcutManagerCompat.isRequestPinShortcutSupported(requireContext())) {
			menu.findItem(R.id.action_context_shortcut).setVisible(false);
		}

		AppItem appItem = ((AppItemLayout.AppItemMenuInfo) menuInfo).appItem;
		if (!new File(appItem.getPathExt() + Config.MIDLET_RES_FILE).exists()) {
			menu.findItem(R.id.action_context_reinstall).setVisible(false);
		}
	}

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		AppItem appItem = ((AppItemLayout.AppItemMenuInfo) item.getMenuInfo()).appItem;
		int itemId = item.getItemId();
		if (itemId == R.id.action_context_shortcut) {
			requestAddShortcut(appItem);
		} else if (itemId == R.id.action_context_rename) {
			alertRename(appItem);
		} else if (itemId == R.id.action_context_settings) {
			Config.startApp(requireActivity(), appItem.getTitle(), appItem.getPathExt(), true);
		} else if (itemId == R.id.action_context_reinstall) {
			InstallerDialog.newInstance(appItem.getId()).show(getParentFragmentManager(), "installer");
		} else if (itemId == R.id.action_context_delete) {
			alertDelete(appItem);
		} else {
			return super.onContextItemSelected(item);
		}
		return true;
	}

	private void requestAddShortcut(AppItem appItem) {
		FragmentActivity activity = requireActivity();
		Bitmap bitmap = AppUtils.getIconBitmap(appItem);
		IconCompat icon;
		if (bitmap == null) {
			icon = IconCompat.createWithResource(activity, R.mipmap.ic_launcher);
		} else {
			int width = bitmap.getWidth();
			int height = bitmap.getHeight();
			ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
			int iconSize = am.getLauncherLargeIconSize();
			Rect src;
			if (width > height) {
				int left = (width - height) / 2;
				src = new Rect(left, 0, left + height, height);
			} else if (width < height) {
				int top = (height - width) / 2;
				src = new Rect(0, top, width, top + width);
			} else {
				src = null;
			}
			Bitmap scaled = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888);
			Canvas canvas = new Canvas(scaled);
			canvas.drawBitmap(bitmap, src, new RectF(0, 0, iconSize, iconSize), null);
			icon = IconCompat.createWithBitmap(scaled);
		}
		String title = appItem.getTitle();
		Intent launchIntent = new Intent(Intent.ACTION_DEFAULT, Uri.parse(appItem.getPathExt()),
				activity, ConfigActivity.class);
		launchIntent.putExtra(KEY_MIDLET_NAME, title);
		ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(activity, title)
				.setIntent(launchIntent)
				.setShortLabel(title)
				.setIcon(icon)
				.build();
		ShortcutManagerCompat.requestPinShortcut(activity, shortcut, null);
	}

	@Override
	public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
		menuInflater.inflate(R.menu.main, menu);
		final MenuItem searchItem = menu.findItem(R.id.action_search);
		SearchView searchView = (SearchView) searchItem.getActionView();
		searchView.setQueryHint(getString(R.string.search));
		// The menu is recreated on invalidation: do not leave the old subscription behind.
		if (searchViewDisposable != null) {
			searchViewDisposable.dispose();
		}
		searchViewDisposable = Observable.create((ObservableOnSubscribe<String>) emitter -> {
					emitter.setCancellable(() -> searchView.setOnQueryTextListener(null));
					searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
							@Override
							public boolean onQueryTextSubmit(String query) {
								emitter.onNext(query);
								return true;
							}

							@Override
							public boolean onQueryTextChange(String newText) {
								emitter.onNext(newText);
								return true;
							}
						});
				}).debounce(300, TimeUnit.MILLISECONDS)
				.map(AppsListAdapter::normalize)
				.distinctUntilChanged()
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(charSequence -> adapter.getFilter().filter(charSequence));
		int type = preferences.getInt(PREF_APPS_VIEW, AppsListAdapter.LAYOUT_TYPE_GRID);
		if (type == AppsListAdapter.LAYOUT_TYPE_LIST) {
			menu.findItem(R.id.action_view).setIcon(R.drawable.ic_action_apps_view_grid);
		}
	}

	@SuppressLint("NotifyDataSetChanged")
	@Override
	public boolean onMenuItemSelected(MenuItem item) {
		FragmentActivity activity = requireActivity();
		int itemId = item.getItemId();
		if (itemId == R.id.action_about) {
			AboutDialogFragment aboutDialogFragment = new AboutDialogFragment();
			aboutDialogFragment.show(getChildFragmentManager(), "about");
		} else if (itemId == R.id.action_profiles) {
			Intent intentProfiles = new Intent(activity, ProfilesActivity.class);
			startActivity(intentProfiles);
		} else if (item.getItemId() == R.id.action_settings) {
			startActivity(new Intent(activity, SettingsActivity.class));
		} else if (itemId == R.id.action_help) {
			HelpDialogFragment helpDialogFragment = new HelpDialogFragment();
			helpDialogFragment.show(getChildFragmentManager(), "help");
		} else if (itemId == R.id.action_save_log) {
			try {
				LogUtils.writeLog();
				Toast.makeText(activity, R.string.log_saved, Toast.LENGTH_SHORT).show();
			} catch (IOException e) {
				e.printStackTrace();
				Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show();
			}
		} else if (itemId == R.id.action_exit_app) {
			activity.finish();
		} else if (itemId == R.id.action_sort) {
			showSortDialog();
		} else if (itemId == R.id.action_view) {
			int viewType = adapter.getItemViewType(0);
			viewType = (viewType + 1) % 2;
			if (viewType == AppsListAdapter.LAYOUT_TYPE_LIST) {
				item.setIcon(R.drawable.ic_action_apps_view_grid);
			} else {
				item.setIcon(R.drawable.ic_action_apps_view_list);
			}
			int spanCount = getSpanCount(viewType);
			layoutManager.setSpanCount(spanCount);
			adapter.setLayout(viewType);
			adapter.notifyDataSetChanged();
			preferences.edit().putInt(PREF_APPS_VIEW, viewType).apply();
		} else {
			return false;
		}
		return true;
	}

	private void showSortDialog() {
		int variant = preferences.getInt(PREF_APP_SORT, 0);
		FragmentActivity activity = requireActivity();
		AlertDialog.Builder builder = new AlertDialog.Builder(activity)
				.setTitle(R.string.pref_app_sort_title)
				.setAdapter(new SortAdapter(activity, variant), (d, v) -> setSort(v));
		builder.show();
	}

	private void setSort(int sortVariant) {
		if (preferences.getInt(PREF_APP_SORT, 0) == sortVariant) {
			sortVariant |= 0x80000000;
		}
		preferences.edit().putInt(PREF_APP_SORT, sortVariant).apply();
	}

	private void onDbUpdated(List<AppItem> items) {
		adapter.setItems(items);
		if (appUri != null) {
			if (ImportSources.isZip(FileUtils.getDisplayName(requireContext(), appUri))) {
				BatchInstallerDialog.newInstanceForZip(appUri).show(getParentFragmentManager(), "installer");
			} else {
				InstallerDialog.newInstance(appUri).show(getParentFragmentManager(), "installer");
			}
			appUri = null;
		}
	}

	private static class SortAdapter extends ArrayAdapter<String> {
		private final int variant;
		private final Drawable drawableArrowDown;
		private final Drawable drawableArrowUp;

		public SortAdapter(Context context, int variant) {
			super(context,
					android.R.layout.simple_list_item_1,
					context.getResources().getStringArray(R.array.pref_app_sort_entries));
			this.variant = variant;
			drawableArrowDown = AppCompatResources.getDrawable(context, R.drawable.ic_arrow_down);
			drawableArrowUp = AppCompatResources.getDrawable(context, R.drawable.ic_arrow_up);
		}

		@NonNull
		@Override
		public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
			TextView tv = (TextView) super.getView(position, convertView, parent);
			if ((variant & 0x7FFFFFFF) == position) {
				TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(tv, null, null,
						variant >= 0 ? drawableArrowDown : drawableArrowUp, null);
			} else {
				tv.setCompoundDrawables(null, null, null, null);
			}
			return tv;
		}
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		// The adapter and the listeners outlive the view; detach them so it can be collected.
		requireActivity().removeOnConfigurationChangedListener(configurationListener);
		adapter.setEmptyStateListener(null);
		adapter.clearIconCache();
		if (binding != null) {
			binding.fastScroller.detach();
			binding.list.setAdapter(null);
		}
		if (searchViewDisposable != null) {
			searchViewDisposable.dispose();
			searchViewDisposable = null;
		}
		layoutManager = null;
		binding = null;
	}

	@Override
	public void onDestroy() {
		if (searchViewDisposable != null) {
			searchViewDisposable.dispose();
		}
		super.onDestroy();
	}
}
