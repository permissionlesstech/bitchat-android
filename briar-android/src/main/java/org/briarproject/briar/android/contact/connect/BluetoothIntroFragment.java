package org.briarproject.briar.android.contact.connect;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import org.briarproject.briar.R;
import org.briarproject.briar.android.util.ActivityLaunchers.RequestBluetoothDiscoverable;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.Map;

import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import static android.widget.Toast.LENGTH_LONG;
import static org.briarproject.briar.android.AppModule.getAndroidComponent;
import static org.briarproject.briar.android.util.UiUtils.hideViewOnSmallScreen;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class BluetoothIntroFragment extends Fragment {

	final static String TAG = BluetoothIntroFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private final BluetoothConditionManager conditionManager =
			new BluetoothConditionManager();
	private ConnectViaBluetoothViewModel viewModel;

	private final ActivityResultLauncher<Integer> bluetoothDiscoverableRequest =
			registerForActivityResult(new RequestBluetoothDiscoverable(),
					this::onBluetoothDiscoverable);
	private final ActivityResultLauncher<String[]> permissionRequest =
			registerForActivityResult(new RequestMultiplePermissions(),
					this::onPermissionRequestResult);

	@Override
	public void onAttach(Context context) {
		super.onAttach(context);
		getAndroidComponent(requireContext()).inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(ConnectViaBluetoothViewModel.class);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		return inflater
				.inflate(R.layout.fragment_bluetooth_intro, container, false);
	}

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		Button startButton = view.findViewById(R.id.startButton);
		startButton.setOnClickListener(this::onStartClicked);
	}

	@Override
	public void onStart() {
		super.onStart();
		hideViewOnSmallScreen(requireView().findViewById(R.id.introImageView));
		conditionManager.reset();
	}

	private void onStartClicked(View v) {
		if (viewModel.shouldStartFlow()) {
			// The dialog starts a permission request which comes back as true
			// if the permission is already granted.
			// So we can use the request as a generic entry point
			// to the whole flow.
			conditionManager.requestPermissions(permissionRequest);
		}
	}

	private void onPermissionRequestResult(
			@Nullable Map<String, Boolean> result) {
		FragmentActivity a = requireActivity();
		// update permission result in BluetoothConnecter
		conditionManager.onLocationPermissionResult(a, result);
		// what to do when the user denies granting the location permission
		Runnable onLocationPermissionDenied = () -> Toast.makeText(
				requireContext(),
				R.string.connect_via_bluetooth_no_location_permission,
				LENGTH_LONG).show();
		// if requirements are fulfilled, request Bluetooth discoverability
		if (conditionManager.areRequirementsFulfilled(a, permissionRequest,
				onLocationPermissionDenied)) {
			bluetoothDiscoverableRequest.launch(120); // for 2min
		}
	}

	private void onBluetoothDiscoverable(@Nullable Boolean result) {
		if (result != null && result) {
			viewModel.onBluetoothDiscoverable();
		}
	}

}
