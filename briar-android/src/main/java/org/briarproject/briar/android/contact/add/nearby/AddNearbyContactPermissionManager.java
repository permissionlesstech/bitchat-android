package org.briarproject.briar.android.contact.add.nearby;

import android.content.Context;

import org.briarproject.briar.R;
import org.briarproject.briar.android.util.Permission;

import java.util.Map;

import androidx.core.util.Consumer;
import androidx.fragment.app.FragmentActivity;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.BLUETOOTH_ADVERTISE;
import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_SCAN;
import static android.Manifest.permission.CAMERA;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale;
import static androidx.core.content.ContextCompat.checkSelfPermission;
import static org.briarproject.briar.android.util.Permission.GRANTED;
import static org.briarproject.briar.android.util.Permission.PERMANENTLY_DENIED;
import static org.briarproject.briar.android.util.Permission.SHOW_RATIONALE;
import static org.briarproject.briar.android.util.Permission.UNKNOWN;
import static org.briarproject.briar.android.util.PermissionUtils.areBluetoothPermissionsGranted;
import static org.briarproject.briar.android.util.PermissionUtils.gotPermission;
import static org.briarproject.briar.android.util.PermissionUtils.isLocationEnabledForBt;
import static org.briarproject.briar.android.util.PermissionUtils.showDenialDialog;
import static org.briarproject.briar.android.util.PermissionUtils.showLocationDialog;
import static org.briarproject.briar.android.util.PermissionUtils.showRationale;
import static org.briarproject.briar.android.util.PermissionUtils.wasGrantedBluetoothPermissions;

class AddNearbyContactPermissionManager {

	private Permission cameraPermission = UNKNOWN;
	private Permission locationPermission = SDK_INT < 31 ? UNKNOWN : GRANTED;
	private Permission bluetoothPermissions = SDK_INT < 31 ? GRANTED : UNKNOWN;

	private final FragmentActivity ctx;
	private final Consumer<String[]> requestPermissions;
	private final boolean isBluetoothSupported;

	AddNearbyContactPermissionManager(FragmentActivity ctx,
			Consumer<String[]> requestPermissions,
			boolean isBluetoothSupported) {
		this.ctx = ctx;
		this.requestPermissions = requestPermissions;
		this.isBluetoothSupported = isBluetoothSupported;
	}

	void resetPermissions() {
		cameraPermission = UNKNOWN;
		locationPermission = SDK_INT < 31 ? UNKNOWN : GRANTED;
		bluetoothPermissions = SDK_INT < 31 ? GRANTED : UNKNOWN;
	}

	static boolean areEssentialPermissionsGranted(Context ctx,
			boolean isBluetoothSupported) {
		int ok = PERMISSION_GRANTED;
		boolean bluetoothOk;
		if (!isBluetoothSupported || SDK_INT < 23) {
			bluetoothOk = true;
		} else if (SDK_INT < 31) {
			bluetoothOk = checkSelfPermission(ctx, ACCESS_FINE_LOCATION) == ok;
		} else {
			bluetoothOk = areBluetoothPermissionsGranted(ctx);
		}
		return bluetoothOk && checkSelfPermission(ctx, CAMERA) == ok;
	}

	private boolean areEssentialPermissionsGranted() {
		boolean bluetoothGranted = locationPermission == GRANTED &&
				bluetoothPermissions == GRANTED;
		return cameraPermission == GRANTED &&
				(SDK_INT < 23 || !isBluetoothSupported || bluetoothGranted);
	}

	boolean checkPermissions() {
		boolean locationEnabled = isLocationEnabledForBt(ctx);
		if (locationEnabled && areEssentialPermissionsGranted()) return true;
		// If an essential permission has been permanently denied, ask the
		// user to change the setting
		if (cameraPermission == PERMANENTLY_DENIED) {
			showDenialDialog(ctx, R.string.permission_camera_title,
					R.string.permission_camera_denied_body);
			return false;
		}
		if (isBluetoothSupported && locationPermission == PERMANENTLY_DENIED) {
			showDenialDialog(ctx, R.string.permission_location_title,
					R.string.permission_location_denied_body);
			return false;
		}
		if (isBluetoothSupported &&
				bluetoothPermissions == PERMANENTLY_DENIED) {
			showDenialDialog(ctx, R.string.permission_bluetooth_title,
					R.string.permission_bluetooth_denied_body);
			return false;
		}
		// Should we show the rationale for one or both permissions?
		if (cameraPermission == SHOW_RATIONALE &&
				locationPermission == SHOW_RATIONALE) {
			showRationale(ctx, R.string.permission_camera_location_title,
					R.string.permission_camera_location_request_body,
					this::requestPermissions);
		} else if (cameraPermission == SHOW_RATIONALE &&
				bluetoothPermissions == SHOW_RATIONALE) {
			showRationale(ctx, R.string.permission_camera_bluetooth_title,
					R.string.permission_camera_bluetooth_request_body,
					this::requestPermissions);
		} else if (cameraPermission == SHOW_RATIONALE) {
			showRationale(ctx, R.string.permission_camera_title,
					R.string.permission_camera_request_body,
					this::requestPermissions);
		} else if (locationPermission == SHOW_RATIONALE) {
			showRationale(ctx, R.string.permission_location_title,
					R.string.permission_location_request_body,
					this::requestPermissions);
		} else if (bluetoothPermissions == SHOW_RATIONALE) {
			showRationale(ctx, R.string.permission_bluetooth_title,
					R.string.permission_bluetooth_body,
					this::requestPermissions);
		} else if (locationEnabled) {
			requestPermissions();
		} else {
			showLocationDialog(ctx);
		}
		return false;
	}

	private void requestPermissions() {
		String[] permissions;
		if (isBluetoothSupported) {
			if (SDK_INT < 31) {
				permissions = new String[] {CAMERA, ACCESS_FINE_LOCATION};
			} else {
				permissions = new String[] {CAMERA, BLUETOOTH_ADVERTISE,
						BLUETOOTH_CONNECT, BLUETOOTH_SCAN};
			}
		} else {
			permissions = new String[] {CAMERA};
		}
		requestPermissions.accept(permissions);
	}

	void onRequestPermissionResult(Map<String, Boolean> result) {
		if (gotPermission(ctx, result, CAMERA)) {
			cameraPermission = GRANTED;
		} else if (shouldShowRationale(CAMERA)) {
			cameraPermission = SHOW_RATIONALE;
		} else {
			cameraPermission = PERMANENTLY_DENIED;
		}
		if (isBluetoothSupported) {
			if (SDK_INT < 31) {
				if (gotPermission(ctx, result, ACCESS_FINE_LOCATION)) {
					locationPermission = GRANTED;
				} else if (shouldShowRationale(ACCESS_FINE_LOCATION)) {
					locationPermission = SHOW_RATIONALE;
				} else {
					locationPermission = PERMANENTLY_DENIED;
				}
			} else {
				if (wasGrantedBluetoothPermissions(ctx, result)) {
					bluetoothPermissions = GRANTED;
				} else if (shouldShowRationale(BLUETOOTH_CONNECT)) {
					bluetoothPermissions = SHOW_RATIONALE;
				} else {
					bluetoothPermissions = PERMANENTLY_DENIED;
				}
			}
		}
	}

	private boolean shouldShowRationale(String permission) {
		return shouldShowRequestPermissionRationale(ctx, permission);
	}

}
