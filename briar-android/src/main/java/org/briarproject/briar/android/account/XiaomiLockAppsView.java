package org.briarproject.briar.android.account;

import android.content.ActivityNotFoundException;
import android.content.Context;
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
import static org.briarproject.android.dontkillmelib.XiaomiUtils.getXiaomiLockAppsIntent;
import static org.briarproject.android.dontkillmelib.XiaomiUtils.xiaomiLockAppsNeedsToBeShown;
import static org.briarproject.bramble.util.LogUtils.logException;

@UiThread
@NotNullByDefault
class XiaomiLockAppsView extends PowerView {

	private static final Logger LOG =
			getLogger(XiaomiLockAppsView.class.getName());

	public XiaomiLockAppsView(Context context) {
		this(context, null);
	}

	public XiaomiLockAppsView(Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public XiaomiLockAppsView(Context context, @Nullable AttributeSet attrs,
			int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		setText(R.string.dnkm_xiaomi_lock_apps_text);
		setButtonText(R.string.dnkm_xiaomi_lock_apps_button);
	}

	@Override
	public boolean needsToBeShown() {
		return xiaomiLockAppsNeedsToBeShown(getContext());
	}

	@Override
	@StringRes
	protected int getHelpText() {
		return R.string.dnkm_xiaomi_lock_apps_help;
	}

	@Override
	protected void onButtonClick() {
		try {
			getContext().startActivity(getXiaomiLockAppsIntent());
			setChecked(true);
			return;
		} catch (SecurityException | ActivityNotFoundException e) {
			logException(LOG, WARNING, e);
			Toast.makeText(getContext(),
					R.string.dnkm_xiaomi_lock_apps_error_toast,
					LENGTH_LONG).show();
		}
		// Let the user continue with setup
		setChecked(true);
	}
}
