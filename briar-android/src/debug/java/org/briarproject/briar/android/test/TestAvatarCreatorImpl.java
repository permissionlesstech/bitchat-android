/*
 * Some code was taken from:
 *
 * RIG – Random Image Generator
 * https://github.com/stedi-akk/RandomImageGenerator
 * licenced under Apache2 license.
 */

package org.briarproject.briar.android.test;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import org.briarproject.briar.android.attachment.media.ImageCompressor;
import org.briarproject.briar.api.test.TestAvatarCreator;

import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

import javax.annotation.Nullable;
import javax.inject.Inject;

public class TestAvatarCreatorImpl implements TestAvatarCreator {
	private final int WIDTH = 800;
	private final int HEIGHT = 640;

	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final float[] hsv = new float[3];
	private final Random random = new Random();

	private final ImageCompressor imageCompressor;

	@Inject
	TestAvatarCreatorImpl(ImageCompressor imageCompressor) {
		this.imageCompressor = imageCompressor;
	}

	@Nullable
	@Override
	public InputStream getAvatarInputStream() throws IOException {
		Bitmap bitmap = generateBitmap();
		return imageCompressor.compressImage(bitmap);
	}

	private Bitmap generateBitmap() {
		// one pattern is boring, let's at least use two
		if (random.nextBoolean()) {
			return generateColoredPixels();
		} else {
			return generateColoredCircles();
		}
	}

	private Bitmap generateColoredPixels() {
		Bitmap bitmap = getBitmapWithRandomBackground();
		Canvas canvas = new Canvas(bitmap);
		Rect pixel = new Rect();
		int pixelMultiplier = random.nextInt(500) + 1;
		for (int x = 0; x < WIDTH; x += pixelMultiplier) {
			for (int y = 0; y < HEIGHT; y += pixelMultiplier) {
				pixel.set(x, y, x + pixelMultiplier, y + pixelMultiplier);
				paint.setColor(getRandomColor());
				canvas.drawRect(pixel, paint);
			}
		}
		return bitmap;
	}

	private Bitmap generateColoredCircles() {
		Bitmap bitmap = getBitmapWithRandomBackground();
		int biggestSide = Math.max(WIDTH, HEIGHT);
		int selectedCount = random.nextInt(10) + 2;
		Canvas canvas = new Canvas(bitmap);
		float radiusFrom = biggestSide / 12f;
		float radiusTo = biggestSide / 4f;
		for (int i = 0; i < selectedCount; i++) {
			float cx = random.nextInt(WIDTH);
			float cy = random.nextInt(HEIGHT);
			float radius =
					random.nextInt((int) (radiusTo - radiusFrom)) + radiusFrom;
			paint.setColor(getRandomColor());
			canvas.drawCircle(cx, cy, radius, paint);
		}
		return bitmap;
	}

	private Bitmap getBitmapWithRandomBackground() {
		Bitmap bitmap =
				Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
		bitmap.eraseColor(getRandomColor());
		return bitmap;
	}

	private int getRandomColor() {
		hsv[0] = random.nextInt(360);
		hsv[1] = random.nextFloat();
		hsv[2] = 1f;
		return Color.HSVToColor(hsv);
	}

}
