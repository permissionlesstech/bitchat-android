package org.briarproject.briar.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;

import java.util.Random;

import javax.annotation.Nullable;

import static android.content.Context.WINDOW_SERVICE;
import static android.provider.Settings.canDrawOverlays;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static androidx.test.internal.runner.junit4.statement.UiThreadStatement.runOnUiThread;
import static org.junit.Assert.assertTrue;

/**
 * A full-screen overlay used to make taps visible in instrumentation tests.
 */
public class OverlayView extends View {

	public static OverlayView attach(Context ctx) throws Throwable {
		assertTrue(canDrawOverlays(ctx));
		OverlayView view = new OverlayView(getApplicationContext());
		runOnUiThread(() -> attachInternal(ctx, view));
		return view;
	}

	private static void attachInternal(Context ctx, OverlayView view) {
		WindowManager.LayoutParams params = new WindowManager.LayoutParams(
				WindowManager.LayoutParams.MATCH_PARENT,
				WindowManager.LayoutParams.MATCH_PARENT,
				WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
				FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE,
				PixelFormat.TRANSLUCENT);
		WindowManager wm = (WindowManager) ctx.getSystemService(WINDOW_SERVICE);
		wm.addView(view, params);
	}

	private final Random random = new Random();
	private final Paint paint;
	private final int yOffset;
	@Nullable
	private float[] coordinates;

	public OverlayView(Context ctx) {
		super(ctx);
		int resourceId = getResources()
				.getIdentifier("status_bar_height", "dimen", "android");
		yOffset = getResources().getDimensionPixelSize(resourceId);
		paint = new Paint();
		paint.setAntiAlias(true);
		paint.setARGB(175, 255, 0, 0);
		setWillNotDraw(false);
	}

	@Override
	protected void onLayout(boolean changed, int l, int t, int r, int b) {
	}

	void tap(float[] coordinates) {
		this.coordinates = coordinates;
		invalidate();
		new Handler().postDelayed(this::untap, 750);
	}

	private void untap() {
		this.coordinates = null;
		invalidate();
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		if (coordinates == null) return;
		float x = coordinates[0] + random.nextInt(42);
		float y = coordinates[1] - yOffset + random.nextInt(13);
		canvas.drawCircle(x, y, 42, paint);
	}
}
