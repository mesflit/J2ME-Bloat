package javax.microedition.lcdui.graphics;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;

import javax.microedition.lcdui.Image;
import javax.microedition.util.ContextHolder;

public class CanvasWrapper {
	private static final float TEXT_SIZE_KEYBOARD = 22;

	/** Outline width of the virtual keys, in dp */
	private static final float STROKE_WIDTH_KEYBOARD = 1.5f;

	// Overlay shapes and labels are anti-aliased, otherwise round keys look jagged
	private final Paint drawPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint imgPaint = new Paint();
	private final Path arrowPath = new Path();
	private final float textSize;

	private float textAscent;
	private float textCenterOffset;
	private float textHeight;
	private Canvas canvas;

	public CanvasWrapper(boolean filterBitmap) {
		imgPaint.setFilterBitmap(filterBitmap);
		drawPaint.setStyle(Paint.Style.STROKE);
		fillPaint.setStyle(Paint.Style.FILL);

		Resources res = ContextHolder.getAppContext().getResources();
		drawPaint.setStrokeWidth(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
				STROKE_WIDTH_KEYBOARD, res.getDisplayMetrics()));
		arrowPaint.setStyle(Paint.Style.FILL_AND_STROKE);
		arrowPaint.setStrokeJoin(Paint.Join.ROUND);

		// init text paint
		Typeface typeface = Typeface.createFromAsset(res.getAssets(), "Roboto-Regular.ttf");
		textPaint.setTypeface(typeface);
		textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_KEYBOARD, res.getDisplayMetrics());
		textPaint.setTextSize(textSize);
		textPaint.setTextAlign(Paint.Align.CENTER);
		textAscent = textPaint.ascent();
		float descent = textPaint.descent();
		textHeight = descent - textAscent;
		textCenterOffset = ((descent + textAscent) / 2);
	}

	public void bind(Canvas canvas) {
		this.canvas = canvas;
	}

	public void clear(int color) {
		canvas.drawColor(color, PorterDuff.Mode.SRC);
	}

	public void drawArc(RectF oval, int startAngle, int sweepAngle) {
		canvas.drawArc(oval, startAngle, sweepAngle, false, drawPaint);
	}

	public void fillArc(RectF oval, int startAngle, int sweepAngle) {
		canvas.drawArc(oval, startAngle, sweepAngle, false, fillPaint);
	}

	public void drawRoundRect(RectF rect, int rx, int ry) {
		canvas.drawRoundRect(rect, rx, ry, drawPaint);
	}

	public void fillRoundRect(RectF rect, int rx, int ry) {
		canvas.drawRoundRect(rect, rx, ry, fillPaint);
	}

	public void drawString(String text, float x, float y) {
		canvas.drawText(text, x, y - textCenterOffset, textPaint);
	}

	public void drawImage(Image image, RectF dst) {
		Bitmap bitmap = image.getBitmap();
		bitmap.prepareToDraw();
		canvas.drawBitmap(bitmap, image.getBounds(), dst, imgPaint);
	}

	public void drawBitmap(Bitmap bitmap, Rect src, RectF dst) {
		bitmap.prepareToDraw();
		canvas.drawBitmap(bitmap, src, dst, imgPaint);
	}

	public void fillRect(RectF rect) {
		canvas.drawRect(rect, fillPaint);
	}

	public void drawRect(RectF rect) {
		canvas.drawRect(rect, drawPaint);
	}

	public void setDrawColor(int color) {
		drawPaint.setColor(color);
	}

	public void setFillColor(int color) {
		fillPaint.setColor(color);
	}

	public void setTextColor(int color) {
		textPaint.setColor(color);
		arrowPaint.setColor(color);
	}

	/**
	 * Draws a solid arrow head with rounded corners in the current text color.
	 *
	 * @param cx      center of the arrow
	 * @param cy      center of the arrow
	 * @param size    distance from the center to the tip
	 * @param degrees direction, clockwise: 0 - up, 90 - right, 180 - down, 270 - left
	 */
	public void fillArrow(float cx, float cy, float size, float degrees) {
		arrowPaint.setStrokeWidth(size * 0.35f);
		arrowPath.rewind();
		// the centroid of the triangle is in the center of the key
		arrowPath.moveTo(0, -size * 0.8f);
		arrowPath.lineTo(size * 0.8f, size * 0.4f);
		arrowPath.lineTo(-size * 0.8f, size * 0.4f);
		arrowPath.close();
		canvas.save();
		canvas.translate(cx, cy);
		canvas.rotate(degrees);
		canvas.drawPath(arrowPath, arrowPaint);
		canvas.restore();
	}

	public void drawBackgroundedText(String text) {
		float width = textPaint.measureText(text);
		canvas.drawRect(0, 0, width, textHeight, fillPaint);
		canvas.drawText(text, width / 2.0f, -textAscent, textPaint);
	}

	public float getTextHeight() {
		return textHeight;
	}

	public void setTextAlign(Paint.Align align) {
		textPaint.setTextAlign(align);
	}

	public void setTextScale(float scale) {
		textPaint.setTextSize(textSize * scale);
		textAscent = textPaint.ascent();
		float descent = textPaint.descent();
		textHeight = descent - textAscent;
		textCenterOffset = ((descent + textAscent) / 2);
	}
}
