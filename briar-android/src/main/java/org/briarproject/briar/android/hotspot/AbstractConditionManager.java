package org.briarproject.briar.android.hotspot;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.briarproject.briar.R;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.StringRes;
import androidx.core.util.Consumer;
import androidx.fragment.app.FragmentActivity;

import static android.content.Context.WIFI_SERVICE;
import static android.widget.Toast.LENGTH_LONG;

/**
 * Abstract base class for the ConditionManagers that ensure that the conditions
 * to open a hotspot are fulfilled. There are different extensions of this for
 * API levels lower than 29, 29+ and 33+.
 */
abstract class AbstractConditionManager {

	/**
	 * Consumes false, if permissions have been denied. Then we don't call
	 * {@link HotspotIntroFragment#startHotspotIfConditionsFulfilled()},
	 * which would result in the same permission being requested again
	 * immediately.
	 */
	final Consumer<Boolean> permissionUpdateCallback;
	protected FragmentActivity ctx;
	WifiManager wifiManager;
	private ActivityResultLauncher<Intent> wifiRequest;

	AbstractConditionManager(Consumer<Boolean> permissionUpdateCallback) {
		this.permissionUpdateCallback = permissionUpdateCallback;
	}

	/**
	 * Pass a FragmentActivity context here during `onCreateView()`.
	 */
	void init(FragmentActivity ctx) {
		this.ctx = ctx;
		wifiManager = (WifiManager) ctx.getApplicationContext()
				.getSystemService(WIFI_SERVICE);
		wifiRequest = ctx.registerForActivityResult(
				new ActivityResultContracts.StartActivityForResult(),
				result -> permissionUpdateCallback
						.accept(wifiManager.isWifiEnabled()));
	}

	/**
	 * Call this during onStart() in the fragment where the ConditionManager
	 * is used.
	 */
	abstract void onStart();

	/**
	 * Check if all required conditions are met such that the hotspot can be
	 * started. If any precondition is not met yet, bring up relevant dialogs
	 * asking the user to grant relevant permissions or take relevant actions.
	 *
	 * @return true if conditions are fulfilled and flow can continue.
	 */
	abstract boolean checkAndRequestConditions();

	abstract String getWifiSettingsAction();

	void showRationale(Context ctx, @StringRes int title,
			@StringRes int body, Runnable onContinueClicked,
			Runnable onDismiss) {
		MaterialAlertDialogBuilder builder =
				new MaterialAlertDialogBuilder(ctx);
		builder.setTitle(title);
		builder.setMessage(body);
		builder.setNeutralButton(R.string.continue_button,
				(dialog, which) -> onContinueClicked.run());
		builder.setOnDismissListener(dialog -> onDismiss.run());
		builder.show();
	}

	void requestEnableWiFi() {
		try {
			wifiRequest.launch(new Intent(getWifiSettingsAction()));
		} catch (ActivityNotFoundException e) {
			Toast.makeText(ctx, R.string.error_start_activity, LENGTH_LONG)
					.show();
		}
	}

}
