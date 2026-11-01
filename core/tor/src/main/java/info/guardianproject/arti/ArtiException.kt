package info.guardianproject.arti

/**
 * Exception thrown by Arti operations.
 *
 * This exception is thrown when the native Arti library encounters
 * an error during initialization, startup, or other operations.
 */
class ArtiException(message: String, cause: Throwable? = null) : Exception(message, cause)
