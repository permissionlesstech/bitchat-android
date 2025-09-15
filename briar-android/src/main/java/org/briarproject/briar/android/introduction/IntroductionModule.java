package org.briarproject.briar.android.introduction;

import org.briarproject.briar.android.viewmodel.ViewModelKey;

import androidx.lifecycle.ViewModel;
import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoMap;

@Module
public abstract class IntroductionModule {

	@Binds
	@IntoMap
	@ViewModelKey(IntroductionViewModel.class)
	abstract ViewModel bindIntroductionViewModel(
			IntroductionViewModel introductionViewModel);

}
