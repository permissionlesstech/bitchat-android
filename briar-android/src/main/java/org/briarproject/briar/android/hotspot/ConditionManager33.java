package org.briarproject.briar.android.hotspot;

import android.provider.Settings;

import org.briarproject.briar.R;
import org.briarproject.briar.android.util.Permission;
import org.briarproject.briar.android.util.PermissionUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.logging.Logger;

import androidx.activity.result.ActivityResultCaller;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.util.Consumer;

import static android.Manifest.permission.NEARBY_WIFI_DEVICES;
import static androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale;
import static java.lang.Boolean.TRUE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Logger.getLogger;

/**
 * This class ensures that the conditions to open a hotspot are fulfilled on
 * API levels >= 33.
 * <p>
 * As soon as {@link #checkAndRequestConditions()} returns true,
 * all conditions are fulfilled.
 */
@RequiresApi(33)
@NotNullByDefault
class ConditionManager33 extends AbstractConditionManager {

	private static final Logger LOG =
			getLogger(ConditionManager33.class.getName());

	private Permission nearbyWifiPermission = Permission.UNKNOWN;

	private final ActivityResultLauncher<String> nearbyWifiRequest;

	ConditionManager33(ActivityResultCaller arc,
			Consumer<Boolean> permissionUpdateCallback) {
		super(permissionUpdateCallback);
		// permissionUpdateCallback receives false if permissions were denied
		nearbyWifiRequest = arc.registerForActivityResult(
				new RequestPermission(), granted -> {
					onRequestPermissionResult(granted);
					permissionUpdateCallback.accept(TRUE.equals(granted));
				});
	}

	@Override
	void onStart() {
		nearbyWifiPermission = Permission.UNKNOWN;
	}

	private boolean areEssentialPermissionsGranted() {
		boolean isWifiEnabled = wifiManager.isWifiEnabled();
		if (LOG.isLoggable(INFO)) {
			LOG.info(String.format("areEssentialPermissionsGranted(): " +
							"nearbyWifiPermission? %s, " +
							"wifiManager.isWifiEnabled()? %b",
					nearbyWifiPermission, isWifiEnabled));
		}
		return nearbyWifiPermission == Permission.GRANTED && isWifiEnabled;
	}

	@Override
	boolean checkAndRequestConditions() {
		if (areEssentialPermissionsGranted()) return true;

		if (nearbyWifiPermission == Permission.UNKNOWN) {
			requestPermissions();
			return false;
		}

		// If the location permission has been permanently denied, ask the
		// user to change the setting
		if (nearbyWifiPermission == Permission.PERMANENTLY_DENIED) {
			PermissionUtils.showDenialDialog(ctx,
					R.string.permission_nearby_devices_title,
					R.string.permission_hotspot_nearby_wifi_denied_body,
					() -> permissionUpdateCallback.accept(false));
			return false;
		}

		// Should we show the rationale for location permission?
		if (nearbyWifiPermission == Permission.SHOW_RATIONALE) {
			showRationale(ctx,
					R.string.permission_location_title,
					R.string.permission_hotspot_nearby_wifi_request_body,
					this::requestPermissions,
					() -> permissionUpdateCallback.accept(false));
			return false;
		}

		// If Wifi is not enabled, we show the rationale for enabling Wifi?
		if (!wifiManager.isWifiEnabled()) {
			showRationale(ctx, R.string.wifi_settings_title,
					R.string.wifi_settings_request_enable_body,
					this::requestEnableWiFi,
					() -> permissionUpdateCallback.accept(false));
			return false;
		}

		// we shouldn't usually reach this point, but if we do, return false
		// anyway to force a recheck. Maybe some condition changed in the
		// meantime.
		return false;
	}

	@Override
	String getWifiSettingsAction() {
		return Settings.Panel.ACTION_WIFI;
	}

	private void onRequestPermissionResult(@Nullable Boolean granted) {
		if (granted != null && granted) {
			nearbyWifiPermission = Permission.GRANTED;
		} else if (shouldShowRequestPermissionRationale(ctx,
				NEARBY_WIFI_DEVICES)) {
			nearbyWifiPermission = Permission.SHOW_RATIONALE;
		} else {
			nearbyWifiPermission = Permission.PERMANENTLY_DENIED;
		}
	}

	private void requestPermissions() {
		nearbyWifiRequest.launch(NEARBY_WIFI_DEVICES);
	}

}
