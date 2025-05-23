package com.example.networkscanner

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Random

class LanSpeedTestManager(private val context: Context) {
    private val TAG = "LanSpeedTestManager"
    
    // Test file sizes in bytes (1MB, 5MB, 10MB)
    private val TEST_FILE_SIZES = listOf(1_048_576, 5_242_880, 10_485_760)
    
    // Results data class
    data class SpeedTestResult(
        val pingMs: Float = 0f,
        val downloadMbps: Float = 0f,
        val uploadMbps: Float = 0f,
        val testDurationMs: Long = 0L,
        val status: String = ""
    )
    
    suspend fun performSpeedTest(gatewayIp: String): SpeedTestResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var result = SpeedTestResult(status = "Testing...")
        
        try {
            // 1. Ping test
            val pingResult = measurePing(gatewayIp, 5) // 5 ping attempts
            result = result.copy(pingMs = pingResult)
            
            // 2. Download speed test
            val downloadSpeed = measureDownloadSpeed(gatewayIp)
            result = result.copy(downloadMbps = downloadSpeed)
            
            // 3. Upload speed test
            val uploadSpeed = measureUploadSpeed(gatewayIp)
            result = result.copy(uploadMbps = uploadSpeed)
            
            // Calculate total duration
            val duration = System.currentTimeMillis() - startTime
            result = result.copy(
                testDurationMs = duration,
                status = "Completed"
            )
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Speed test failed: ${e.message}", e)
            result.copy(status = "Failed: ${e.message}")
        }
    }
    
    private suspend fun measurePing(gatewayIp: String, attempts: Int): Float {
        val pingTimes = mutableListOf<Float>()
        
        repeat(attempts) {
            try {
                val startTime = System.nanoTime()
                val socket = Socket()
                socket.connect(InetSocketAddress(gatewayIp, 80), 2000) // Connect to HTTP port
                val endTime = System.nanoTime()
                socket.close()
                
                // Calculate ms from ns
                val pingTime = (endTime - startTime) / 1_000_000f
                pingTimes.add(pingTime)
                delay(200) // Brief delay between pings
            } catch (e: Exception) {
                Log.d(TAG, "Socket ping failed, trying ICMP: ${e.message}")
                // Try ICMP ping as fallback if HTTP port is closed
                try {
                    val pingProcess = Runtime.getRuntime().exec("ping -c 1 -W 2 $gatewayIp")
                    val exitValue = pingProcess.waitFor()
                    
                    if (exitValue == 0) {
                        val output = pingProcess.inputStream.bufferedReader().use { it.readText() }
                        val pattern = "time=(\\d+\\.?\\d*) ms".toRegex()
                        val match = pattern.find(output)
                        val pingTime = match?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                        pingTimes.add(pingTime)
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "ICMP ping also failed: ${e2.message}")
                }
            }
        }
        
        // Return average ping time, excluding zeros
        return if (pingTimes.isNotEmpty()) {
            pingTimes.filter { it > 0 }.average().toFloat()
        } else 0f
    }
    
    private suspend fun measureDownloadSpeed(gatewayIp: String): Float {
        // Try HTTP download from router if available
        try {
            val url = URL("http://$gatewayIp/")
            val startTime = System.currentTimeMillis()
            var bytesDownloaded = 0L
            
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val buffer = ByteArray(8192)
                val inputStream = connection.inputStream
                var bytesRead: Int
                
                // Read for max 10 seconds for more accurate throughput measurement
                val testDuration = 10000 // 10 seconds
                val endTime = startTime + testDuration
                
                while (System.currentTimeMillis() < endTime) {
                    bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    bytesDownloaded += bytesRead
                }
                
                inputStream.close()
                connection.disconnect()
                
                val durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0
                return (bytesDownloaded * 8 / durationSeconds / 1_000_000).toFloat() // Mbps
            } else {
                Log.d(TAG, "HTTP download test got response code: $responseCode")
            }
        } catch (e: Exception) {
            Log.d(TAG, "HTTP download test failed, trying TCP test: ${e.message}")
        }
        
        // Fallback: TCP socket test
        return performTcpSpeedTest(gatewayIp, 80, true)
    }
    
    private suspend fun measureUploadSpeed(gatewayIp: String): Float {
        // Try TCP socket upload test
        return performTcpSpeedTest(gatewayIp, 80, false)
    }
    
    private suspend fun performTcpSpeedTest(
        gatewayIp: String, 
        port: Int, 
        isDownload: Boolean
    ): Float {
        var socket: Socket? = null
        try {
            // Try alternative common ports if 80 fails
            val ports = listOf(port, 443, 8080, 22, 21)
            var connectedPort = -1
            
            // Try to connect to any available port
            for (testPort in ports) {
                try {
                    socket = Socket()
                    socket.connect(InetSocketAddress(gatewayIp, testPort), 2000)
                    if (socket.isConnected) {
                        connectedPort = testPort
                        break
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Could not connect to port $testPort: ${e.message}")
                    socket?.close()
                    socket = null
                }
            }
            
            if (socket != null && socket.isConnected) {
                Log.d(TAG, "Connected to $gatewayIp:$connectedPort for ${if (isDownload) "download" else "upload"} test")
                val testDuration = 8000 // 8 seconds
                val buffer = ByteArray(8192) // 8KB buffer
                
                // Fill buffer with random data for upload test
                if (!isDownload) {
                    Random().nextBytes(buffer)
                }
                
                val startTime = System.currentTimeMillis()
                val endTime = startTime + testDuration
                var totalBytes = 0L
                
                if (isDownload) {
                    // Download test: read from socket
                    val inputStream = socket.getInputStream()
                    while (System.currentTimeMillis() < endTime) {
                        try {
                            val bytesRead = inputStream.read(buffer)
                            if (bytesRead <= 0) break
                            totalBytes += bytesRead
                        } catch (e: Exception) {
                            break
                        }
                    }
                } else {
                    // Upload test: write to socket
                    val outputStream = socket.getOutputStream()
                    while (System.currentTimeMillis() < endTime) {
                        try {
                            outputStream.write(buffer)
                            outputStream.flush()
                            totalBytes += buffer.size
                        } catch (e: Exception) {
                            break
                        }
                    }
                }
                
                val actualDuration = System.currentTimeMillis() - startTime
                val durationSeconds = actualDuration / 1000.0
                
                // Only return a value if we actually transferred some data
                // Make sure we have a minimum test duration of 3 seconds for better accuracy
                if (totalBytes > 0 && durationSeconds > 0) {
                    // For very short tests, data might be inconsistent
                    if (durationSeconds < 3.0 && totalBytes > 100000) {
                        // Normalize to what a full 3 second test would be
                        val normalizedBytes = (totalBytes / durationSeconds) * 3.0
                        return (normalizedBytes * 8 / 3.0 / 1_000_000).toFloat() // Mbps
                    } else {
                        return (totalBytes * 8 / durationSeconds / 1_000_000).toFloat() // Mbps
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TCP ${if (isDownload) "download" else "upload"} test failed: ${e.message}")
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
        }
        
        return 0f
    }
    
    companion object {
        // Quality assessment thresholds
        fun getPingQuality(pingMs: Float): SpeedQuality {
            return when {
                pingMs <= 5 -> SpeedQuality.EXCELLENT
                pingMs <= 20 -> SpeedQuality.GOOD
                pingMs <= 50 -> SpeedQuality.AVERAGE
                pingMs <= 100 -> SpeedQuality.POOR
                pingMs > 100 -> SpeedQuality.BAD
                else -> SpeedQuality.UNKNOWN
            }
        }
        
        fun getSpeedQuality(speedMbps: Float): SpeedQuality {
            return when {
                speedMbps >= 500 -> SpeedQuality.EXCELLENT
                speedMbps >= 100 -> SpeedQuality.GOOD
                speedMbps >= 50 -> SpeedQuality.AVERAGE
                speedMbps >= 10 -> SpeedQuality.POOR
                speedMbps > 0 -> SpeedQuality.BAD
                else -> SpeedQuality.UNKNOWN
            }
        }
    }
}

enum class SpeedQuality {
    EXCELLENT,
    GOOD,
    AVERAGE,
    POOR,
    BAD,
    UNKNOWN
} 