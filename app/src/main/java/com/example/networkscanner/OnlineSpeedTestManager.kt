package com.example.networkscanner

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okhttp3.ResponseBody
import com.google.gson.Gson
import com.google.gson.JsonObject
import okio.ByteString
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class OnlineSpeedTestManager(private val context: Context) {
    private val TAG = "OnlineSpeedTestManager"
    
    // M-Lab NDT7 API endpoints
    private val LOCATE_URL = "https://locate.measurementlab.net/v2/nearest/ndt/ndt7"
    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(3, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()
    
    // Results data class
    data class SpeedTestResult(
        val downloadMbps: Float = 0f,
        val uploadMbps: Float = 0f,
        val latencyMs: Float = 0f,
        val isp: String = "",
        val serverName: String = "",
        val testDurationMs: Long = 0L,
        val status: String = "Ready"
    )
    
    // Callbacks for test progress and completion
    interface SpeedTestListener {
        fun onTestStarted()
        fun onTestFinished(result: SpeedTestResult)
        fun onTestProgress(
            speedMbps: Float, 
            progressPercent: Int, 
            isDownload: Boolean
        )
        fun onTestFailed(reason: String)
    }
    
    private var listener: SpeedTestListener? = null
    private lateinit var downloadUrl: String
    private lateinit var uploadUrl: String
    private var serverName: String = ""
    private var testResult = SpeedTestResult()
    private var webSocket: WebSocket? = null
    
    fun setSpeedTestListener(listener: SpeedTestListener) {
        this.listener = listener
    }
    
    // Entry point for the test
    suspend fun startTest(): SpeedTestResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        testResult = SpeedTestResult(status = "Running")
        
        try {
            listener?.onTestStarted()
            
            // Get the nearest server
            val serverInfo = findNearestServer()
            if (serverInfo == null) {
                listener?.onTestFailed("Failed to find a test server")
                return@withContext testResult.copy(status = "Failed: No server found")
            }
            
            serverName = serverInfo.first
            downloadUrl = serverInfo.second.first
            uploadUrl = serverInfo.second.second
            testResult = testResult.copy(serverName = serverName)
            
            // First test ping/latency
            val pingResult = measurePing()
            testResult = testResult.copy(latencyMs = pingResult)
            
            // Then test download
            val downloadResult = performDownloadTest()
            testResult = testResult.copy(downloadMbps = downloadResult)
            
            // Finally test upload
            val uploadResult = performUploadTest()
            testResult = testResult.copy(uploadMbps = uploadResult)
            
            // Calculate total duration
            val duration = System.currentTimeMillis() - startTime
            testResult = testResult.copy(
                testDurationMs = duration,
                status = "Completed"
            )
            
            listener?.onTestFinished(testResult)
            return@withContext testResult
            
        } catch (e: Exception) {
            Log.e(TAG, "Speed test failed: ${e.message}", e)
            listener?.onTestFailed(e.message ?: "Unknown error")
            return@withContext testResult.copy(status = "Failed: ${e.message}")
        }
    }
    
    // Find the nearest M-Lab NDT7 server
    private suspend fun findNearestServer(): Pair<String, Pair<String, String>>? = withContext(Dispatchers.IO) {
        try {
            val url = URL(LOCATE_URL)
            val conn = url.openConnection()
            conn.connectTimeout = 5000
            
            val response = conn.getInputStream().bufferedReader().use { it.readText() }
            val gson = Gson()
            val jsonArray = gson.fromJson(response, Array<JsonObject>::class.java)
            
            if (jsonArray.isNotEmpty()) {
                val resultsElement = jsonArray[0].getAsJsonArray("results")
                val resultsArray = gson.fromJson(resultsElement, Array<JsonObject>::class.java)
                if (resultsArray.isNotEmpty()) {
                    val server = resultsArray[0]
                    val urls = server.getAsJsonObject("urls")
                    
                    val downloadUrlStr = urls.getAsJsonObject("ndt7")
                        .getAsJsonPrimitive("wss:///download")
                        .asString
                    
                    val uploadUrlStr = urls.getAsJsonObject("ndt7")
                        .getAsJsonPrimitive("wss:///upload")
                        .asString
                    
                    val serverName = server.get("machine").asString
                    return@withContext Pair(serverName, Pair(downloadUrlStr, uploadUrlStr))
                }
            }
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find server: ${e.message}", e)
            return@withContext null
        }
    }
    
    private suspend fun measurePing(): Float = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            val url = URL(downloadUrl.replace("wss://", "https://").replace("/download", "/"))
            val conn = url.openConnection()
            conn.connectTimeout = 5000
            conn.connect()
            val endTime = System.currentTimeMillis()
            
            return@withContext (endTime - startTime).toFloat()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to measure ping: ${e.message}", e)
            return@withContext 0f
        }
    }
    
    private suspend fun performDownloadTest(): Float = withContext(Dispatchers.IO) {
        val downloadCompletionSignal = java.util.concurrent.CountDownLatch(1)
        var finalSpeed = 0f
        
        val downloadRequest = Request.Builder()
            .url(downloadUrl)
            .build()
        
        val downloadListener = object : WebSocketListener() {
            private var startTime = System.currentTimeMillis()
            private var bytesReceived = 0L
            private var lastSpeedUpdate = 0f
            private var measurementStartTime = System.currentTimeMillis()
            private var measurementCount = 0
            
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Download WebSocket opened")
                startTime = System.currentTimeMillis()
                bytesReceived = 0
                measurementStartTime = System.currentTimeMillis()
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                bytesReceived += text.length
                measurementCount++
                
                try {
                    val json = Gson().fromJson(text, JsonObject::class.java)
                    if (json.has("ConnectionInfo") || json.has("MeasurementResult")) {
                        // This is metadata, not actual test data
                        return
                    }
                    
                    val now = System.currentTimeMillis()
                    if (now - measurementStartTime >= 250) {  // Update every 250ms
                        val elapsedSeconds = (now - startTime) / 1000.0
                        if (elapsedSeconds > 0) {
                            val bps = (bytesReceived * 8.0) / elapsedSeconds
                            val mbps = (bps / 1_000_000.0).toFloat()
                            
                            // Avoid updating with zero if we had a higher value before
                            if (mbps > 0 || lastSpeedUpdate == 0f) {
                                lastSpeedUpdate = mbps
                            }
                            
                            // Calculate progress (the test runs for about 10 seconds)
                            val progressPercent = ((now - startTime) / 100.0).toInt().coerceIn(0, 100)
                            
                            // Report progress
                            listener?.onTestProgress(lastSpeedUpdate, progressPercent, true)
                        }
                        
                        measurementStartTime = now
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing download message: ${e.message}")
                }
            }
            
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                bytesReceived += bytes.size
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0
                if (elapsedSeconds > 0) {
                    val bps = (bytesReceived * 8.0) / elapsedSeconds
                    finalSpeed = (bps / 1_000_000.0).toFloat()
                    
                    Log.d(TAG, "Download completed: ${finalSpeed} Mbps")
                }
                
                webSocket.close(1000, "Test completed")
                downloadCompletionSignal.countDown()
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Download test failed: ${t.message}")
                finalSpeed = lastSpeedUpdate  // Use the last measured speed
                downloadCompletionSignal.countDown()
            }
        }
        
        webSocket = okHttpClient.newWebSocket(downloadRequest, downloadListener)
        
        // Wait for the test to complete (with timeout)
        try {
            downloadCompletionSignal.await(15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Download test timed out: ${e.message}")
        } finally {
            webSocket?.close(1000, "Test completed")
            webSocket = null
        }
        
        return@withContext finalSpeed
    }
    
    private suspend fun performUploadTest(): Float = withContext(Dispatchers.IO) {
        val uploadCompletionSignal = java.util.concurrent.CountDownLatch(1)
        var finalSpeed = 0f
        
        val uploadRequest = Request.Builder()
            .url(uploadUrl)
            .build()
        
        val uploadListener = object : WebSocketListener() {
            private var startTime = System.currentTimeMillis()
            private var bytesSent = 0L
            private var lastSpeedUpdate = 0f
            private var measurementStartTime = System.currentTimeMillis()
            private val message = ByteString.of(*ByteArray(8192) { 0 })  // 8KB chunks
            private var isTestRunning = false
            
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Upload WebSocket opened")
                startTime = System.currentTimeMillis()
                bytesSent = 0
                measurementStartTime = System.currentTimeMillis()
                isTestRunning = true
                
                // Start sending data
                sendData()
            }
            
            private fun sendData() {
                val testDuration = 10000  // 10 seconds
                
                Thread {
                    val endTime = startTime + testDuration
                    while (isTestRunning && System.currentTimeMillis() < endTime) {
                        if (webSocket?.send(message) == true) {
                            bytesSent += message.size
                            
                            val now = System.currentTimeMillis()
                            if (now - measurementStartTime >= 250) {  // Update every 250ms
                                val elapsedSeconds = (now - startTime) / 1000.0
                                if (elapsedSeconds > 0) {
                                    val bps = (bytesSent * 8.0) / elapsedSeconds
                                    val mbps = (bps / 1_000_000.0).toFloat()
                                    
                                    if (mbps > 0 || lastSpeedUpdate == 0f) {
                                        lastSpeedUpdate = mbps
                                    }
                                    
                                    // Calculate progress
                                    val progressPercent = ((now - startTime) / 100.0).toInt().coerceIn(0, 100)
                                    
                                    // Report progress
                                    listener?.onTestProgress(lastSpeedUpdate, progressPercent, false)
                                }
                                
                                measurementStartTime = now
                            }
                        }
                        
                        try {
                            Thread.sleep(1)  // Prevent CPU hogging
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                    
                    isTestRunning = false
                    val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0
                    if (elapsedSeconds > 0) {
                        val bps = (bytesSent * 8.0) / elapsedSeconds
                        finalSpeed = (bps / 1_000_000.0).toFloat()
                        Log.d(TAG, "Upload completed: ${finalSpeed} Mbps")
                    }
                    
                    webSocket?.close(1000, "Test completed")
                    uploadCompletionSignal.countDown()
                }.start()
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                // Process any server responses (usually metadata)
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isTestRunning = false
                webSocket.close(1000, "Test completed")
                
                if (finalSpeed == 0f) {
                    finalSpeed = lastSpeedUpdate
                }
                
                uploadCompletionSignal.countDown()
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Upload test failed: ${t.message}")
                isTestRunning = false
                finalSpeed = lastSpeedUpdate  // Use the last measured speed
                uploadCompletionSignal.countDown()
            }
        }
        
        webSocket = okHttpClient.newWebSocket(uploadRequest, uploadListener)
        
        // Wait for the test to complete (with timeout)
        try {
            uploadCompletionSignal.await(15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Upload test timed out: ${e.message}")
        } finally {
            webSocket?.close(1000, "Test completed")
            webSocket = null
        }
        
        return@withContext finalSpeed
    }
    
    fun cancelTest() {
        webSocket?.close(1000, "Test canceled")
        webSocket = null
    }
    
    companion object {
        // Helper method to get speed quality assessment
        fun getSpeedQuality(speedMbps: Float): SpeedQuality {
            return when {
                speedMbps >= 100 -> SpeedQuality.EXCELLENT
                speedMbps >= 50 -> SpeedQuality.GOOD
                speedMbps >= 25 -> SpeedQuality.AVERAGE
                speedMbps >= 8 -> SpeedQuality.POOR
                speedMbps > 0 -> SpeedQuality.BAD
                else -> SpeedQuality.UNKNOWN
            }
        }
    }
} 