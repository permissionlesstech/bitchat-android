package org.briarproject.briar.android.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import com.google.android.material.snackbar.Snackbar.SnackbarLayout;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.coordinatorlayout.widget.CoordinatorLayout.Behavior;
import androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams;

/**
 * This behavior makes room for a snackbar at the bottom of the screen. The
 * proper solution is to use layout_dodgeInsetEdges="bottom", but when used on
 * a scrollable view that results in the view being pushed under the app bar
 * (see https://issuetracker.google.com/issues/116541304).
 */
@NotNullByDefault
public class SnackbarAwareBehavior<V extends View> extends Behavior<V> {

	public SnackbarAwareBehavior(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	@Override
	public boolean onDependentViewChanged(CoordinatorLayout parent,
			V child, View snackbar) {
		setMargin(child, snackbar.getHeight());
		return true;
	}

	@Override
	public void onDependentViewRemoved(CoordinatorLayout parent,
			V child, View snackbar) {
		setMargin(child, 0);
	}

	@Override
	public boolean layoutDependsOn(CoordinatorLayout parent,
			V child, View dependency) {
		return dependency instanceof SnackbarLayout;
	}

	private void setMargin(V child, int margin) {
		LayoutParams params = (LayoutParams) child.getLayoutParams();
		params.setMargins(0, 0, 0, margin);
		child.setLayoutParams(params);
	}
}
