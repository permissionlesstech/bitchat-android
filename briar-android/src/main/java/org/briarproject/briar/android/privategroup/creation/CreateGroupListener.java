package org.briarproject.briar.android.privategroup.creation;

import org.briarproject.briar.android.fragment.BaseFragment.BaseFragmentListener;

interface CreateGroupListener extends BaseFragmentListener {

	void onGroupNameChosen(String name);
}
