package org.briarproject.briar.android.privategroup.list;

import org.briarproject.briar.android.viewmodel.ViewModelKey;

import androidx.lifecycle.ViewModel;
import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoMap;

@Module
public abstract class GroupListModule {

	@Binds
	@IntoMap
	@ViewModelKey(GroupListViewModel.class)
	abstract ViewModel bindGroupListViewModel(
			GroupListViewModel groupListViewModel);
}
