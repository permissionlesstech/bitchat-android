package org.briarproject.briar.android.login;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.util.AttributeSet;
import android.widget.ProgressBar;

import androidx.annotation.Nullable;

import static android.graphics.Color.BLACK;
import static android.graphics.Paint.Style.FILL;
import static android.graphics.Paint.Style.STROKE;
import static android.graphics.drawable.ClipDrawable.HORIZONTAL;
import static android.view.Gravity.LEFT;
import static android.view.Gravity.START;
import static org.briarproject.bramble.api.crypto.PasswordStrengthEstimator.QUITE_STRONG;
import static org.briarproject.bramble.api.crypto.PasswordStrengthEstimator.QUITE_WEAK;
import static org.briarproject.bramble.api.crypto.PasswordStrengthEstimator.STRONG;
import static org.briarproject.bramble.api.crypto.PasswordStrengthEstimator.WEAK;

public class StrengthMeter extends ProgressBar {

	private static final int MAX = 100;
	public static final int RED = Color.rgb(255, 0, 0);
	public static final int ORANGE = Color.rgb(255, 160, 0);
	public static final int YELLOW = Color.rgb(255, 255, 0);
	public static final int LIME = Color.rgb(180, 255, 0);
	public static final int GREEN = Color.rgb(0, 255, 0);

	private final ShapeDrawable bar;

	public StrengthMeter(Context context) {
		this(context, null);
	}

	public StrengthMeter(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs, android.R.attr.progressBarStyleHorizontal);
		bar = new ShapeDrawable();
		bar.getPaint().setColor(RED);
		ClipDrawable clip = new ClipDrawable(bar, LEFT & START, HORIZONTAL);
		ShapeDrawable background = new ShapeDrawable();
		Paint p = background.getPaint();
		p.setStyle(FILL);
		p.setColor(getResources().getColor(android.R.color.transparent));
		p.setStyle(STROKE);
		p.setStrokeWidth(1);
		p.setColor(BLACK);
		Drawable[] layers = new Drawable[] { clip, background };
		setProgressDrawable(new LayerDrawable(layers));
		setIndeterminate(false);
		if (isInEditMode()) setStrength(STRONG);
	}

	@Override
	public int getMax() {
		return MAX;
	}

	public int getColor() {
		return bar.getPaint().getColor();
	}

	public void setStrength(float strength) {
		if (strength < 0 || strength > 1) throw new IllegalArgumentException();
		int colour;
		if (strength < WEAK) colour = RED;
		else if (strength < QUITE_WEAK) colour = ORANGE;
		else if (strength < QUITE_STRONG) colour = YELLOW;
		else if (strength < STRONG) colour = LIME;
		else colour = GREEN;
		bar.getPaint().setColor(colour);
		setProgress((int) (strength * MAX));
	}
}
