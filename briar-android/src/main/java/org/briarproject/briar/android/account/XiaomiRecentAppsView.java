package org.briarproject.briar.android.account;

import android.content.Context;
import android.util.AttributeSet;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import androidx.annotation.StringRes;
import androidx.annotation.UiThread;

import static org.briarproject.android.dontkillmelib.XiaomiUtils.isMiuiVersionAtLeast;
import static org.briarproject.android.dontkillmelib.XiaomiUtils.xiaomiRecentAppsNeedsToBeShown;
import static org.briarproject.briar.android.util.UiUtils.showOnboardingDialog;

@UiThread
@NotNullByDefault
class XiaomiRecentAppsView extends PowerView {

	public XiaomiRecentAppsView(Context context) {
		this(context, null);
	}

	public XiaomiRecentAppsView(Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public XiaomiRecentAppsView(Context context, @Nullable AttributeSet attrs,
			int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		setText(R.string.dnkm_xiaomi_text);
		setButtonText(R.string.dnkm_xiaomi_button);
	}

	@Override
	public boolean needsToBeShown() {
		return xiaomiRecentAppsNeedsToBeShown();
	}

	@Override
	@StringRes
	protected int getHelpText() {
		return R.string.dnkm_xiaomi_help;
	}

	@Override
	protected void onButtonClick() {
		int bodyRes = isMiuiVersionAtLeast(10, 0)
				? R.string.dnkm_xiaomi_dialog_body_new
				: R.string.dnkm_xiaomi_dialog_body_old;
		showOnboardingDialog(getContext(), getContext().getString(bodyRes));
		setChecked(true);
	}
}
