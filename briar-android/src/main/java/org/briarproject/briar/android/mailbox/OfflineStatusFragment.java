package org.briarproject.briar.android.mailbox;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class OfflineStatusFragment extends OfflineFragment {

	public static final String TAG = OfflineStatusFragment.class.getName();

	@Override
	protected void onTryAgainClicked() {
		viewModel.checkIfOnlineWhenPaired();
	}

}
