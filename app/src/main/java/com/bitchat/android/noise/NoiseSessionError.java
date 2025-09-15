package com.bitchat.android.noise;

/**
 * Classe de base pour les erreurs spécifiques à la gestion des sessions Noise.
 */
public abstract class NoiseSessionError extends Exception {
    public NoiseSessionError(String message) {
        super(message);
    }
    public NoiseSessionError(String message, Throwable cause) {
        super(message, cause);
    }

    public static class SessionNotFound extends NoiseSessionError {
        public SessionNotFound() { super("Session non trouvée"); }
    }
    public static class SessionNotEstablished extends NoiseSessionError {
        public SessionNotEstablished() { super("Session non établie"); }
    }
    public static class InvalidState extends NoiseSessionError {
        public InvalidState() { super("État de la session invalide"); }
    }
    public static class HandshakeFailed extends NoiseSessionError {
        public HandshakeFailed() { super("La poignée de main (handshake) a échoué"); }
    }
    public static class AlreadyEstablished extends NoiseSessionError {
        public AlreadyEstablished() { super("La session est déjà établie"); }
    }
}
