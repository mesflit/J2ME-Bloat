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

package ru.playsoftware.j2meloader.applist;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

/**
 * A scroll bar for long game lists whose thumb can be grabbed and dragged to jump
 * through the list quickly. It appears while the list scrolls and hides afterwards.
 */
public class FastScrollBar extends View {
	private static final long HIDE_DELAY_MS = 1500;
	private static final long FADE_DURATION_MS = 250;

	/** Tells which letter the list shows at a position. */
	public interface SectionProvider {
		String getSection(int position);
	}

	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final float touchWidth;
	private final float bubbleSize;
	private final float bubbleGap;
	private final int bubbleColor;
	private SectionProvider sectionProvider;
	private String section = "";
	private final RectF thumbRect = new RectF();
	private final float thumbWidth;
	private final float thumbWidthDragging;
	private final float minThumbHeight;
	private final float edgeMargin;
	private final int thumbColor;
	private final Runnable hideRunnable = () ->
			animate().alpha(0f).setDuration(FADE_DURATION_MS).start();
	private final RecyclerView.OnScrollListener scrollListener = new RecyclerView.OnScrollListener() {
		@Override
		public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
			updateThumb();
			if (dy != 0) {
				show();
			}
		}
	};

	private RecyclerView recyclerView;
	private boolean scrollable;
	private boolean dragging;
	private float thumbTop;
	private float thumbHeight;
	/** Distance from the top of the thumb to the finger that drags it. */
	private float dragOffset;

	public FastScrollBar(Context context) {
		this(context, null);
	}

	public FastScrollBar(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		float density = getResources().getDisplayMetrics().density;
		thumbWidth = 5 * density;
		thumbWidthDragging = 9 * density;
		minThumbHeight = 52 * density;
		edgeMargin = 4 * density;
		thumbColor = MaterialColors.getColor(context,
				com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF808080);
		touchWidth = 28 * density;
		bubbleSize = 56 * density;
		bubbleGap = 12 * density;
		bubbleColor = MaterialColors.getColor(context,
				com.google.android.material.R.attr.colorPrimaryContainer, 0xFFDFE0FF);
		textPaint.setColor(MaterialColors.getColor(context,
				com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF020865));
		textPaint.setTextAlign(Paint.Align.CENTER);
		textPaint.setTextSize(26 * density);
		textPaint.setFakeBoldText(true);
		setAlpha(0f);
	}

	/** Enables the letter bubble shown next to the thumb while it is dragged. */
	public void setSectionProvider(@Nullable SectionProvider provider) {
		sectionProvider = provider;
	}

	/** Connects the bar to the list it scrolls. */
	public void attach(RecyclerView list) {
		detach();
		recyclerView = list;
		list.addOnScrollListener(scrollListener);
	}

	public void detach() {
		removeCallbacks(hideRunnable);
		animate().cancel();
		if (recyclerView != null) {
			recyclerView.removeOnScrollListener(scrollListener);
			recyclerView = null;
		}
		dragging = false;
	}

	private void show() {
		removeCallbacks(hideRunnable);
		animate().cancel();
		setAlpha(1f);
		if (!dragging) {
			postDelayed(hideRunnable, HIDE_DELAY_MS);
		}
	}

	/** Places the thumb according to the scroll position of the list. */
	private void updateThumb() {
		RecyclerView list = recyclerView;
		int height = getHeight();
		if (list == null || height <= 0) {
			scrollable = false;
			return;
		}
		int extent = list.computeVerticalScrollExtent();
		int range = list.computeVerticalScrollRange();
		int scrollRange = range - extent;
		scrollable = scrollRange > 0 && range > 0;
		if (!scrollable) {
			invalidate();
			return;
		}
		thumbHeight = Math.min(height, Math.max(minThumbHeight, (float) height * extent / range));
		if (!dragging) {
			float fraction = (float) list.computeVerticalScrollOffset() / scrollRange;
			thumbTop = Math.max(0f, Math.min(1f, fraction)) * (height - thumbHeight);
		}
		invalidate();
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		updateThumb();
	}

	@Override
	protected void onDraw(@NonNull Canvas canvas) {
		if (!scrollable) {
			return;
		}
		float width = dragging ? thumbWidthDragging : thumbWidth;
		boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
		float left = rtl ? edgeMargin : getWidth() - edgeMargin - width;
		thumbRect.set(left, thumbTop, left + width, thumbTop + thumbHeight);
		paint.setColor(thumbColor);
		paint.setAlpha(dragging ? 0xE6 : 0x99);
		canvas.drawRoundRect(thumbRect, width / 2, width / 2, paint);
		if (dragging && !section.isEmpty()) {
			// The letter of the games at the current position, beside the thumb.
			float radius = bubbleSize / 2;
			float cx = rtl ? left + width + bubbleGap + radius : left - bubbleGap - radius;
			float cy = thumbTop + thumbHeight / 2;
			cy = Math.max(radius, Math.min(getHeight() - radius, cy));
			paint.setColor(bubbleColor);
			paint.setAlpha(0xFF);
			canvas.drawCircle(cx, cy, radius, paint);
			float baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2;
			canvas.drawText(section, cx, baseline, textPaint);
		}
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (recyclerView == null || !scrollable) {
			return false;
		}
		float y = event.getY();
		switch (event.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				// Only the visible thumb takes touches; everything else goes to the list.
				float x = event.getX();
				boolean onEdge = getLayoutDirection() == LAYOUT_DIRECTION_RTL
						? x <= touchWidth : x >= getWidth() - touchWidth;
				if (!onEdge || getAlpha() < 0.5f || y < thumbTop || y > thumbTop + thumbHeight) {
					return false;
				}
				dragging = true;
				dragOffset = y - thumbTop;
				recyclerView.stopScroll();
				ViewParent parent = getParent();
				if (parent != null) {
					parent.requestDisallowInterceptTouchEvent(true);
				}
				show();
				invalidate();
				return true;
			case MotionEvent.ACTION_MOVE:
				if (!dragging) {
					return false;
				}
				float track = getHeight() - thumbHeight;
				if (track <= 0) {
					return true;
				}
				thumbTop = Math.max(0f, Math.min(track, y - dragOffset));
				scrollListTo(thumbTop / track);
				invalidate();
				return true;
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
				if (!dragging) {
					return false;
				}
				dragging = false;
				section = "";
				updateThumb();
				show();
				return true;
			default:
				return dragging;
		}
	}

	/** Jumps to the part of the list given as a fraction of its length. */
	private void scrollListTo(float fraction) {
		RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
		RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
		if (adapter == null || layoutManager == null) {
			return;
		}
		int count = adapter.getItemCount();
		if (count == 0) {
			return;
		}
		int position = Math.round(fraction * (count - 1));
		if (sectionProvider != null) {
			String letter = sectionProvider.getSection(position);
			section = letter == null ? "" : letter;
		}
		if (layoutManager instanceof LinearLayoutManager) {
			((LinearLayoutManager) layoutManager).scrollToPositionWithOffset(position, 0);
		} else {
			layoutManager.scrollToPosition(position);
		}
	}

	@Override
	protected void onDetachedFromWindow() {
		removeCallbacks(hideRunnable);
		super.onDetachedFromWindow();
	}
}
