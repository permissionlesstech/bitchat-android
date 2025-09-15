package org.briarproject.briar.android.hotspot;

import android.os.Bundle;
import android.view.View;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import static android.view.View.GONE;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class WebsiteFragment extends AbstractTabsFragment {

	public final static String TAG = WebsiteFragment.class.getName();

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		connectedButton.setVisibility(GONE);
	}

	@Override
	protected Fragment getFirstFragment() {
		return ManualHotspotFragment.newInstance(false);
	}

	@Override
	protected Fragment getSecondFragment() {
		return QrHotspotFragment.newInstance(false);
	}

}
