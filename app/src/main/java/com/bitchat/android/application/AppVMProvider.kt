package com.bitchat.android.application

import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bitchat.android.MainViewModel
import com.bitchat.android.ui.ChatViewModel

object AppVMProvider {
    val Factory = viewModelFactory {
        initializer {
            MainViewModel()
        }
        initializer {
            ChatViewModel(
                bitchatApplication(),
                bitchatApplication().container.meshService,
                bitchatApplication().container.bmd
            )
        }
    }
}