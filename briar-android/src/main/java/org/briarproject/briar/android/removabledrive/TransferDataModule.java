package org.briarproject.briar.android.removabledrive;

import org.briarproject.briar.android.viewmodel.ViewModelKey;

import androidx.lifecycle.ViewModel;
import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoMap;

@Module
public interface TransferDataModule {

	@Binds
	@IntoMap
	@ViewModelKey(RemovableDriveViewModel.class)
	ViewModel bindRemovableDriveViewModel(
			RemovableDriveViewModel removableDriveViewModel);

}
