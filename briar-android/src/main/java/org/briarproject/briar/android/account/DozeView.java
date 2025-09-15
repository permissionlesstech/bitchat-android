package org.briarproject.briar.android.account;


import android.content.Context;
import android.util.AttributeSet;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import static org.briarproject.android.dontkillmelib.DozeUtils.needsDozeWhitelisting;

@UiThread
@NotNullByDefault
class DozeView extends PowerView {

	@Nullable
	private Runnable onButtonClickListener;

	public DozeView(Context context) {
		this(context, null);
	}

	public DozeView(Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public DozeView(Context context, @Nullable AttributeSet attrs,
			int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		setText(R.string.dnkm_doze_intro);
		setButtonText(R.string.dnkm_doze_button);
	}

	@Override
	public boolean needsToBeShown() {
		return needsDozeWhitelisting(getContext());
	}

	@Override
	protected int getHelpText() {
		return R.string.dnkm_doze_explanation;
	}

	@Override
	protected void onButtonClick() {
		if (onButtonClickListener == null) throw new IllegalStateException();
		onButtonClickListener.run();
	}

	void setOnButtonClickListener(Runnable runnable) {
		onButtonClickListener = runnable;
	}

}
