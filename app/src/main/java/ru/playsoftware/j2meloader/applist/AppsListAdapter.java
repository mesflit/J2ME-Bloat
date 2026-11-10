/*
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2017-2019 Nikita Shakarun
 * Copyright 2019-2023 Yury Kharchenko
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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.databinding.ListRowGridJarBinding;
import ru.playsoftware.j2meloader.databinding.ListRowJarBinding;

class AppsListAdapter extends ListAdapter<AppItem, AppsListAdapter.AppViewHolder> implements Filterable {
	static final int LAYOUT_TYPE_LIST = 0;
	static final int LAYOUT_TYPE_GRID = 1;

	/** Memory used for decoded game icons, so that scrolling does not decode them again. */
	private static final int ICON_CACHE_SIZE = 8 * 1024 * 1024;

	private final View.OnCreateContextMenuListener contextMenuListener;
	private final LruCache<String, Bitmap> iconCache = new LruCache<String, Bitmap>(ICON_CACHE_SIZE) {
		@Override
		protected int sizeOf(String key, Bitmap value) {
			return value.getByteCount();
		}
	};
	private List<AppItem> list = new ArrayList<>();
	private final AppFilter appFilter = new AppFilter();
	private CharSequence filterConstraint;
	private EmptyStateListener emptyStateListener;
	/** Ids of the games picked in multi-select mode. */
	private final Set<Integer> selectedIds = new HashSet<>();
	private boolean selectionMode;
	private SelectionListener selectionListener;
	private int layout = LAYOUT_TYPE_LIST;

	AppsListAdapter(View.OnCreateContextMenuListener contextMenuListener) {
		super(new DiffUtil.ItemCallback<AppItem>() {
			@Override
			public boolean areItemsTheSame(@NonNull AppItem oldItem, @NonNull AppItem newItem) {
				return oldItem.getId() == newItem.getId();
			}

			@Override
			public boolean areContentsTheSame(@NonNull AppItem oldItem, @NonNull AppItem newItem) {
				return Objects.equals(oldItem.getTitle(), newItem.getTitle()) &&
						Objects.equals(oldItem.getVersion(), newItem.getVersion()) &&
						Objects.equals(oldItem.getAuthor(), newItem.getAuthor()) &&
						Objects.equals(oldItem.getImagePath(), newItem.getImagePath()) &&
						oldItem.isFavorite() == newItem.isFavorite();
			}
		});
		this.contextMenuListener = contextMenuListener;
	}

	@Override
	public int getItemViewType(int position) {
		return layout;
	}

	@NonNull
	@Override
	public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		LayoutInflater inflater = LayoutInflater.from(parent.getContext());
		if (viewType == 1) {
			ListRowGridJarBinding binding = ListRowGridJarBinding.inflate(inflater, parent, false);
			return new AppViewHolder(binding, contextMenuListener);
		} else {
			ListRowJarBinding binding = ListRowJarBinding.inflate(inflater, parent, false);
			return new AppListViewHolder(binding, contextMenuListener);
		}
	}

	@Override
	public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
		AppItem item = getItem(position);
		holder.onBind(item, loadIcon(item));
		holder.itemView.setActivated(selectedIds.contains(item.getId()));
	}

	/** Called when the set of selected games changes. */
	interface SelectionListener {
		void onSelectionChanged(int count);
	}

	void setSelectionListener(SelectionListener listener) {
		selectionListener = listener;
	}

	boolean isSelectionMode() {
		return selectionMode;
	}

	/** Enters multi-select mode with one game selected. */
	void startSelection(AppItem item) {
		selectionMode = true;
		selectedIds.clear();
		selectedIds.add(item.getId());
		refreshSelection();
	}

	/** Leaves multi-select mode. */
	void clearSelection() {
		if (!selectionMode && selectedIds.isEmpty()) {
			return;
		}
		selectionMode = false;
		selectedIds.clear();
		notifyItemRangeChanged(0, getItemCount());
	}

	/** Selects every game that is shown, or none if all of them are selected already. */
	void toggleSelectAll() {
		List<AppItem> shown = getCurrentList();
		boolean all = !shown.isEmpty();
		for (AppItem item : shown) {
			if (!selectedIds.contains(item.getId())) {
				all = false;
				break;
			}
		}
		if (all) {
			selectedIds.clear();
		} else {
			for (AppItem item : shown) {
				selectedIds.add(item.getId());
			}
		}
		refreshSelection();
	}

	List<AppItem> getSelectedItems() {
		ArrayList<AppItem> result = new ArrayList<>();
		for (AppItem item : list) {
			if (selectedIds.contains(item.getId())) {
				result.add(item);
			}
		}
		return result;
	}

	/** First letter of the title at this position, for the fast scroll bar. */
	String getSectionLetter(int position) {
		List<AppItem> shown = getCurrentList();
		if (position < 0 || position >= shown.size()) {
			return "";
		}
		String title = shown.get(position).getTitle();
		if (title == null) {
			return "";
		}
		title = title.trim();
		if (title.isEmpty()) {
			return "";
		}
		int codePoint = title.codePointAt(0);
		if (!Character.isLetterOrDigit(codePoint)) {
			return "#";
		}
		return new String(Character.toChars(codePoint)).toUpperCase(Locale.getDefault());
	}

	/**
	 * Handles a tap while in multi-select mode.
	 *
	 * @return false if not in that mode, the tap then starts the game
	 */
	boolean onItemClick(AppItem item, int position) {
		if (!selectionMode) {
			return false;
		}
		if (!selectedIds.remove(item.getId())) {
			selectedIds.add(item.getId());
		}
		if (position >= 0 && position < getItemCount()) {
			notifyItemChanged(position);
		}
		if (selectionListener != null) {
			selectionListener.onSelectionChanged(selectedIds.size());
		}
		return true;
	}

	private void refreshSelection() {
		notifyItemRangeChanged(0, getItemCount());
		if (selectionListener != null) {
			selectionListener.onSelectionChanged(selectedIds.size());
		}
	}

	private Bitmap loadIcon(AppItem item) {
		String path = item.getImagePathExt();
		if (path == null) {
			return null;
		}
		Bitmap bitmap = iconCache.get(path);
		if (bitmap == null) {
			bitmap = BitmapFactory.decodeFile(path);
			if (bitmap != null) {
				iconCache.put(path, bitmap);
			}
		}
		return bitmap;
	}

	void setItems(List<AppItem> items) {
		list = items == null ? new ArrayList<>() : items;
		if (!selectedIds.isEmpty()) {
			// Forget selected games that no longer exist.
			Set<Integer> present = new HashSet<>();
			for (AppItem item : list) {
				present.add(item.getId());
			}
			if (selectedIds.retainAll(present) && selectionListener != null) {
				selectionListener.onSelectionChanged(selectedIds.size());
			}
		}
		// A reinstalled game keeps its icon path but may have a new icon.
		iconCache.evictAll();
		appFilter.filter(filterConstraint);
	}

	/** Frees the cached icons; called when the list view is destroyed. */
	void clearIconCache() {
		iconCache.evictAll();
	}

	/**
	 * Prepares text for case-insensitive search. The root locale and the dotless/dotted
	 * "i" handling make "fifa" match "FIFA" on devices set to Turkish as well.
	 */
	static String normalize(CharSequence text) {
		if (text == null) {
			return "";
		}
		return text.toString()
				.toLowerCase(Locale.ROOT)
				.replace('\u0131', 'i')
				.replace("\u0307", "");
	}

	@Override
	public Filter getFilter() {
		return appFilter;
	}

	/** Called whenever the list becomes empty or non-empty. */
	interface EmptyStateListener {
		/**
		 * @param shown number of games shown in the list
		 * @param total number of games installed (larger than {@code shown} while searching)
		 * @param query the current search query
		 */
		void onEmptyStateChanged(int shown, int total, CharSequence query);
	}

	void setEmptyStateListener(EmptyStateListener listener) {
		this.emptyStateListener = listener;
	}

	void setLayout(int layout) {
		this.layout = layout;
	}

	static class AppViewHolder extends RecyclerView.ViewHolder {
		private final ImageView icon;
		private final TextView name;
		private final View favorite;

		AppViewHolder(ListRowGridJarBinding binding, View.OnCreateContextMenuListener contextMenuListener) {
			this(binding.getRoot(), binding.listTitle, binding.listImage, binding.listFavorite,
					contextMenuListener);
		}

		AppViewHolder(View itemView,
					  TextView listTitle,
					  ImageView listImage,
					  View listFavorite,
					  View.OnCreateContextMenuListener contextMenuListener) {
			super(itemView);
			favorite = listFavorite;
			icon = listImage;
			name = listTitle;

			itemView.setOnClickListener(v -> {
				// The bound item is kept in the tag; a position can be stale during list updates.
				Object tag = v.getTag();
				if (tag instanceof AppItem) {
					AppItem item = (AppItem) tag;
					RecyclerView.Adapter<?> adapter = getBindingAdapter();
					if (adapter instanceof AppsListAdapter && ((AppsListAdapter) adapter)
							.onItemClick(item, getBindingAdapterPosition())) {
						return;
					}
					Config.startApp(v.getContext(), item.getTitle(), item.getPathExt(), false);
				}
			});
			itemView.setOnCreateContextMenuListener(contextMenuListener);
		}

		void onBind(AppItem item, Bitmap iconBitmap) {
			if (iconBitmap != null) {
				BitmapDrawable icon = new BitmapDrawable(itemView.getResources(), iconBitmap);
				icon.setFilterBitmap(false);
				this.icon.setImageDrawable(icon);
			} else {
				this.icon.setImageResource(R.mipmap.ic_launcher);
			}
			name.setText(item.getTitle());
			favorite.setVisibility(item.isFavorite() ? View.VISIBLE : View.GONE);
			itemView.setTag(item);
		}
	}

	static class AppListViewHolder extends AppViewHolder {
		private final TextView author;
		private final TextView version;

		AppListViewHolder(ListRowJarBinding binding, View.OnCreateContextMenuListener contextMenuListener) {
			super(binding.getRoot(), binding.listTitle, binding.listImage, binding.listFavorite,
					contextMenuListener);
			author = binding.listAuthor;
			version = binding.listVersion;
		}

		@Override
		void onBind(AppItem item, Bitmap iconBitmap) {
			super.onBind(item, iconBitmap);
			author.setText(item.getAuthor());
			version.setText(item.getVersion());
		}
	}

	class AppFilter extends Filter {

		@Override
		protected FilterResults performFiltering(CharSequence constraint) {
			FilterResults results = new FilterResults();
			// Read the field once: it can be replaced on the main thread meanwhile.
			List<AppItem> list = AppsListAdapter.this.list;
			if (isEmpty(constraint)) {
				results.count = list.size();
				results.values = list;
			} else {
				String query = normalize(constraint).trim();
				ArrayList<AppItem> resultList = new ArrayList<>();
				for (AppItem item : list) {
					if (normalize(item.getTitle()).contains(query) ||
							normalize(item.getAuthor()).contains(query)) {
						resultList.add(item);
					}
				}
				results.count = resultList.size();
				results.values = resultList;
			}
			return results;
		}

		boolean isEmpty(CharSequence constraint) {
			return constraint == null || constraint.toString().trim().length() == 0;
		}

		@Override
		protected void publishResults(CharSequence constraint, FilterResults results) {
			filterConstraint = constraint;
			//noinspection unchecked
			submitList((List<AppItem>) results.values);
			if (emptyStateListener != null) {
				emptyStateListener.onEmptyStateChanged(results.count, list.size(), constraint);
			}
		}
	}
}
