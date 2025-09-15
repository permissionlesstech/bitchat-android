package org.briarproject.briar.android.contact.connect;

import android.app.Activity;
import android.widget.Toast;

import org.briarproject.briar.R;
import org.briarproject.briar.android.util.Permission;

import java.util.Map;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.fragment.app.FragmentActivity;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.os.Build.VERSION.SDK_INT;
import static android.widget.Toast.LENGTH_LONG;
import static androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale;
import static org.briarproject.briar.android.util.Permission.GRANTED;
import static org.briarproject.briar.android.util.Permission.PERMANENTLY_DENIED;
import static org.briarproject.briar.android.util.Permission.SHOW_RATIONALE;
import static org.briarproject.briar.android.util.Permission.UNKNOWN;
import static org.briarproject.briar.android.util.PermissionUtils.gotPermission;
import static org.briarproject.briar.android.util.PermissionUtils.isLocationEnabledForBt;
import static org.briarproject.briar.android.util.PermissionUtils.requestBluetoothPermissions;
import static org.briarproject.briar.android.util.PermissionUtils.showDenialDialog;
import static org.briarproject.briar.android.util.PermissionUtils.showLocationDialog;
import static org.briarproject.briar.android.util.PermissionUtils.showRationale;
import static org.briarproject.briar.android.util.PermissionUtils.wasGrantedBluetoothPermissions;

class BluetoothConditionManager {

	private Permission locationPermission = SDK_INT < 31 ? UNKNOWN : GRANTED;
	private Permission bluetoothPermissions = SDK_INT < 31 ? GRANTED : UNKNOWN;

	/**
	 * Call this when the using activity or fragment starts,
	 * because permissions might have changed while it was stopped.
	 */
	void reset() {
		locationPermission = SDK_INT < 31 ? UNKNOWN : GRANTED;
		bluetoothPermissions = SDK_INT < 31 ? GRANTED : UNKNOWN;
	}

	@UiThread
	void requestPermissions(ActivityResultLauncher<String[]> launcher) {
		if (SDK_INT < 31) {
			requestLocationPermission(launcher);
		} else {
			requestBluetoothPermissions(launcher);
		}
	}

	@UiThread
	void onLocationPermissionResult(Activity activity,
			@Nullable Map<String, Boolean> result) {
		if (SDK_INT < 31) {
			if (gotPermission(activity, result, ACCESS_FINE_LOCATION)) {
				locationPermission = GRANTED;
			} else if (shouldShowRequestPermissionRationale(activity,
					ACCESS_FINE_LOCATION)) {
				locationPermission = SHOW_RATIONALE;
			} else {
				locationPermission = PERMANENTLY_DENIED;
			}
		} else {
			if (wasGrantedBluetoothPermissions(activity, result)) {
				bluetoothPermissions = GRANTED;
			} else if (shouldShowRequestPermissionRationale(activity,
					BLUETOOTH_CONNECT)) {
				bluetoothPermissions = SHOW_RATIONALE;
			} else {
				bluetoothPermissions = PERMANENTLY_DENIED;
			}
		}
	}

	boolean areRequirementsFulfilled(FragmentActivity ctx,
			ActivityResultLauncher<String[]> permissionRequest,
			Runnable onLocationDenied) {
		boolean permissionGranted =
				(SDK_INT < 23 || locationPermission == GRANTED) &&
						bluetoothPermissions == GRANTED;
		boolean locationEnabled = isLocationEnabledForBt(ctx);
		if (permissionGranted && locationEnabled) return true;

		if (locationPermission == PERMANENTLY_DENIED) {
			showDenialDialog(ctx, R.string.permission_location_title,
					R.string.permission_location_denied_body, onLocationDenied);
		} else if (locationPermission == SHOW_RATIONALE) {
			showRationale(ctx, R.string.permission_location_title,
					R.string.permission_location_request_body,
					() -> requestLocationPermission(permissionRequest));
		} else if (!locationEnabled) {
			showLocationDialog(ctx);
		} else if (bluetoothPermissions == PERMANENTLY_DENIED) {
			Runnable onDenied = () -> Toast.makeText(ctx,
					R.string.connect_via_bluetooth_no_bluetooth_permission,
					LENGTH_LONG).show();
			showDenialDialog(ctx, R.string.permission_bluetooth_title,
					R.string.permission_bluetooth_denied_body, onDenied);
		} else if (bluetoothPermissions == SHOW_RATIONALE && SDK_INT >= 31) {
			// SDK_INT is checked to make linter happy, because
			// requestBluetoothPermissions() requires SDK_INT 31
			showRationale(ctx,
					R.string.permission_bluetooth_title,
					R.string.permission_bluetooth_body, () ->
							requestBluetoothPermissions(permissionRequest));
		}
		return false;
	}

	private void requestLocationPermission(
			ActivityResultLauncher<String[]> launcher) {
		launcher.launch(new String[] {ACCESS_FINE_LOCATION});
	}

}
