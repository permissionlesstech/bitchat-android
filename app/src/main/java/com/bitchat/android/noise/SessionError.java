package com.bitchat.android.noise;

/**
 * Classe de base pour les erreurs spécifiques à une session Noise.
 * Simule une "sealed class" Kotlin en utilisant une hiérarchie de classes d'exception.
 */
public abstract class SessionError extends Exception {
    public SessionError(String message) {
        super(message);
    }
    public SessionError(String message, Throwable cause) {
        super(message, cause);
    }

    public static class InvalidState extends SessionError {
        public InvalidState() { super("La session est dans un état invalide"); }
    }
    public static class NotEstablished extends SessionError {
        public NotEstablished() { super("La session n'est pas établie"); }
    }
    public static class HandshakeFailed extends SessionError {
        public HandshakeFailed() { super("La poignée de main (handshake) a échoué"); }
    }
    public static class EncryptionFailed extends SessionError {
        public EncryptionFailed() { super("Le chiffrement a échoué"); }
    }
    public static class DecryptionFailed extends SessionError {
        public DecryptionFailed() { super("Le déchiffrement a échoué"); }
    }
    public static class HandshakeInitializationFailed extends SessionError {
        public HandshakeInitializationFailed(String message) { super("L'initialisation du handshake a échoué: " + message); }
    }
    public static class NonceExceeded extends SessionError {
        public NonceExceeded(String message) { super(message); }
    }
}
