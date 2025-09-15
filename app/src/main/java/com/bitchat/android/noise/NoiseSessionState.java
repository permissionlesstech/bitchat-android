package com.bitchat.android.noise;

import java.util.Objects;

/**
 * Représente les différents états d'une session Noise.
 * Simule une "sealed class" Kotlin en utilisant une classe abstraite et des sous-classes finales.
 */
public abstract class NoiseSessionState {

    private NoiseSessionState() {}

    public static final class Uninitialized extends NoiseSessionState {
        public static final Uninitialized INSTANCE = new Uninitialized();
        private Uninitialized() {}
        @Override public String toString() { return "uninitialized"; }
    }

    public static final class Handshaking extends NoiseSessionState {
        public static final Handshaking INSTANCE = new Handshaking();
        private Handshaking() {}
        @Override public String toString() { return "handshaking"; }
    }

    public static final class Established extends NoiseSessionState {
        public static final Established INSTANCE = new Established();
        private Established() {}
        @Override public String toString() { return "established"; }
    }

    public static final class Failed extends NoiseSessionState {
        private final Throwable error;
        public Failed(Throwable error) { this.error = error; }
        public Throwable getError() { return error; }
        @Override public String toString() { return "failed: " + error.getMessage(); }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Failed failed = (Failed) o;
            return Objects.equals(error, failed.error);
        }

        @Override
        public int hashCode() {
            return Objects.hash(error);
        }
    }
}
