/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.briarproject.briar.android.viewmodel;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Map;
import java.util.Map.Entry;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

@Singleton
@NotNullByDefault
class ViewModelFactory implements ViewModelProvider.Factory {

	private final Map<Class<? extends ViewModel>, Provider<ViewModel>> creators;

	@Inject
	ViewModelFactory(Map<Class<? extends ViewModel>,
			Provider<ViewModel>> creators) {
		this.creators = creators;
	}

	@Override
	public <T extends ViewModel> T create(Class<T> modelClass) {
		Provider<? extends ViewModel> creator = creators.get(modelClass);
		if (creator == null) {
			for (Entry<Class<? extends ViewModel>, Provider<ViewModel>> entry :
					creators.entrySet()) {
				if (modelClass.isAssignableFrom(entry.getKey())) {
					creator = entry.getValue();
					break;
				}
			}
		}
		if (creator == null) {
			throw new IllegalArgumentException(
					"unknown model class " + modelClass);
		}
		//noinspection unchecked
		return (T) creator.get();
	}

}
