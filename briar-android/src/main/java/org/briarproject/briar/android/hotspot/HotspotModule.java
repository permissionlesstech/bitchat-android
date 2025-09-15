package org.briarproject.briar.android.hotspot;

import org.briarproject.briar.android.viewmodel.ViewModelKey;

import androidx.lifecycle.ViewModel;
import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoMap;

@Module
public interface HotspotModule {

	@Binds
	@IntoMap
	@ViewModelKey(HotspotViewModel.class)
	ViewModel bindHotspotViewModel(HotspotViewModel hotspotViewModel);

}
