package org.briarproject.briar.android.util;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.location.LocationManager;
import android.net.Uri;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.Map;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.fragment.app.FragmentActivity;

import static android.Manifest.permission.BLUETOOTH_ADVERTISE;
import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_SCAN;
import static android.content.Intent.CATEGORY_DEFAULT;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS;
import static androidx.core.content.ContextCompat.checkSelfPermission;
import static java.lang.Boolean.TRUE;
import static org.briarproject.briar.BuildConfig.APPLICATION_ID;
import static org.briarproject.briar.android.util.UiUtils.tryToStartActivity;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class PermissionUtils {

	public static boolean gotPermission(Context ctx,
			@Nullable Map<String, Boolean> grantedMap, String permission) {
		if (grantedMap == null || !grantedMap.containsKey(permission)) {
			return isPermissionGranted(ctx, permission);
		} else {
			return TRUE.equals(grantedMap.get(permission));
		}
	}

	private static boolean isPermissionGranted(Context ctx, String permission) {
		return checkSelfPermission(ctx, permission) ==
				PERMISSION_GRANTED;
	}

	public static boolean areBluetoothPermissionsGranted(Context ctx) {
		if (SDK_INT < 31) return true;
		return isPermissionGranted(ctx, BLUETOOTH_ADVERTISE) &&
				isPermissionGranted(ctx, BLUETOOTH_CONNECT) &&
				isPermissionGranted(ctx, BLUETOOTH_SCAN);
	}

	@RequiresApi(31)
	public static boolean wasGrantedBluetoothPermissions(Context ctx,
			@Nullable Map<String, Boolean> grantedMap) {
		return grantedMap != null &&
				gotPermission(ctx, grantedMap, BLUETOOTH_ADVERTISE) &&
				gotPermission(ctx, grantedMap, BLUETOOTH_CONNECT) &&
				gotPermission(ctx, grantedMap, BLUETOOTH_SCAN);
	}

	private static DialogInterface.OnClickListener getGoToSettingsListener(
			Context context) {
		return (dialog, which) -> {
			Intent i = new Intent();
			i.setAction("android.settings.APPLICATION_DETAILS_SETTINGS");
			i.addCategory(CATEGORY_DEFAULT);
			i.setData(Uri.parse("package:" + APPLICATION_ID));
			i.addFlags(FLAG_ACTIVITY_NEW_TASK);
			tryToStartActivity(context, i);
		};
	}

	/**
	 * @return true if location is enabled for Bluetooth purposes,
	 * or it isn't required due to this being a device with SDK < 28 or >= 31.
	 */
	public static boolean isLocationEnabledForBt(Context ctx) {
		if (SDK_INT >= 28 && SDK_INT < 31) {
			LocationManager lm = ctx.getSystemService(LocationManager.class);
			return lm.isLocationEnabled();
		} else {
			return true;
		}
	}

	/**
	 * @return true if location is enabled for Wi-Fi hotspot purposes,
	 * or it isn't required due to this being a device with SDK < 31.
	 */
	public static boolean isLocationEnabledForWiFi(Context ctx) {
		if (SDK_INT >= 31) {
			LocationManager lm = ctx.getSystemService(LocationManager.class);
			return lm.isLocationEnabled();
		}
		return true;
	}

	public static void showLocationDialog(Context ctx) {
		showLocationDialog(ctx, true);
	}

	public static void showLocationDialog(Context ctx, boolean forBluetooth) {
		MaterialAlertDialogBuilder builder =
				new MaterialAlertDialogBuilder(ctx, R.style.BriarDialogTheme);
		builder.setTitle(R.string.permission_location_setting_title);
		if (forBluetooth) {
			builder.setMessage(R.string.permission_location_setting_body);
		} else {
			builder.setMessage(
					R.string.permission_location_setting_hotspot_body);
		}
		builder.setNegativeButton(R.string.cancel, null);
		builder.setPositiveButton(R.string.permission_location_setting_button,
				(dialog, which) -> {
					Intent i = new Intent(ACTION_LOCATION_SOURCE_SETTINGS);
					tryToStartActivity(ctx, i);
				});
		builder.show();
	}

	/**
	 * This closes the given activity when dialog gets cancelled.
	 */
	public static void showDenialDialog(FragmentActivity ctx,
			@StringRes int title, @StringRes int body) {
		showDenialDialog(ctx, title, body, ctx::supportFinishAfterTransition);
	}

	public static void showDenialDialog(FragmentActivity ctx,
			@StringRes int title, @StringRes int body, Runnable onDenied) {
		MaterialAlertDialogBuilder builder =
				new MaterialAlertDialogBuilder(ctx, R.style.BriarDialogTheme);
		builder.setTitle(title);
		builder.setMessage(body);
		builder.setPositiveButton(R.string.ok, getGoToSettingsListener(ctx));
		builder.setNegativeButton(R.string.cancel, (dialog, which) ->
				onDenied.run());
		builder.show();
	}

	public static void showRationale(FragmentActivity ctx, @StringRes int title,
			@StringRes int body, @Nullable Runnable onOk) {
		MaterialAlertDialogBuilder builder =
				new MaterialAlertDialogBuilder(ctx, R.style.BriarDialogTheme);
		builder.setTitle(title);
		builder.setMessage(body);
		builder.setNeutralButton(R.string.continue_button, (dialog, which) -> {
			if (onOk != null) onOk.run();
			dialog.dismiss();
		});
		builder.show();
	}

	@RequiresApi(31)
	public static void requestBluetoothPermissions(
			ActivityResultLauncher<String[]> launcher) {
		String[] perms = new String[] {BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT,
				BLUETOOTH_SCAN};
		launcher.launch(perms);
	}
}
