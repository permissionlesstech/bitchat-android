package com.bitchat.android;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.bitchat.android.onboarding.BluetoothStatus;
import com.bitchat.android.onboarding.LocationStatus;
import com.bitchat.android.onboarding.OnboardingState;
import com.bitchat.android.onboarding.BatteryOptimizationStatus;

/**
 * ViewModel pour l'activité principale.
 * Gère l'état de la phase d'initialisation (onboarding) et les permissions.
 * Utilise LiveData pour communiquer les changements d'état à l'UI de manière réactive.
 */
public class MainViewModel extends ViewModel {

    // Note: Les classes comme OnboardingState, BluetoothStatus, etc., seront converties de Kotlin à Java ultérieurement.

    private final MutableLiveData<OnboardingState> _onboardingState = new MutableLiveData<>(OnboardingState.CHECKING);
    public final LiveData<OnboardingState> onboardingState = _onboardingState;

    private final MutableLiveData<BluetoothStatus> _bluetoothStatus = new MutableLiveData<>(BluetoothStatus.ENABLED);
    public final LiveData<BluetoothStatus> bluetoothStatus = _bluetoothStatus;

    private final MutableLiveData<LocationStatus> _locationStatus = new MutableLiveData<>(LocationStatus.ENABLED);
    public final LiveData<LocationStatus> locationStatus = _locationStatus;

    private final MutableLiveData<String> _errorMessage = new MutableLiveData<>("");
    public final LiveData<String> errorMessage = _errorMessage;

    private final MutableLiveData<Boolean> _isBluetoothLoading = new MutableLiveData<>(false);
    public final LiveData<Boolean> isBluetoothLoading = _isBluetoothLoading;

    private final MutableLiveData<Boolean> _isLocationLoading = new MutableLiveData<>(false);
    public final LiveData<Boolean> isLocationLoading = _isLocationLoading;

    private final MutableLiveData<BatteryOptimizationStatus> _batteryOptimizationStatus = new MutableLiveData<>(BatteryOptimizationStatus.ENABLED);
    public final LiveData<BatteryOptimizationStatus> batteryOptimizationStatus = _batteryOptimizationStatus;

    private final MutableLiveData<Boolean> _isBatteryOptimizationLoading = new MutableLiveData<>(false);
    public final LiveData<Boolean> isBatteryOptimizationLoading = _isBatteryOptimizationLoading;


    // Fonctions de mise à jour publiques pour être appelées depuis la MainActivity
    public void updateOnboardingState(OnboardingState state) {
        _onboardingState.setValue(state);
    }

    public void updateBluetoothStatus(BluetoothStatus status) {
        _bluetoothStatus.setValue(status);
    }

    public void updateLocationStatus(LocationStatus status) {
        _locationStatus.setValue(status);
    }

    public void updateErrorMessage(String message) {
        _errorMessage.setValue(message);
    }

    public void updateBluetoothLoading(boolean loading) {
        _isBluetoothLoading.setValue(loading);
    }

    public void updateLocationLoading(boolean loading) {
        _isLocationLoading.setValue(loading);
    }

    public void updateBatteryOptimizationStatus(BatteryOptimizationStatus status) {
        _batteryOptimizationStatus.setValue(status);
    }

    public void updateBatteryOptimizationLoading(boolean loading) {
        _isBatteryOptimizationLoading.setValue(loading);
    }
}
