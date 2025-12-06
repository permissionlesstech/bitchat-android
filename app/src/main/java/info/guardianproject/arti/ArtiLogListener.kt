package info.guardianproject.arti

/**
 * Listener interface for Arti log messages.
 *
 * This interface is called from the native Arti implementation whenever
 * a log line is produced. It allows the application to monitor Tor's
 * bootstrap progress and connection status.
 */
fun interface ArtiLogListener {
    /**
     * Called when Arti produces a log line.
     *
     * @param logLine The log message from Arti, or null if no message
     */
    fun onLogLine(logLine: String?)
}
