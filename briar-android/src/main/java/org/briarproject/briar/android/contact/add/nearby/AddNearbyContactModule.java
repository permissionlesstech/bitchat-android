package org.briarproject.briar.android.contact.add.nearby;

import org.briarproject.briar.android.viewmodel.ViewModelKey;

import androidx.lifecycle.ViewModel;
import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoMap;

@Module
public abstract class AddNearbyContactModule {

	@Binds
	@IntoMap
	@ViewModelKey(AddNearbyContactViewModel.class)
	abstract ViewModel bindContactExchangeViewModel(
			AddNearbyContactViewModel addNearbyContactViewModel);

}
