package com.bitchat.android.mesh;

/**
 * Interface déléguée pour les callbacks de gestion de l'alimentation (PowerManager).
 */
public interface PowerManagerDelegate {
    /**
     * Appelé lorsque le mode d'alimentation a changé.
     * @param newMode Le nouveau mode d'alimentation actif.
     */
    void onPowerModeChanged(PowerManager.PowerMode newMode);

    /**
     * Appelé pour indiquer si le scan Bluetooth doit être actif ou non,
     * en fonction du cycle de service (duty cycle).
     * @param shouldScan true si le scan doit être activé, false sinon.
     */
    void onScanStateChanged(boolean shouldScan);
}
