package com.example.networkscanner.printer

import android.content.Context
import android.util.Log
import net.posprinter.POSConnect
import net.posprinter.POSPrinter
import net.posprinter.IConnectListener
import net.posprinter.IPOSListener
import net.posprinter.IDeviceConnection
import net.posprinter.posprinterface.IStatusCallback
import net.posprinter.POSConst

class XprinterManager private constructor(private val applicationContext: Context) {
    companion object {
        private const val TAG = "XprinterManager"
        @Volatile private var instance: XprinterManager? = null

        fun getInstance(context: Context): XprinterManager =
            instance ?: synchronized(this) {
                instance ?: XprinterManager(context.applicationContext).also { instance = it }
            }
    }

    private var _posPrinter: POSPrinter? = null
    private var currentDeviceConnection: IDeviceConnection? = null
    private var sdkInitialized = false

    val printer: POSPrinter?
        get() = _posPrinter

    fun setup() {
        if (!sdkInitialized) {
            try {
                Log.d(TAG, "Initializing Xprinter SDK...")
                POSConnect.init(applicationContext)
                sdkInitialized = true
                Log.d(TAG, "Xprinter SDK (POSConnect context) initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Xprinter SDK: ${e.message}", e)
                sdkInitialized = false
            }
        } else {
            Log.d(TAG, "SDK already initialized")
        }
    }

    fun connectPrinter(ipAddress: String, callback: IConnectListener) {
        if (!sdkInitialized) {
            Log.e(TAG, "SDK not set up. Call setup() first.")
            callback.onStatus(POSConnect.CONNECT_FAIL, "SDK not initialized", "")
            return
        }

        Log.d(TAG, "Connecting to printer at $ipAddress")
        
        // Check if we're already connected to this printer
        if (currentDeviceConnection != null && _posPrinter != null) {
            Log.d(TAG, "Printer connection already exists, checking if it's the same printer")
            // We should check if we're connected to the same IP, but without a direct method
            // we'll just return success if we have an active connection
            callback.onStatus(POSConnect.CONNECT_SUCCESS, "Already connected", ipAddress)
            return
        }

        // Ensure we clean up any existing connection before creating a new one
        disconnectPrinter()
        
        try {
            Log.d(TAG, "Creating new printer connection")
            currentDeviceConnection = POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET)
            currentDeviceConnection?.connect(ipAddress, object : IConnectListener {
                override fun onStatus(status: Int, message: String, extra: String) {
                    Log.d(TAG, "Connection status: $status, message: $message")
                    
                    if (status == POSConnect.CONNECT_SUCCESS) {
                        try {
                            if (currentDeviceConnection != null) {
                                _posPrinter = POSPrinter(currentDeviceConnection!!) 
                                Log.d(TAG, "Printer connected, POSPrinter instantiated successfully")
                                callback.onStatus(status, "Connected", extra)
                            } else {
                                Log.e(TAG, "IDeviceConnection is null after successful status from connect call.")
                                callback.onStatus(POSConnect.CONNECT_FAIL, "Internal connection object error", "")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error instantiating POSPrinter after connect", e)
                            currentDeviceConnection = null
                            callback.onStatus(POSConnect.CONNECT_FAIL, e.message ?: "POSPrinter init error", "")
                        }
                    } else {
                        Log.e(TAG, "IDeviceConnection connect failed: $message (Status: $status)")
                        _posPrinter = null
                        currentDeviceConnection = null
                        callback.onStatus(status, message, extra) 
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception during connectPrinter setup: ${e.message}", e)
            currentDeviceConnection = null
            _posPrinter = null
            callback.onStatus(POSConnect.CONNECT_FAIL, e.message ?: "Connection setup error", "")
        }
    }

    fun disconnectPrinter() {
        Log.d(TAG, "Disconnecting current printer")
        try {
            if (currentDeviceConnection != null) {
                currentDeviceConnection?.close()
                Log.d(TAG, "Printer connection closed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during IDeviceConnection.close(): ${e.message}", e)
        }
        _posPrinter = null
        currentDeviceConnection = null
        Log.d(TAG, "Printer disconnected and resources released.")
    }

    fun printTestPage(callback: IPOSListener) {
        if (_posPrinter == null) {
            Log.e(TAG, "Printer not connected for printTestPage")
            callback.onStatus(1, "Printer not connected") 
            return
        }
        try {
            Log.d(TAG, "Starting test page print sequence")
            _posPrinter?.apply {
                initializePrinter()
                printString("== Test Page ==\n")
                printText("SDK Test Print\n", 1, 0, 0)
                printQRCode("https://example.com", 8, 1)
                feedLine(3)
                cutPaper()
            }
            Log.d(TAG, "Test page data sent successfully")
            callback.onStatus(0, "Test page sent") 
        } catch (e: Exception) {
            Log.e(TAG, "Error printing test page: ${e.message}", e)
            callback.onStatus(2, e.message ?: "Print error") 
        }
    }

    fun getPrinterStatus(statusCallback: IStatusCallback) { 
        if (_posPrinter == null) {
            Log.e(TAG, "Printer not connected for getPrinterStatus")
            statusCallback.receive(-100) 
            return
        }
        try {
            _posPrinter?.printerStatus(statusCallback) 
        } catch (e: Exception) {
            Log.e(TAG, "Error calling printerStatus", e)
            statusCallback.receive(-101) 
        }
    }

    fun cutPaper(callback: IPOSListener) {
        if (_posPrinter == null) {
            Log.e(TAG, "Printer not connected for cutPaper")
            callback.onStatus(1, "Printer not connected") 
            return
        }
        try {
            _posPrinter?.cutPaper()
            Log.d(TAG, "Cut paper command sent.")
            callback.onStatus(0, "Cut paper command sent") 
        } catch (e: Exception) {
            Log.e(TAG, "Error cutting paper", e)
            callback.onStatus(2, e.message ?: "Cut paper error") 
        }
    }

    // Add a method to check if the SDK and printer are ready
    fun isPrinterReady(): Boolean {
        val ready = sdkInitialized && _posPrinter != null
        Log.d(TAG, "Printer ready state: SDK initialized=${sdkInitialized}, printer=${_posPrinter != null}")
        return ready
    }
} 