package com.example.networkscanner

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.delay // Added for potential use in utility functions
import java.nio.charset.Charset // Import Charset

/**
 * Utility class for basic printer ESC/POS commands and operations.
 */
object PrinterUtils {
    private const val TAG = "PrinterUtils"
    private const val DEFAULT_SOCKET_TIMEOUT = 5000 // 5 seconds

    // Common ESC/POS Commands
    val ESC_AT = byteArrayOf(0x1B, 0x40)         // Initialize/Reset Printer
    val GS_V_0 = byteArrayOf(0x1D, 0x56, 0x00)    // Partial Cut
    val GS_V_1 = byteArrayOf(0x1D, 0x56, 0x01)    // Full Cut
    val LF = byteArrayOf(0x0A)                   // Line Feed

    // Text Formatting
    val TXT_NORMAL = byteArrayOf(0x1B, 0x21, 0x00) // Normal text
    val TXT_BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01) // Bold on
    val TXT_BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)// Bold off
    val TXT_ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00) // Align left
    val TXT_ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01) // Align center
    val TXT_ALIGN_RIGHT = byteArrayOf(0x1B, 0x61, 0x02)  // Align right
    val TXT_UNDERLINE_ON = byteArrayOf(0x1B, 0x2D, 0x01) // Underline on (1-dot)
    val TXT_UNDERLINE_OFF = byteArrayOf(0x1B, 0x2D, 0x00)// Underline off
    val TXT_FONT_LARGE = byteArrayOf(0x1D, 0x21, 0x11) // Double height, double width

    // Other useful commands (add as needed)
    val DLE_EOT_1 = byteArrayOf(0x10, 0x04, 0x01) // Transmit printer status (online status)
    val DC4_CANCEL_JOB = byteArrayOf(0x10, 0x14, 0x01, 0x00, 0x00) // Cancel last job / clear buffer

    /**
     * Helper function to send a command to the printer via an OutputStream.
     */
    suspend fun sendCommand(outputStream: OutputStream, command: ByteArray) {
        try {
            outputStream.write(command)
            outputStream.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending command: ${command.contentToString()}", e)
            // Optionally rethrow or handle
        }
    }

    /**
     * Helper function to send text to the printer.
     */
    suspend fun sendText(outputStream: OutputStream, text: String, charset: Charset = Charsets.UTF_8) {
        try {
            outputStream.write(text.toByteArray(charset))
            outputStream.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending text: $text", e)
        }
    }
    
    /**
     * Helper to safely close a socket.
     */
    fun closeSocketSafely(socket: Socket?, logMessage: String = "Closing socket") {
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "$logMessage failed: ${e.message}")
        }
    }
} 