package org.briarproject.briar.android.account;


import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.widget.Toast;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.logging.Logger;

import javax.annotation.Nullable;

import androidx.annotation.StringRes;
import androidx.annotation.UiThread;

import static android.widget.Toast.LENGTH_LONG;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.android.dontkillmelib.HuaweiUtils.appLaunchNeedsToBeShown;
import static org.briarproject.android.dontkillmelib.HuaweiUtils.getHuaweiAppLaunchIntents;
import static org.briarproject.bramble.util.LogUtils.logException;

@UiThread
@NotNullByDefault
class HuaweiAppLaunchView extends PowerView {

	private static final Logger LOG =
			getLogger(HuaweiAppLaunchView.class.getName());

	public HuaweiAppLaunchView(Context context) {
		this(context, null);
	}

	public HuaweiAppLaunchView(Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public HuaweiAppLaunchView(Context context, @Nullable AttributeSet attrs,
			int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		setText(R.string.dnkm_huawei_app_launch_text);
		setButtonText(R.string.dnkm_huawei_app_launch_button);
	}

	@Override
	public boolean needsToBeShown() {
		return needsToBeShown(getContext());
	}

	public static boolean needsToBeShown(Context context) {
		return appLaunchNeedsToBeShown(context);
	}

	@Override
	@StringRes
	protected int getHelpText() {
		return R.string.dnkm_huawei_app_launch_help;
	}

	@Override
	protected void onButtonClick() {
		Context context = getContext();
		for (Intent i : getHuaweiAppLaunchIntents()) {
			try {
				context.startActivity(i);
				setChecked(true);
				return;
			} catch (Exception e) {
				logException(LOG, WARNING, e);
			}
		}
		Toast.makeText(context, R.string.dnkm_huawei_app_launch_error_toast,
				LENGTH_LONG).show();
		// Let the user continue with setup
		setChecked(true);
	}
}
