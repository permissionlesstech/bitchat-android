package org.briarproject.briar.android.account;


import android.content.Context;
import android.util.AttributeSet;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import androidx.annotation.StringRes;
import androidx.annotation.UiThread;

import static org.briarproject.android.dontkillmelib.HuaweiUtils.getHuaweiProtectedAppsIntent;
import static org.briarproject.android.dontkillmelib.HuaweiUtils.protectedAppsNeedsToBeShown;
import static org.briarproject.briar.android.util.UiUtils.tryToStartActivity;

@UiThread
@NotNullByDefault
class HuaweiProtectedAppsView extends PowerView {

	public HuaweiProtectedAppsView(Context context) {
		this(context, null);
	}

	public HuaweiProtectedAppsView(Context context,
			@Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public HuaweiProtectedAppsView(Context context,
			@Nullable AttributeSet attrs,
			int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		setText(R.string.dnkm_huawei_protected_text);
		setButtonText(R.string.dnkm_huawei_protected_button);
	}

	@Override
	public boolean needsToBeShown() {
		return protectedAppsNeedsToBeShown(getContext());
	}

	@Override
	@StringRes
	protected int getHelpText() {
		return R.string.dnkm_huawei_protected_help;
	}

	@Override
	protected void onButtonClick() {
		tryToStartActivity(getContext(), getHuaweiProtectedAppsIntent());
		setChecked(true);
	}
}
