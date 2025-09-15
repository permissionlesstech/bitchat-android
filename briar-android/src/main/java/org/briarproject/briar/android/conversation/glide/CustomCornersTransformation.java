package org.briarproject.briar.android.conversation.glide;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import org.briarproject.nullsafety.NotNullByDefault;

import java.security.MessageDigest;

import javax.annotation.concurrent.Immutable;

import androidx.annotation.NonNull;

import static android.graphics.Bitmap.Config.ARGB_8888;
import static android.graphics.Shader.TileMode.CLAMP;

@Immutable
@NotNullByDefault
class CustomCornersTransformation extends BitmapTransformation {

	private static final String ID =
			CustomCornersTransformation.class.getName();

	private final Radii radii;

	CustomCornersTransformation(Radii radii) {
		this.radii = radii;
	}

	@Override
	protected Bitmap transform(BitmapPool pool, Bitmap toTransform,
			int outWidth, int outHeight) {
		int width = toTransform.getWidth();
		int height = toTransform.getHeight();

		Bitmap bitmap = pool.get(width, height, ARGB_8888);
		bitmap.setHasAlpha(true);

		Canvas canvas = new Canvas(bitmap);
		Paint paint = new Paint();
		paint.setAntiAlias(true);
		paint.setShader(new BitmapShader(toTransform, CLAMP, CLAMP));
		drawRect(canvas, paint, width, height);
		return bitmap;
	}

	private void drawRect(Canvas canvas, Paint paint, float width,
			float height) {
		drawTopLeft(canvas, paint, radii.topLeft, width, height);
		drawTopRight(canvas, paint, radii.topRight, width, height);
		drawBottomLeft(canvas, paint, radii.bottomLeft, width, height);
		drawBottomRight(canvas, paint, radii.bottomRight, width, height);
	}

	private void drawTopLeft(Canvas canvas, Paint paint, int radius,
			float width, float height) {
		RectF rect = new RectF(
				0,
				0,
				width / 2 + radius + 1,
				height / 2 + radius + 1
		);
		if (radius == 0) canvas.drawRect(rect, paint);
		else canvas.drawRoundRect(rect, radius, radius, paint);
	}

	private void drawTopRight(Canvas canvas, Paint paint, int radius,
			float width, float height) {
		RectF rect = new RectF(
				width / 2 - radius,
				0,
				width,
				height / 2 + radius + 1
		);
		if (radius == 0) canvas.drawRect(rect, paint);
		else canvas.drawRoundRect(rect, radius, radius, paint);
	}

	private void drawBottomLeft(Canvas canvas, Paint paint, int radius,
			float width, float height) {
		RectF rect = new RectF(
				0,
				height / 2 - radius,
				width / 2 + radius + 1,
				height
		);
		if (radius == 0) canvas.drawRect(rect, paint);
		else canvas.drawRoundRect(rect, radius, radius, paint);
	}

	private void drawBottomRight(Canvas canvas, Paint paint, int radius,
			float width, float height) {
		RectF rect = new RectF(
				width / 2 - radius,
				height / 2 - radius,
				width,
				height
		);
		if (radius == 0) canvas.drawRect(rect, paint);
		else canvas.drawRoundRect(rect, radius, radius, paint);
	}

	@Override
	public String toString() {
		return "ImageCornerTransformation(" + radii + ")";
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof CustomCornersTransformation &&
				radii.equals(((CustomCornersTransformation) o).radii);
	}

	@Override
	public int hashCode() {
		return ID.hashCode() + radii.hashCode();
	}

	@Override
	public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
		messageDigest.update((ID + radii).getBytes(CHARSET));
	}

}
