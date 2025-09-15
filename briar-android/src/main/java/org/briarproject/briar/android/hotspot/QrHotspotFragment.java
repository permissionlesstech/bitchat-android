package org.briarproject.briar.android.hotspot;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.briarproject.briar.R;
import org.briarproject.briar.android.hotspot.HotspotState.HotspotStarted;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import static org.briarproject.briar.android.AppModule.getAndroidComponent;
import static org.briarproject.briar.android.hotspot.AbstractTabsFragment.ARG_FOR_WIFI_CONNECT;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class QrHotspotFragment extends Fragment {

	public final static String TAG = QrHotspotFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private HotspotViewModel viewModel;

	static QrHotspotFragment newInstance(boolean forWifiConnect) {
		QrHotspotFragment f = new QrHotspotFragment();
		Bundle bundle = new Bundle();
		bundle.putBoolean(ARG_FOR_WIFI_CONNECT, forWifiConnect);
		f.setArguments(bundle);
		return f;
	}

	@Override
	public void onAttach(Context context) {
		super.onAttach(context);
		getAndroidComponent(requireContext()).inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(HotspotViewModel.class);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View v = inflater
				.inflate(R.layout.fragment_hotspot_qr, container, false);

		TextView qrIntroView = v.findViewById(R.id.qrIntroView);
		ImageView qrCodeView = v.findViewById(R.id.qrCodeView);

		boolean forWifi = requireArguments().getBoolean(ARG_FOR_WIFI_CONNECT);

		qrIntroView.setText(forWifi ? R.string.hotspot_qr_wifi :
				R.string.hotspot_qr_site);

		viewModel.getState().observe(getViewLifecycleOwner(), state -> {
			if (state instanceof HotspotStarted) {
				HotspotStarted s = (HotspotStarted) state;
				Bitmap qrCode = forWifi ? s.getNetworkConfig().qrCode :
						s.getWebsiteConfig().qrCode;
				if (qrCode == null) {
					Toast.makeText(requireContext(), R.string.error,
							Toast.LENGTH_SHORT).show();
					qrCodeView.setImageResource(R.drawable.ic_image_broken);
				} else {
					qrCodeView.setImageBitmap(qrCode);
				}
			}
		});
		return v;
	}

}
