package com.example.networkscanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import android.net.wifi.WifiManager.MulticastLock
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.os.Build
import java.util.Scanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.example.networkscanner.printer.XprinterManager
import net.posprinter.IConnectListener
import net.posprinter.IPOSListener
import net.posprinter.posprinterface.IStatusCallback
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import net.posprinter.esc.PosUdpNet
import net.posprinter.model.UdpDevice
import net.posprinter.posprinterface.UdpCallback
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.SocketTimeoutException
import androidx.cardview.widget.CardView
import android.graphics.Color
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import com.example.networkscanner.LanSpeedTestManager
import com.example.networkscanner.SpeedQuality
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import androidx.core.text.HtmlCompat
import android.widget.LinearLayout
import com.example.networkscanner.PrinterUtils
import android.widget.ScrollView
import android.widget.ImageButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.Socket
import java.net.InetSocketAddress
import android.text.SpannableString
import android.text.style.ClickableSpan
import android.text.method.LinkMovementMethod
import android.text.Spanned
import android.content.Intent
import android.net.Uri
import android.text.TextPaint
import com.example.networkscanner.ArpScanner

class MainActivity : AppCompatActivity() {
    private val TAG = "NetworkScanner"
    private val PERMISSIONS_REQUEST_CODE = 123
    
    // Declare views
    private lateinit var healthStatusText: TextView
    private lateinit var scanButton: MaterialButton
    private lateinit var speedTestButton: MaterialButton
    private lateinit var scanProgressBar: ProgressBar
    private lateinit var deviceRecyclerView: RecyclerView
    private lateinit var healthCheckRecyclerView: RecyclerView
    private lateinit var networkInfoText: TextView
    private lateinit var healthCheckPanelCard: com.google.android.material.card.MaterialCardView
    private lateinit var healthStatusTextInCard: TextView
    private lateinit var healthCheckRecyclerViewInCard: RecyclerView
    private lateinit var networkInfoCard: com.google.android.material.card.MaterialCardView
    private lateinit var resultsContainer: FrameLayout
    private lateinit var speedTestPanelCard: com.google.android.material.card.MaterialCardView
    private lateinit var tvGatewayIp: TextView
    private lateinit var tvPingResult: TextView
    private lateinit var tvUploadResult: TextView
    private lateinit var ivPingQuality: ImageView
    private lateinit var ivUploadQuality: ImageView
    private lateinit var speedTestProgressLayout: LinearLayout
    private lateinit var speedTestResultsLayout: LinearLayout
    private lateinit var speedTestProgressText: TextView

    // Service status bubble views
    private lateinit var refreshServicesButton: ImageButton
    private lateinit var infoButton: ImageButton
    
    // Individual service status text views
    private lateinit var beepitStatusText: TextView
    private lateinit var storehubHQStatusText: TextView
    private lateinit var storehubMeStatusText: TextView
    private lateinit var paymentAPIStatusText: TextView

    // New UI elements for App Log
    private lateinit var appLogTextView: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var clearLogButton: ImageButton

    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var healthCheckAdapter: HealthCheckAdapter
    
    // Animation objects
    private lateinit var fadeInAnimation: Animation
    private lateinit var slideInAnimation: Animation
    
    private var isScanning = false
    private var scanJob: Job? = null
    private var multicastLock: MulticastLock? = null
    private var isMulticastLockAcquired = false

    private val healthCheckServices = mapOf(
        "BeepIT" to "https://www.beepit.com/health",
        "StoreHub HQ" to "https://www.storehubhq.com/health",
        "StoreHub Merchandise" to "https://www.storehub.me/health",
        "Payment API" to "https://payment.storehubhq.com/health"
    )

    private val printerPorts = listOf(9100, 515, 631, 80, 443, 5001) // Added 5001
    private val printerOIDs = listOf(
        "1.3.6.1.2.1.25.3.2.1.3", // Printer description
        "1.3.6.1.2.1.43.9.2.1.8"  // Printer status
    )

    private lateinit var xprinterManager: XprinterManager
    private lateinit var nsdManager: NsdManager
    private var nsdDiscoveryActive = false
    private var nsdResolveListener: NsdManager.ResolveListener? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null

    private var posUdpNet: PosUdpNet? = null
    private var udpScanActive = false
    private var currentNetworkPrefixLength: Int = -1 // Added for gateway detection
    
    // Additional variables for timeouts
    private val UDP_SCAN_TIMEOUT_MS = 8000L // 8 seconds timeout for UDP scan
    private val UDP_INITIAL_WAIT_MS = 2000L // 2 seconds to wait for initial UDP results
    private var udpTimeoutHandler = Handler(Looper.getMainLooper())
    private var udpTimeoutRunnable: Runnable? = null

    // Common Printer Service Types for NSD
    private val PRINTER_SERVICE_TYPES = arrayOf(
        "_ipp._tcp.", // Internet Printing Protocol
        "_pdl-datastream._tcp.", // RAW Port / JetDirect
        "_printer._tcp." // LPD/LPR
        // you can add more specific ones if known, e.g., "_ipps._tcp." for IPP Secure
    )

    // Add the ArpScanner as a class variable
    private lateinit var arpScanner: ArpScanner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            
            // Initialize views
            healthStatusText = findViewById(R.id.healthStatusText)
            scanButton = findViewById(R.id.scanButton)
            speedTestButton = findViewById(R.id.speedTestButton)
            scanProgressBar = findViewById(R.id.scanProgressBar)
            deviceRecyclerView = findViewById(R.id.deviceRecyclerView)
            healthCheckRecyclerView = findViewById(R.id.healthCheckRecyclerView)
            networkInfoText = findViewById(R.id.networkInfoText)
            healthCheckPanelCard = findViewById(R.id.healthCheckPanelCard)
            networkInfoCard = findViewById(R.id.networkInfoCard)
            resultsContainer = findViewById(R.id.resultsContainer)
            speedTestPanelCard = findViewById(R.id.speedTestPanelCard)
            
            // Initialize service status views
            refreshServicesButton = findViewById(R.id.refreshServicesButton)
            infoButton = findViewById(R.id.infoButton)
            
            // Initialize individual service status text views
            beepitStatusText = findViewById(R.id.beepitStatusText)
            storehubHQStatusText = findViewById(R.id.storehubHQStatusText)
            storehubMeStatusText = findViewById(R.id.storehubMeStatusText)
            paymentAPIStatusText = findViewById(R.id.paymentAPIStatusText)
            
            // Initialize logo
            val logoImageView = findViewById<ImageView>(R.id.logoImageView)
            try {
                // Using a safer approach since the specific drawable might not exist
                logoImageView.setBackgroundColor(Color.parseColor("#FF6F00")) // Orange background as fallback
                val packageName = packageName
                val resourceId = resources.getIdentifier("storehub_logo", "drawable", packageName)
                if (resourceId != 0) {
                    logoImageView.setImageResource(resourceId)
                } else {
                    // Set text as the logo if image not available
                    logoImageView.setBackgroundColor(Color.parseColor("#FF6F00"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading logo: ${e.message}")
                // If failed to load the logo, set a fallback text
                logoImageView.setBackgroundColor(Color.parseColor("#FF6F00"))
            }
            
            // Initialize speed test result views
            val speedTestResultsView = findViewById<View>(R.id.speedTestResultsView)
            tvGatewayIp = speedTestResultsView.findViewById(R.id.tvGatewayIp)
            tvPingResult = speedTestResultsView.findViewById(R.id.tvPingResult)
            tvUploadResult = speedTestResultsView.findViewById(R.id.tvUploadResult)
            ivPingQuality = speedTestResultsView.findViewById(R.id.ivPingQuality)
            ivUploadQuality = speedTestResultsView.findViewById(R.id.ivUploadQuality)
            speedTestProgressLayout = speedTestResultsView.findViewById(R.id.speedTestProgressLayout)
            speedTestResultsLayout = speedTestResultsView.findViewById(R.id.speedTestResultsLayout)
            speedTestProgressText = speedTestResultsView.findViewById(R.id.speedTestProgressText)

            // Initialize views within healthCheckPanelCard
            healthStatusTextInCard = healthCheckPanelCard.findViewById(R.id.healthStatusText)
            healthCheckRecyclerViewInCard = healthCheckPanelCard.findViewById(R.id.healthCheckRecyclerView)
            
            // Initialize App Log UI
            appLogTextView = findViewById(R.id.appLogTextView)
            logScrollView = findViewById(R.id.logScrollView)
            clearLogButton = findViewById(R.id.clearLogButton)
            
            // Initialize log with useful starting information
            initializeAppLog()
            
            // Initialize animations
            fadeInAnimation = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
            fadeInAnimation.duration = 500 // 500ms
            
            slideInAnimation = AnimationUtils.loadAnimation(this, R.anim.slide_in_right)
            slideInAnimation.duration = 300 // 300ms
            
            // Initialize XprinterManager
            xprinterManager = XprinterManager.getInstance(this)
            xprinterManager.setup()
            
            // Extra validation for SDK initialization
            Log.d(TAG, "XprinterManager initialized and setup called. Printer ready: ${xprinterManager.isPrinterReady()}")
            
            // Initialize NsdManager
            nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
            
            try {
                posUdpNet = PosUdpNet()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize PosUdpNet", e)
                posUdpNet = null
            }
            
            deviceAdapter = DeviceAdapter(
                onTestPrintClick = { device -> 
                    performDirectPrint(device.ipAddress)
                },
                onDocketPrintClick = { device, quantity -> 
                    performDocketPrint(device.ipAddress, quantity)
                },
                onNetworkInfoClick = { device -> 
                    showNetworkInfoDialog(device)
                },
                onNetworkConfigClick = { device -> 
                    showNetworkConfigDialog(device)
                },
                coroutineScope = lifecycleScope
            )
            deviceRecyclerView.adapter = deviceAdapter
            deviceRecyclerView.layoutManager = LinearLayoutManager(this)
            
            // Initialize ARP scanner
            arpScanner = ArpScanner(this)
            
            healthCheckAdapter = HealthCheckAdapter()

            // Setup RecyclerView inside the panel
            healthCheckRecyclerViewInCard.layoutManager = LinearLayoutManager(this)
            healthCheckRecyclerViewInCard.adapter = healthCheckAdapter
            healthCheckAdapter.updateData(emptyList()) // Ensure it's initially empty

            checkAndRequestPermissions()
            
            // Show network info by default
            displayNetworkInfo()
            // No need to animate or show/hide since it's now permanently visible
            
            scanButton.setOnClickListener {
                onScanButtonClicked()
            }

            speedTestButton.setOnClickListener {
                showSpeedTestOptionsDialog()
            }

            clearLogButton.setOnClickListener {
                appLogTextView.text = ""
                addAppLog("Log cleared.")
            }
            
            // Initialize service status refresh button
            refreshServicesButton.setOnClickListener {
                performQuickServiceCheck()
            }
            
            // Initialize info button
            infoButton.setOnClickListener {
                showAppInfoDialog()
            }
            
            // Auto-run service check
            performQuickServiceCheck()
            
            // Initialize ArpScanner
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
            Toast.makeText(this, "Error initializing app: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun showDhcpQueryFailed(device: Device) {
        AlertDialog.Builder(this)
            .setTitle("DHCP Query Failed")
            .setMessage("Could not determine DHCP configuration for ${device.ipAddress}.\n\nThis could be because:\n- The printer doesn't support this command\n- The printer is not currently responding\n- The printer uses a different protocol")
            .setPositiveButton("OK", null)
            .show()
        
        addAppLog("Failed to query DHCP status from ${device.ipAddress}", "WARN")
    }
    
    // Helper method to hide all result views
    private fun hideAllResultViews() {
        // Note: networkInfoCard is now a separate element outside the results container
        // and should stay visible, so we don't hide it
        healthCheckPanelCard.visibility = View.GONE
        deviceRecyclerView.visibility = View.GONE
        speedTestPanelCard.visibility = View.GONE
    }
    
    // Helper method to show a view with animation
    private fun showWithAnimation(view: View) {
        // First hide all other result views
        hideAllResultViews()
        
        // Then show and animate the desired view
        view.visibility = View.VISIBLE
        view.startAnimation(fadeInAnimation)
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.INTERNET,
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE
        )

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            Log.i(TAG, "Requesting permissions: ${permissionsToRequest.joinToString()}")
            ActivityCompat.requestPermissions(this, permissionsToRequest, PERMISSIONS_REQUEST_CODE)
        } else {
            Log.i(TAG, "All required permissions already granted")
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val linkProperties = connectivityManager.getLinkProperties(network)
                return linkProperties?.linkAddresses?.firstOrNull { 
                    it.address is InetAddress && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress 
                }?.address?.hostAddress
            } else {
                @Suppress("DEPRECATION")
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val ipAddress = wifiManager.connectionInfo.ipAddress
                return String.format(
                    "%d.%d.%d.%d",
                    ipAddress and 0xff,
                    ipAddress shr 8 and 0xff,
                    ipAddress shr 16 and 0xff,
                    ipAddress shr 24 and 0xff
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP: ${e.message}")
            return null
        }
    }

    private fun getNetworkPrefix(): String? {
        val localIp = getLocalIpAddress() ?: return null
        return localIp.substring(0, localIp.lastIndexOf(".") + 1)
    }

    private fun getGatewayAddress(): String {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val linkProperties = connectivityManager.getLinkProperties(network)
                
                linkProperties?.routes?.forEach { route ->
                    if (route.gateway != null && route.isDefaultRoute) {
                        return route.gateway?.hostAddress ?: "Unknown"
                    }
                }
            }
            
            // Fallback for older Android versions or if no gateway found
            // Common gateway addresses are usually .1 or .254 in the subnet
            val localIp = getLocalIpAddress()
            if (localIp != null) {
                val ipParts = localIp.split(".")
                if (ipParts.size == 4) {
                    return "${ipParts[0]}.${ipParts[1]}.${ipParts[2]}.1"
                }
            }
            
            return "Unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting gateway address: ${e.message}")
            return "Unknown"
        }
    }

    private fun onScanButtonClicked() {
        if (isScanning || udpScanActive || nsdDiscoveryActive) {
            showStopScanDialog()
        } else {
            startPrioritizedScan()
        }
    }

    private fun startPrioritizedScan() {
        deviceAdapter.updateData(emptyList()) // Clear printer list
        
        // Show the deviceRecyclerView with animation
        showWithAnimation(deviceRecyclerView)
        
        isScanning = true
        scanButton.text = "Stop Scan"
        Log.i(TAG, "Starting prioritized scan process...")
        
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            if (networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                addAppLog("Please connect to WiFi for best printer discovery results", "WARN")
            }
        }
        
        acquireMulticastLock()
        startUdpPrinterSearch()
        
        udpTimeoutRunnable = Runnable {
            if (udpScanActive) {
                Log.i(TAG, "UDP scan timeout reached (${UDP_SCAN_TIMEOUT_MS}ms)")
                udpScanActive = false
                if (deviceAdapter.itemCount == 0) {
                    Log.w(TAG, "No printers found via UDP method")
                }
                addAppLog("UDP printer search completed", "INFO")
            }
        }
        udpTimeoutHandler.postDelayed(udpTimeoutRunnable!!, UDP_SCAN_TIMEOUT_MS)
        
        Handler(Looper.getMainLooper()).postDelayed({
            if (isScanning) {
                Log.i(TAG, "Starting secondary scan methods after ${UDP_INITIAL_WAIT_MS}ms delay")
                startScan()
                startNsdDiscovery()
                startArpScan()
            }
        }, UDP_INITIAL_WAIT_MS)
    }

    private fun startScan() {
        isScanning = true
        scanJob = lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            val duration = 30000L
            while (isActive && System.currentTimeMillis() - startTime < duration) {
                val remainingSeconds = (duration - (System.currentTimeMillis() - startTime)) / 1000
                scanButton.text = "Stop Scan (${remainingSeconds}s)"
                delay(1000)
            }
            if (isActive) { // Ensure job wasn't cancelled before calling stopScan
                stopScan()
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    deviceRecyclerView.visibility = View.VISIBLE // Ensure this is visible
                    healthCheckPanelCard.visibility = View.GONE // And this is hidden
                    addAppLog("Starting IP scan of your network...", "INFO")
                }
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifiManager.createMulticastLock("networkscannerlock")
                multicastLock?.let { lock ->
                    if (!isMulticastLockAcquired) {
                        lock.acquire()
                        isMulticastLockAcquired = true
                        Log.d(TAG, "Multicast lock acquired successfully")
                    }
                }

                val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = connectivityManager.activeNetwork
                val linkProperties = connectivityManager.getLinkProperties(network)
                
                val ipAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val linkAddress = linkProperties?.linkAddresses?.firstOrNull { 
                        it.address is InetAddress && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress 
                    }
                    if (linkAddress != null) {
                        currentNetworkPrefixLength = linkAddress.prefixLength
                        Log.d(TAG, "Network prefix length detected: $currentNetworkPrefixLength")
                    } else {
                        currentNetworkPrefixLength = 24
                        Log.d(TAG, "Defaulting to network prefix length: $currentNetworkPrefixLength")
                    }
                    linkAddress?.address?.hostAddress
                } else {
                    @Suppress("DEPRECATION")
                    val wifiInfo = wifiManager.connectionInfo
                    @Suppress("DEPRECATION")
                    val ipInt = wifiInfo.ipAddress
                    currentNetworkPrefixLength = 24
                    String.format("%d.%d.%d.%d", ipInt and 0xff, ipInt shr 8 and 0xff, ipInt shr 16 and 0xff, ipInt shr 24 and 0xff)
                }

                Log.i(TAG, "Local IP: $ipAddress, Network prefix length: $currentNetworkPrefixLength")
                val networkPrefix = ipAddress?.substring(0, ipAddress.lastIndexOf(".") + 1)
                
                if (networkPrefix == null) {
                    withContext(Dispatchers.Main) {
                        addAppLog("Could not determine network address. Please ensure you're connected to WiFi.", "ERROR")
                        stopScan() // Call stopScan to reset UI and state
                    }
                    return@launch
                }
                
                for (i in 1..254) {
                    if (!isScanning || !isActive) break // Check isActive for coroutine cancellation
                    val targetIp = "$networkPrefix$i"
                    try {
                        val device = discoverDevice(targetIp)
                        if (device != null) {
                            withContext(Dispatchers.Main) {
                                deviceAdapter.updateDevice(device)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error discovering device at $targetIp: ${e.message}")
                    }
                }

                withContext(Dispatchers.Main) {
                    if (deviceAdapter.itemCount == 0) {
                        addAppLog("No devices found. Make sure you're connected to the network.", "WARN")
                    } else {
                        addAppLog("IP Scan complete. Found ${deviceAdapter.itemCount} devices on network.", "INFO")
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) { // Don't log cancellation exceptions as errors
                    Log.e(TAG, "Error during scan: ${e.message}")
                    withContext(Dispatchers.Main) {
                        addAppLog("Scan failed: ${e.message}", "ERROR")
                    }
                }
            } finally {
                releaseMulticastLock()
                // Ensure stopScan is called on the main thread if the coroutine is still active
                // If scanJob initiated this, its own onCompletion will handle it.
                // This finally block is for the inner IO coroutine.
                // The primary stopScan should be from the scanJob timeout or manual button press.
            }
        }
    }

    private fun stopScan() {
        if (!isScanning && !udpScanActive && !nsdDiscoveryActive && scanJob?.isCompleted != false) {
            Log.d(TAG, "stopScan called but no scan appears to be active or job is already completed.")
            // return // Optionally return if no scan is truly active, to prevent redundant UI updates
        }
        Log.d(TAG, "stopScan: Toggling isScanning to false. Current state: isScanning=$isScanning, udpScanActive=$udpScanActive, nsdDiscoveryActive=$nsdDiscoveryActive, scanJobActive=${scanJob?.isActive}")

        isScanning = false
        scanJob?.cancel() // Cancel the countdown job
        scanButton.text = "Printer Scanner"
        releaseMulticastLock()
        
        if (udpTimeoutRunnable != null) {
            udpTimeoutHandler.removeCallbacks(udpTimeoutRunnable!!)
            udpTimeoutRunnable = null
        }
        udpScanActive = false 
        stopNsdDiscovery()
        
        // When stopping any scan, ensure device list is shown and health panel is appropriately managed
        // If health panel was meant to be static, this might need adjustment based on user flow
        // For now, printer scan button implies showing printer list and hiding health panel
        // deviceRecyclerView.visibility = View.VISIBLE // This is handled by onScanButtonClicked -> startPrioritizedScan
        // healthCheckPanelCard.visibility = View.GONE
        
        Log.d(TAG, "All scanning activities (IP, NSD, UDP) marked to stop.")
    }

    private fun releaseMulticastLock() {
        try {
            if (isMulticastLockAcquired) {
                multicastLock?.release()
                isMulticastLockAcquired = false
                Log.d(TAG, "Multicast lock released.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing multicast lock: ${e.message}")
        }
    }

    private fun showStopScanDialog() {
        AlertDialog.Builder(this)
            .setTitle("Stop Scan")
            .setMessage("Do you want to stop the current scan?")
            .setPositiveButton("Yes") { _, _ -> stopScan() }
            .setNegativeButton("No", null)
            .show()
    }

    private suspend fun getPublicIpAddress(): Pair<String, String> {
        return withContext(Dispatchers.IO) {
            try {
                // Use ip-api.com which provides ISP information
                val url = URL("http://ip-api.com/json/?fields=query,isp,country,org,as")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                
                // Parse JSON response
                val jsonObject = org.json.JSONObject(response)
                val ipAddress = jsonObject.getString("query")
                val isp = jsonObject.getString("isp")
                val country = jsonObject.getString("country")
                // Additional fields that can help with ISP identification
                val org = if (jsonObject.has("org")) jsonObject.getString("org") else ""
                val asn = if (jsonObject.has("as")) jsonObject.getString("as") else ""
                
                Log.d(TAG, "Public IP: $ipAddress, ISP: $isp, Country: $country, Org: $org, AS: $asn")
                
                Pair(ipAddress, "$isp|$org|$asn|$country")
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching public IP and ISP: ${e.message}", e)
                Pair("Unknown", "Unknown")
            }
        }
    }

    private suspend fun discoverDevice(ipAddress: String): Device? {
        return withContext(Dispatchers.IO) {
            try {
                // Step 1: Check for printer ports first
                val isPrinterByPort = checkPrinterPorts(ipAddress)
                var latency = "N/A" // Default latency
                val deviceInfo = getDeviceFromArpCache(ipAddress) // Called regardless of method

                if (isPrinterByPort) {
                    // Device identified as a printer by open port.
                    try {
                        val process = Runtime.getRuntime().exec("ping -c 1 -W 1 $ipAddress")
                        val exitCode = process.waitFor()
                        if (exitCode == 0) {
                            val output = process.inputStream.bufferedReader().use { it.readText() }
                            val pattern = "time=(\\d+\\.?\\d*) ms".toRegex()
                            val match = pattern.find(output)
                            latency = match?.groupValues?.get(1) ?: "0"
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Ping for latency check failed for printer $ipAddress: ${e.message}")
                    }
                    
                    Device(
                        macAddress = deviceInfo.first,
                        deviceName = if (deviceInfo.second != "Unknown") "Printer: ${deviceInfo.second}" else "Printer: $ipAddress",
                        ipAddress = ipAddress,
                        latency = latency,
                        isPrinter = true,
                        discoveryMethod = DiscoveryMethod.IP_SCAN
                    )
                } else {
                    // Not identified as a printer by port scan. Try generic ICMP ping for other devices.
                    val process = Runtime.getRuntime().exec("ping -c 1 -W 1 $ipAddress")
                    val exitCode = process.waitFor()
                    
                    if (exitCode == 0) { // Ping successful
                        val output = process.inputStream.bufferedReader().use { it.readText() }
                        val pattern = "time=(\\d+\\.?\\d*) ms".toRegex()
                        val match = pattern.find(output)
                        latency = match?.groupValues?.get(1) ?: "0"
                        
                        // Gateway naming logic
                        val finalDeviceName = if (ipAddress.endsWith(".1") && currentNetworkPrefixLength == 24) {
                            "Gateway/Router"
                        } else {
                            if (deviceInfo.second != "Unknown") deviceInfo.second else ipAddress
                        }
                        
                        Device(
                            macAddress = deviceInfo.first,
                            deviceName = finalDeviceName,
                            ipAddress = ipAddress,
                            latency = latency,
                            isPrinter = false,
                            discoveryMethod = DiscoveryMethod.IP_SCAN
                        )
                    } else {
                        null // No open printer ports and no ping response
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error discovering device $ipAddress: ${e.message}")
                null
            }
        }
    }

    private suspend fun checkPrinterPorts(ipAddress: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Checking printer ports for $ipAddress")
                
                // First, try a more reliable HTTP check to see if the device is a printer
                try {
                    val url = URL("http://$ipAddress/")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 2000
                    connection.readTimeout = 2000
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", "NetworkScanner/1.0")
                    
                    val responseCode = connection.responseCode
                    Log.d(TAG, "HTTP response code for $ipAddress: $responseCode")
                    
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        // Check response headers or body for printer identification
                        val server = connection.getHeaderField("Server")
                        val contentType = connection.getHeaderField("Content-Type")
                        val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                        
                        Log.d(TAG, "HTTP server header: $server, Content-Type: $contentType")
                        
                        // Check if any indication of a printer in the response
                        if (server?.contains("Printer", ignoreCase = true) == true || 
                            responseBody.contains("Printer", ignoreCase = true) ||
                            responseBody.contains("print", ignoreCase = true)) {
                            Log.i(TAG, "Device at $ipAddress identified as printer via HTTP content")
                            return@withContext true
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "HTTP check failed for $ipAddress: ${e.message}")
                    // Continue with port checks
                }
                
                // Then try the standard printer ports
                for (port in printerPorts) {
                    Log.d(TAG, "Attempting to connect to $ipAddress:$port")
                    try {
                        val socket = java.net.Socket()
                        socket.connect(java.net.InetSocketAddress(ipAddress, port), 3000)
                        Log.i(TAG, "Successfully connected to $ipAddress:$port - Device is likely a printer.")
                        
                        // For some printer ports, attempt a protocol-specific check
                        if (port == 9100) {
                            try {
                                // Very minimal and safe printer status request for port 9100
                                val outputStream = socket.getOutputStream()
                                outputStream.write(byteArrayOf(0x1B, 0x76)) // ESC v - transmit printer status
                                outputStream.flush()
                                
                                // Set a short timeout
                                socket.soTimeout = 1000
                                
                                // Check if there's any response
                                val inputStream = socket.getInputStream()
                                val byte = ByteArray(1)
                                val read = inputStream.read(byte)
                                
                                if (read > 0) {
                                    Log.i(TAG, "Device at $ipAddress:$port responded to printer status request")
                                    socket.close()
                                    return@withContext true
                                }
                            } catch (e: Exception) {
                                Log.d(TAG, "Printer protocol check failed on $ipAddress:$port - ${e.message}")
                                // If this fails, we still return true based on the port being open
                            }
                        }
                        
                        socket.close()
                        return@withContext true
                    } catch (e: java.net.SocketTimeoutException) {
                        Log.d(TAG, "Timeout connecting to $ipAddress:$port - ${e.message}")
                        continue // Try next port
                    } catch (e: java.net.ConnectException) {
                        Log.d(TAG, "Connection refused on $ipAddress:$port - ${e.message}")
                        continue // Try next port
                    } catch (e: Exception) {
                        Log.w(TAG, "Error checking port $ipAddress:$port - ${e.message}")
                        continue // Try next port, but log as a warning
                    }
                }
                Log.d(TAG, "No common printer ports found open for $ipAddress")
                false
            } catch (e: Exception) {
                Log.e(TAG, "General error in checkPrinterPorts for $ipAddress: ${e.message}")
                false
            }
        }
    }

    private fun getDeviceFromArpCache(ipAddress: String): Pair<String, String> {
        try {
            val process = Runtime.getRuntime().exec("cat /proc/net/arp")
            val reader = process.inputStream.bufferedReader()
            var line: String?
            var macAddress = "Unknown"
            var deviceName = "Unknown"

            while (reader.readLine().also { line = it } != null) {
                if (line?.contains(ipAddress) == true) {
                    val parts = line?.split("\\s+".toRegex())
                    if (parts?.size ?: 0 > 3) {
                        macAddress = parts?.get(3) ?: "Unknown"
                        // Try to get device name using getHostName
                        try {
                            val inetAddress = InetAddress.getByName(ipAddress)
                            deviceName = inetAddress.hostName ?: "Unknown"
                        } catch (e: Exception) {
                            deviceName = "Unknown"
                        }
                    }
                    break
                }
            }
            return Pair(macAddress, deviceName)
        } catch (e: Exception) {
            return Pair("Unknown", "Unknown")
        }
    }

    private fun performHealthCheck() {
        // Show health check panel with animation
        showWithAnimation(healthCheckPanelCard)
        
        healthStatusTextInCard.text = "Checking service health..."
        healthStatusTextInCard.visibility = View.VISIBLE
        healthCheckRecyclerViewInCard.visibility = View.GONE 
        healthCheckAdapter.updateData(emptyList()) // Clear previous results before new check
        
        // Also reset the status in the compact view
        setAllStatusesToChecking()
        
        lifecycleScope.launch(Dispatchers.IO) {
            val healthChecks = mutableListOf<HealthCheckResult>()
            var totalServices = 0
            var onlineServices = 0
            
            for ((serviceName, url) in healthCheckServices) {
                totalServices++
                try {
                    Log.d(TAG, "Checking service: $serviceName at URL: $url")
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", "NetworkScanner/1.0")
                    val responseCode = connection.responseCode
                    Log.d(TAG, "Response code for $serviceName: $responseCode")
                    
                    if (responseCode == 200) {
                        onlineServices++
                        healthChecks.add(HealthCheckResult(serviceName, url, "Online"))
                        
                        // Update the individual service status
                        withContext(Dispatchers.Main) {
                            updateServiceStatus(serviceName, true)
                        }
                    } else {
                        val status = "Offline (HTTP $responseCode)"
                        healthChecks.add(HealthCheckResult(serviceName, url, status))
                        
                        // Update the individual service status
                        withContext(Dispatchers.Main) {
                            updateServiceStatus(serviceName, false)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking $serviceName: ${e.message}", e)
                    healthChecks.add(HealthCheckResult(serviceName, url, "Error")) // Show 'Error' instead of 'Offline' for exceptions
                    
                    // Update the individual service status
                    withContext(Dispatchers.Main) {
                        updateServiceStatus(serviceName, false)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                healthCheckAdapter.updateData(healthChecks)

                if (healthChecks.isEmpty()) { // Should not happen with current logic, but good check
                    healthStatusTextInCard.text = "No services to check."
                    healthStatusTextInCard.visibility = View.VISIBLE
                    healthCheckRecyclerViewInCard.visibility = View.GONE
                } else if (healthChecks.all { it.status == "Error" }) {
                     healthStatusTextInCard.text = "Error checking services."
                     healthStatusTextInCard.visibility = View.VISIBLE
                     healthCheckRecyclerViewInCard.visibility = View.VISIBLE // Show list with error statuses
                } else {
                    healthStatusTextInCard.visibility = View.GONE // Hide status text if list has items
                    healthCheckRecyclerViewInCard.visibility = View.VISIBLE
                }
                
                // Animate the recycler view
                healthCheckRecyclerViewInCard.startAnimation(slideInAnimation)
                
                // Add a summary log entry
                if (onlineServices == totalServices) {
                    addAppLog("All StoreHub services are online ($onlineServices/$totalServices)", "INFO")
                } else if (onlineServices > 0) {
                    addAppLog("Some StoreHub services are offline ($onlineServices/$totalServices online)", "WARN")
                } else {
                    addAppLog("All StoreHub services appear to be offline!", "ERROR")
                }
            }
        }
    }

    private fun printSampleReceipt(ipAddress: String) {
        xprinterManager.connectPrinter(ipAddress, object : IConnectListener {
            override fun onStatus(status: Int, message: String, extra: String) {
                runOnUiThread {
                    if (status == 0) {
                        // Successfully connected, now print sample receipt
                        try {
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val localIp = getLocalIpAddress() ?: "Unknown"
                                    val dateTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                                    
                                    xprinterManager.printer?.apply {
                                        initializePrinter()
                                        
                                        // Header with app info
                                        printText("NetworkScanner Sample\n", 2, 1, 1)
                                        printText("--------------------------------\n", 1, 0, 0)
                                        
                                        // Printer details
                                        printText("PRINTER INFORMATION\n", 1, 1, 0)
                                        printText("IP Address: $ipAddress\n", 1, 0, 0)
                                        printText("Discovery Method: ${deviceAdapter.getDeviceByIp(ipAddress)?.discoveryMethod ?: "Unknown"}\n", 1, 0, 0)
                                        printText("Date/Time: $dateTime\n", 1, 0, 0)
                                        printText("--------------------------------\n", 1, 0, 0)
                                        
                                        // Network details
                                        printText("NETWORK INFORMATION\n", 1, 1, 0)
                                        printText("Local IP: $localIp\n", 1, 0, 0)
                                        printText("--------------------------------\n", 1, 0, 0)
                                        
                                        // Test different formatting options
                                        printText("FORMATTING TESTS\n", 1, 1, 0)
                                        printText("Standard Text\n", 1, 0, 0)
                                        printText("Bold Text\n", 1, 1, 0)
                                        printText("Large Text\n", 2, 0, 0)
                                        printText("Large Bold\n", 2, 1, 0)
                                        printText("Double Height\n", 1, 0, 1)
                                        printText("--------------------------------\n", 1, 0, 0)
                                        
                                        // QR Code
                                        printText("SCAN QR CODE\n", 1, 1, 0)
                                        printQRCode("https://example.com/networkscanner", 8, 1)
                                        printText("\n", 1, 0, 0)
                                        
                                        // Feed and cut
                                        feedLine(3)
                                        cutPaper()
                                    }
                                    
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@MainActivity, "Sample receipt printed successfully", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error printing sample receipt", e)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@MainActivity, "Error printing sample: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error launching coroutine for sample print", e)
                            Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val errorMessage = "Failed to connect printer: $message (Code: $status)"
                        Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun performPaperCut(ipAddress: String) {
        // Instead of the complex printer connection process, try a direct cut command
        Log.d(TAG, "Attempting direct paper cut to $ipAddress")
        Toast.makeText(this, "Sending paper cut command...", Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val socket = java.net.Socket()
                
                try {
                    // Connect to standard printer port
                    socket.connect(java.net.InetSocketAddress(ipAddress, 9100), 5000)
                    
                    if (socket.isConnected) {
                        val outputStream = socket.getOutputStream()
                        
                        // Standard ESC/POS cut command
                        outputStream.write(byteArrayOf(
                            0x1B, 0x40,       // ESC @ - Initialize printer
                            0x1D, 0x56, 0x00  // GS V 0 - Cut paper (partial cut)
                        ))
                        
                        // Alternative cut command
                        outputStream.write(byteArrayOf(0x1D, 0x56, 0x41, 0x10)) // GS V A 16 - Cut paper (full cut)
                        
                        outputStream.flush()
                        socket.close()
                        
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Paper cut command sent", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Could not connect to printer", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Paper cut error: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Paper cut error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    if (!socket.isClosed) {
                        try {
                            socket.close()
                        } catch (e: Exception) { /* Ignore close errors */ }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Paper cut operation failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Paper cut failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sendCutCommand() {
        try {
            xprinterManager.cutPaper(object : IPOSListener {
                override fun onStatus(status: Int, message: String) {
                    runOnUiThread {
                        if (status == 0) {
                            Toast.makeText(this@MainActivity, "Paper cut command sent successfully", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Paper cut error: $message (Code: $status)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error sending cut command", e)
            Toast.makeText(this, "Error cutting paper: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPrinterStatus(ipAddress: String) {
        Log.d(TAG, "Checking printer status for $ipAddress")
        addAppLog("Checking printer status...", "INFO")
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pingable = pingHost(ipAddress)
                if (!pingable) {
                    withContext(Dispatchers.Main) {
                        addAppLog("Printer at $ipAddress is not responding to ping", "ERROR")
                    }
                    return@launch
                }
                
                withContext(Dispatchers.Main) {
                    addAppLog("Printer is reachable", "INFO")
                }
                
                // Check if port 9100 is open
                val socketOpen = isPortOpen(ipAddress, 9100)
                
                if (socketOpen) {
                    withContext(Dispatchers.Main) {
                        addAppLog("Printer port 9100 is open", "INFO")
                    }
                    
                    // Get status using POS commands
                    val socket = Socket()
                    socket.connect(InetSocketAddress(ipAddress, 9100), 5000)
                    socket.soTimeout = 5000 // Set timeout for reads
                    
                    try {
                        val outputStream = socket.getOutputStream()
                        val inputStream = socket.getInputStream()
                        
                        // Send real-time status request
                        outputStream.write(byteArrayOf(0x10, 0x04, 0x01)) // DLE EOT n
                        outputStream.flush()
                        
                        var statusByte: Int = -1
                        var bytesRead = 0
                        val buffer = ByteArray(32)
                        val startTime = System.currentTimeMillis()
                        val timeout = 2000 // 2 second timeout for status response
                        
                        // Try to read status response with timeout
                        try {
                            while (bytesRead == 0 && System.currentTimeMillis() - startTime < timeout) {
                                if (inputStream.available() > 0) {
                                    bytesRead = inputStream.read(buffer)
                                    if (bytesRead > 0) {
                                        statusByte = buffer[0].toInt() and 0xFF
                                    }
                                }
                                delay(100) // Short delay before checking again
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error reading status response: ${e.message}")
                        }
                        
                        if (bytesRead > 0) {
                            val statusMsg = when {
                                ((statusByte and 0x20) != 0) -> "ERROR: Printer has errors (possibly offline or out of paper)"
                                ((statusByte and 0x08) != 0) -> "WARNING: Paper is low"
                                ((statusByte and 0x04) != 0) -> "WARNING: Printer is overheating"
                                ((statusByte and 0x02) != 0) -> "INFO: Printer is online and idle"
                                else -> "STATUS: Printer responded with status code: $statusByte"
                            }
                            
                            withContext(Dispatchers.Main) {
                                addAppLog(statusMsg, if (statusMsg.startsWith("ERROR")) "ERROR" else if (statusMsg.startsWith("WARNING")) "WARN" else "INFO")
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                addAppLog("No status response from printer", "WARN")
                            }
                            
                            // If there's no response to status, try resetting the printer
                            try {
                                outputStream.write(byteArrayOf(0x1B, 0x40)) // ESC @
                                outputStream.flush()
                                withContext(Dispatchers.Main) {
                                    addAppLog("Printer did not respond to status request, but is connected", "WARN")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error sending reset after failed status check: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking printer status: ${e.message}")
                        withContext(Dispatchers.Main) {
                            addAppLog("Error checking status: ${e.message}", "ERROR")
                        }
                    } finally {
                        try {
                            socket.close()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error closing socket: ${e.message}")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        addAppLog("Printer port is closed", "ERROR")
                    }
                    
                    val address = InetAddress.getByName(ipAddress)
                    if (address.isReachable(1000)) {
                        withContext(Dispatchers.Main) {
                            addAppLog("Printer is reachable but not accepting connections on port 9100", "WARN")
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
                Log.e(TAG, "Socket timeout checking status: ${e.message}")
                withContext(Dispatchers.Main) {
                    addAppLog("Socket error: ${e.message}", "ERROR")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in checkPrinterStatus: ${e.message}")
                withContext(Dispatchers.Main) {
                    addAppLog("Error checking status: ${e.message}", "ERROR")
                }
            }
        }
    }

    private fun connectToPrinter(ipAddress: String) {
        try {
            Log.d(TAG, "Starting connection to printer at $ipAddress")
            Toast.makeText(this, "Connecting to printer...", Toast.LENGTH_SHORT).show()
            
            xprinterManager.connectPrinter(ipAddress, object : IConnectListener {
                override fun onStatus(status: Int, message: String, extra: String) {
                    Log.d(TAG, "Printer connection status: $status, message: $message, extra: $extra")
                    
                    runOnUiThread {
                        if (status == 0) {
                            Toast.makeText(this@MainActivity, "Printer connected successfully", Toast.LENGTH_SHORT).show()
                            Log.d(TAG, "Printer connected successfully, calling printTestPage()")
                            printTestPage()
                        } else {
                            val errorMessage = "Failed to connect printer: $message (Code: $status)"
                            Log.e(TAG, errorMessage)
                            Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating printer connection", e)
            Toast.makeText(this, "Error connecting to printer: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun printTestPage() {
        try {
            Log.d(TAG, "Starting printTestPage(), posPrinter is ${if (xprinterManager.printer != null) "available" else "NULL"}")
            
            if (xprinterManager.printer == null) {
                Log.e(TAG, "Cannot print test page - printer is null")
                Toast.makeText(this, "Error: Printer connection not established", Toast.LENGTH_SHORT).show()
                return
            }
            
            xprinterManager.printTestPage(object : IPOSListener {
                override fun onStatus(status: Int, message: String) {
                    Log.d(TAG, "Print test page status: $status, message: $message")
                    
                    runOnUiThread {
                        if (status == 0) {
                            Toast.makeText(this@MainActivity, "Test page command sent: $message", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Print test page error: $message (Code: $status)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating printTestPage", e)
            Toast.makeText(this, "Error printing test page: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getAndDisplayPrinterStatus() {
        try {
            xprinterManager.getPrinterStatus(object : IStatusCallback {
                override fun receive(status: Int) {
                    runOnUiThread {
                        val statusMessage = when (status) {
                            0 -> "Printer Normal (or specific success code)"
                            8 -> "Printer Out of Paper"
                            32 -> "Printer Cover Open"
                            16 -> "Cutter Error (if applicable)"
                            64 -> "Offline (or specific error code)"
                            -100 -> "Printer not connected (XprinterManager)"
                            -101 -> "Error calling getStatus (XprinterManager)"
                            else -> "Printer Status Code: $status"
                        }
                        Toast.makeText(this@MainActivity, statusMessage, Toast.LENGTH_LONG).show()
                        Log.d(TAG, "Printer Status Received: $statusMessage")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating getPrinterStatus", e)
            Toast.makeText(this, "Error getting printer status: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            
            if (allGranted) {
                Log.i(TAG, "All requested permissions were granted")
                // Now we can start all operations that require permissions
                displayNetworkInfo()
            } else {
                val deniedPermissions = permissions.filterIndexed { index, _ -> 
                    grantResults[index] != PackageManager.PERMISSION_GRANTED 
                }
                Log.w(TAG, "Some permissions were denied: ${deniedPermissions.joinToString()}")
                Toast.makeText(
                    this,
                    "Some permissions were denied. App functionality may be limited.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun displayNetworkInfo() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkInfo = buildString {
            // Add bold and underlined title with proper HTML line breaks
            append("<b><u>NETWORK INFORMATION</u></b><br><br>")
            Log.d(TAG, "Building network info string...")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
                val linkProperties = connectivityManager.getLinkProperties(network)

                Log.d(TAG, "Active Network: $network")
                Log.d(TAG, "Network Capabilities: $networkCapabilities")
                Log.d(TAG, "Link Properties: $linkProperties")

                if (networkCapabilities != null) {
                    if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        append("Type: \u2022 Wi-Fi<br>")
                        @Suppress("DEPRECATION")
                        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        @Suppress("DEPRECATION")
                        val wifiInfo = wifiManager.connectionInfo
                        append("SSID: \u2022 ${wifiInfo.ssid}<br>")
                        Log.d(TAG, "Network Type: Wi-Fi, SSID: ${wifiInfo.ssid}")
                    } else if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        append("Type: \u2022 Ethernet<br>")
                        Log.d(TAG, "Network Type: Ethernet")
                    } else {
                        append("Type: \u2022 Other<br>")
                        Log.d(TAG, "Network Type: Other")
                    }

                    linkProperties?.let {
                        Log.d(TAG, "Link Addresses count: ${it.linkAddresses.size}")
                        for (linkAddress in it.linkAddresses) {
                            Log.d(TAG, "Processing LinkAddress: $linkAddress, Address: ${linkAddress.address}, Is Loopback: ${linkAddress.address.isLoopbackAddress}, Is LinkLocal: ${linkAddress.address.isLinkLocalAddress}")
                            if (linkAddress.address is InetAddress && !linkAddress.address.isLoopbackAddress && !linkAddress.address.isLinkLocalAddress) {
                                val ipAddr = linkAddress.address.hostAddress
                                val prefix = linkAddress.prefixLength
                                append("IP: \u2022 $ipAddr<br>")
                                append("Subnet: \u2022 /$prefix<br>")
                                currentNetworkPrefixLength = prefix // Store prefix length
                                Log.d(TAG, "Added IP: $ipAddr, CIDR: /$prefix")
                            } else {
                                Log.d(TAG, "Skipped LinkAddress: $linkAddress")
                            }
                        }
                        Log.d(TAG, "Route Infos count: ${it.routes.size}")
                        it.routes.forEach { route ->
                            Log.d(TAG, "Processing RouteInfo: $route, Gateway: ${route.gateway}, Is Default: ${route.isDefaultRoute}")
                            if (route.gateway != null && route.isDefaultRoute) {
                                route.gateway?.let { gateway ->
                                    append("Gateway: \u2022 ${gateway.hostAddress}<br>")
                                    Log.d(TAG, "Added Gateway: ${gateway.hostAddress}")
                                }
                            }
                        }
                    } ?: run {
                        append("LinkProperties not available.<br>")
                        Log.w(TAG, "LinkProperties is null.")
                    }
                } else {
                    append("No active network connection or capabilities not available.<br>")
                    Log.w(TAG, "Active network is null or networkCapabilities is null.")
                }
            } else { // Fallback for older Android versions
                @Suppress("DEPRECATION")
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val wifiInfo = wifiManager.connectionInfo
                @Suppress("DEPRECATION")
                if (wifiInfo.ipAddress != 0) {
                    append("Type: Wi-Fi<br>")
                    @Suppress("DEPRECATION")
                    append("IP Address: ${String.format("%d.%d.%d.%d", 
                        wifiInfo.ipAddress and 0xff,
                        wifiInfo.ipAddress shr 8 and 0xff,
                        wifiInfo.ipAddress shr 16 and 0xff,
                        wifiInfo.ipAddress shr 24 and 0xff)}<br>")
                    append("Subnet: N/A (Legacy API)<br>")
                    append("Gateway: N/A (Legacy API)<br>")
                    Log.d(TAG, "Using legacy API for network info.")
                } else {
                    append("Network Info: Not available (Legacy API or no Wi-Fi)<br>")
                    Log.w(TAG, "Legacy API: No Wi-Fi info.")
                }
            }

            // Public IP info
            append("Public IP: \u2022 Fetching...<br>")
            append("ISP: \u2022 Fetching...")
        }
        
        // Set the text as HTML to support formatting
        networkInfoText.text = HtmlCompat.fromHtml(networkInfo.toString(), HtmlCompat.FROM_HTML_MODE_LEGACY)

        // Fetch public IP and ISP in background
        lifecycleScope.launch {
            try {
                val (publicIp, ispData) = getPublicIpAddress()
                
                val parts = ispData.split("|")
                val isp = parts[0]
                val org = if (parts.size > 1) parts[1] else ""
                val asn = if (parts.size > 2) parts[2] else ""
                val country = if (parts.size > 3) parts[3] else ""
                
                var newHtmlText = networkInfo.toString()
                    .replace("Public IP: • Fetching...", "Public IP: • $publicIp")
                    .replace("ISP: • Fetching...", "ISP: • $isp")
                
                // Identify country from IP geolocation data
                val countryCode = when {
                    country.contains("Malaysia", ignoreCase = true) -> "MY"
                    country.contains("Thailand", ignoreCase = true) -> "TH"
                    country.contains("Philippines", ignoreCase = true) -> "PH"
                    else -> ""
                }
                
                // Combined patterns to match different variations of ISP names
                val ispInfo = identifyISP(isp, org, asn, countryCode)
                
                // Update with ISP info
                newHtmlText = newHtmlText.replace("ISP: • $isp", "ISP: • $ispInfo")
                
                networkInfoText.text = HtmlCompat.fromHtml(newHtmlText, HtmlCompat.FROM_HTML_MODE_LEGACY)
                addAppLog("Network info updated with Public IP: $publicIp, ISP: $ispInfo", "INFO")
            } catch (e: Exception) {
                val newHtmlText = networkInfo.toString()
                    .replace("Public IP: • Fetching...", "Public IP: • Could not obtain")
                    .replace("ISP: • Fetching...", "ISP: • Could not determine")
                networkInfoText.text = HtmlCompat.fromHtml(newHtmlText, HtmlCompat.FROM_HTML_MODE_LEGACY)
                Log.e(TAG, "Error fetching public IP and ISP: ${e.message}")
            }
        }
    }
    
    private fun performLanSpeedTest() {
        val gatewayIp = getGatewayAddress()
        if (gatewayIp == "Unknown") {
            Toast.makeText(this, "Gateway not detected. Please scan network first.", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Show the speed test card with progress layout
        hideAllResultViews()
        speedTestPanelCard.visibility = View.VISIBLE
        speedTestPanelCard.startAnimation(fadeInAnimation)
        
        // Show progress and hide results
        speedTestProgressLayout.visibility = View.VISIBLE
        speedTestResultsLayout.visibility = View.GONE
        speedTestProgressText.text = "Testing connection to gateway ($gatewayIp)...\nThis will take a few seconds."
        
        // Create speed test manager
        val speedTestManager = LanSpeedTestManager(this)
        
        lifecycleScope.launch {
            try {
                val result = speedTestManager.performSpeedTest(gatewayIp)
                
                if (result.status == "Completed") {
                    // Display results in the speed test panel
                    displaySpeedTestResults(result, gatewayIp)
                } else {
                    // Show error and hide the panel
                    Toast.makeText(this@MainActivity, "Speed test failed: ${result.status}", Toast.LENGTH_LONG).show()
                    speedTestPanelCard.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during speed test: ${e.message}", e)
                Toast.makeText(this@MainActivity, "Error during speed test: ${e.message}", Toast.LENGTH_LONG).show()
                speedTestPanelCard.visibility = View.GONE
            }
        }
    }
    
    private fun displaySpeedTestResults(result: LanSpeedTestManager.SpeedTestResult, gatewayIp: String) {
        // Hide progress and show results
        speedTestProgressLayout.visibility = View.GONE
        speedTestResultsLayout.visibility = View.VISIBLE
        
        // Set the result values in the panel
        tvGatewayIp.text = gatewayIp
        tvPingResult.text = "${result.pingMs.toInt()} ms"
        tvUploadResult.text = "${String.format("%.2f", result.uploadMbps)} Mbps"
        
        // Set quality indicators
        setPingQualityIndicator(ivPingQuality, result.pingMs)
        setSpeedQualityIndicator(ivUploadQuality, result.uploadMbps)
    }
    
    private fun setPingQualityIndicator(imageView: ImageView, latencyMs: Float) {
        val quality = LanSpeedTestManager.getPingQuality(latencyMs)
        val colorResId = when (quality) {
            SpeedQuality.EXCELLENT -> android.R.drawable.presence_online
            SpeedQuality.GOOD -> android.R.drawable.presence_online
            SpeedQuality.AVERAGE -> android.R.drawable.presence_away
            SpeedQuality.POOR -> android.R.drawable.presence_busy
            SpeedQuality.BAD -> android.R.drawable.presence_offline
            else -> android.R.drawable.presence_invisible
        }
        
        val colorInt = when (quality) {
            SpeedQuality.EXCELLENT -> Color.parseColor("#4CAF50") // Green
            SpeedQuality.GOOD -> Color.parseColor("#8BC34A") // Light Green
            SpeedQuality.AVERAGE -> Color.parseColor("#FFC107") // Amber
            SpeedQuality.POOR -> Color.parseColor("#FF9800") // Orange
            SpeedQuality.BAD -> Color.parseColor("#F44336") // Red
            else -> Color.GRAY
        }
        
        imageView.setImageResource(colorResId)
        imageView.setColorFilter(colorInt)
    }

    override fun onDestroy() {
        super.onDestroy()
         
        // Clean up printer resources
        try {
            xprinterManager.disconnectPrinter()
            Log.d(TAG, "Printer disconnected on activity destroy")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting printer on destroy: ${e.message}")
        }
         
        releaseMulticastLock()
        stopNsdDiscovery()
    }

    private fun initializeNsd() {
        Log.d(TAG, "Initializing NSD resolve listener...")
        nsdResolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD Resolve failed for ${serviceInfo.serviceName}: Error code: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                // Improved logging with all available service info
                Log.i(TAG, "NSD Service resolved: ${serviceInfo.serviceName}")
                Log.i(TAG, "  - Host addresses: ${serviceInfo.hostAddresses.joinToString()}")
                Log.i(TAG, "  - Port: ${serviceInfo.port}")
                Log.i(TAG, "  - Service type: ${serviceInfo.serviceType}")
                Log.i(TAG, "  - Network interface: ${serviceInfo.network}")
                Log.i(TAG, "  - Attributes: ${serviceInfo.attributes.keys.joinToString()}")
                
                val deviceName = serviceInfo.serviceName ?: "Unknown Printer"
                val ipAddress = serviceInfo.hostAddresses.firstOrNull()?.hostAddress ?: return // IP address is crucial
                
                // Create a Device object or update if already exists
                // We need to ensure thread safety if updating shared list from multiple discovery methods
                // For now, let's assume direct update to adapter for simplicity, will refine
                val newDevice = Device(
                    macAddress = "N/A (NSD)", // MAC not directly available via NSD
                    deviceName = "Printer: $deviceName",
                    ipAddress = ipAddress,
                    latency = "N/A (NSD)",
                    isPrinter = true,
                    discoveryMethod = DiscoveryMethod.NSD
                )
                
                runOnUiThread {
                    // Show toast when a printer is found via NSD
                    Toast.makeText(
                        this@MainActivity,
                        "Found printer via NSD: $deviceName",
                        Toast.LENGTH_SHORT
                    ).show()
                    
                    // Check if device already exists from IP scan to avoid duplicates, or update it
                    // We need to ensure thread safety if updating shared list from multiple discovery methods
                    // For now, let's assume direct update to adapter for simplicity, will refine
                    val existingDevice = deviceAdapter.getDeviceByIp(ipAddress)
                    if (existingDevice == null) {
                        deviceAdapter.addDevice(newDevice)
                    } else {
                        // If UDP already found it, prefer that discovery method
                        if (existingDevice.discoveryMethod == DiscoveryMethod.UDP) {
                            // Keep as UDP, just ensure it's marked as a printer
                            if (!existingDevice.isPrinter) {
                                deviceAdapter.updateDevice(existingDevice.copy(isPrinter = true))
                            }
                        } else {
                            // Optionally update existing device if NSD provides more/better info
                            // and it wasn't found via UDP (which we prioritize)
                            val updatedDevice = existingDevice.copy(
                                isPrinter = true, 
                                deviceName = "Printer: $deviceName",
                                discoveryMethod = DiscoveryMethod.NSD
                            )
                            deviceAdapter.updateDevice(updatedDevice)
                        }
                    }
                }
            }
        }

        Log.d(TAG, "Initializing NSD discovery listener...")
        nsdDiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "NSD Service discovery started for type: $regType")
                nsdDiscoveryActive = true
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.i(TAG, "NSD Service found: ${service.serviceName} of type ${service.serviceType}")
                // Log the host addresses when they are available
                if (service.hostAddresses.isNotEmpty()) {
                    Log.i(TAG, "  - Host addresses: ${service.hostAddresses.joinToString()}")
                }
                
                // Resolve services that are likely printers
                if (PRINTER_SERVICE_TYPES.any { service.serviceType.startsWith(it) }) {
                    Log.d(TAG, "Attempting to resolve printer service: ${service.serviceName}")
                    if (nsdResolveListener != null) {
                        try {
                            @Suppress("DEPRECATION")
                            nsdManager.resolveService(service, nsdResolveListener!!)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error resolving service: ${e.message}", e)
                        }
                    } else {
                        Log.e(TAG, "nsdResolveListener is null, cannot resolve service")
                    }
                } else {
                    Log.d(TAG, "Service type doesn't match our printer types, ignoring")
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.w(TAG, "NSD Service lost: ${service.serviceName}")
                // Optionally remove from list, or mark as offline
                // For now, we'll just log it
                 runOnUiThread {
                    deviceAdapter.removeDeviceByIp(service.hostAddresses.firstOrNull()?.hostAddress)
                 }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "NSD Service discovery stopped for type: $serviceType")
                nsdDiscoveryActive = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD Start Discovery failed for type $serviceType: Error code: $errorCode")
                nsdManager.stopServiceDiscovery(this)
                nsdDiscoveryActive = false;
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD Stop Discovery failed for type $serviceType: Error code: $errorCode")
                // We should probably still mark discovery as inactive
                 nsdDiscoveryActive = false;
            }
        }
    }
    
    private fun startNsdDiscovery() {
        if (nsdDiscoveryActive) {
            Log.d(TAG, "NSD Discovery already active.")
            return
        }
        if (nsdDiscoveryListener == null || nsdResolveListener == null) {
            Log.d(TAG, "Initializing NSD listeners...")
            initializeNsd() // Ensure listeners are initialized
        }
        try {
            Log.i(TAG, "Starting NSD discovery for printer service types...")
            for (serviceType in PRINTER_SERVICE_TYPES) {
                Log.d(TAG, "Initiating discovery for service type: $serviceType")
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, nsdDiscoveryListener)
            }
            Log.i(TAG, "NSD service discovery initiated for printer types.")
            
            // Add log to indicate NSD scan started
            runOnUiThread {
                addAppLog("Scanning for network printers (mDNS/NSD)...", "INFO")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting NSD discovery", e)
            runOnUiThread {
                addAppLog("Error starting NSD discovery: ${e.message}", "ERROR")
            }
        }
    }

    private fun stopNsdDiscovery() {
        if (nsdDiscoveryActive && nsdDiscoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(nsdDiscoveryListener)
                Log.i(TAG, "NSD service discovery stopped.")
            } catch (e: Exception) {
                 // This can happen if the listener was never registered or already unregistered
                Log.e(TAG, "Error stopping NSD discovery: ${e.message}", e)
            }
        }
        nsdDiscoveryActive = false
    }

    private val udpSearchCallback = object : UdpCallback {
        override fun receive(device: UdpDevice?) {
            if (device == null) {
                Log.w(TAG, "UDP search received null device.")
                return
            }
            // Use getter methods as revealed by javap
            val ipAddressString = device.ipStr ?: run {
                Log.w(TAG, "UDP device has null IP string.")
                return
            }
            val macAddressString = device.macStr ?: "N/A (UDP)"
            // No deviceName field in UdpDevice, construct one.
            val discoveredDeviceName = "Xprinter @ $ipAddressString"

            Log.i(TAG, "UDP Printer Found: IP: $ipAddressString, MAC: $macAddressString, Name: $discoveredDeviceName")
            
            val newDevice = Device(
                macAddress = macAddressString,
                deviceName = "Printer: $discoveredDeviceName", // Use constructed name
                ipAddress = ipAddressString,
                latency = "N/A (UDP)",
                isPrinter = true,
                discoveryMethod = DiscoveryMethod.UDP
            )

            runOnUiThread {
                val existingDevice = deviceAdapter.getDeviceByIp(ipAddressString)
                if (existingDevice == null) {
                    deviceAdapter.addDevice(newDevice)
                } else {
                    // Update if it wasn't marked as printer or to show UDP found it too
                    if (!existingDevice.isPrinter || existingDevice.discoveryMethod != DiscoveryMethod.UDP){
                         val updatedDevice = existingDevice.copy(
                            isPrinter = true, 
                            // Use a consistent naming, prefer existing name if already detailed
                            deviceName = if (existingDevice.deviceName.startsWith("Printer:")) 
                                            existingDevice.deviceName 
                                         else 
                                            "Printer: $discoveredDeviceName",
                            macAddress = if(macAddressString != "N/A (UDP)") macAddressString else existingDevice.macAddress,
                            discoveryMethod = DiscoveryMethod.UDP // Update to UDP as it's our preferred method
                        )
                        deviceAdapter.updateDevice(updatedDevice)
                    }
                }
            }
        }
    }

    private fun startUdpPrinterSearch() {
        if (udpScanActive) {
            Log.d(TAG, "UDP Printer Search already active.")
            return
        }
        if (posUdpNet == null) {
            Log.e(TAG, "PosUdpNet is not initialized, cannot start UDP search.")
            // Attempt to reinitialize if it failed before
            try {
                Log.d(TAG, "Attempting to re-initialize PosUdpNet...")
                posUdpNet = PosUdpNet()
                if (posUdpNet == null) { // Check again after re-attempt
                     Log.e(TAG, "Re-initialization of PosUdpNet failed.")
                     return
                }
                 Log.d(TAG, "PosUdpNet re-initialized successfully during startUdpPrinterSearch.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-initialize PosUdpNet during startUdpPrinterSearch", e)
                return
            }
        }

        Log.i(TAG, "Starting UDP Printer Search...")
        udpScanActive = true
        try {
            // Add a toast to indicate UDP scan is starting
            runOnUiThread {
                addAppLog("Starting UDP printer search...", "INFO")
            }

            posUdpNet?.searchNetDevice(object : UdpCallback {
                override fun receive(device: UdpDevice?) {
                    if (device == null) {
                        Log.w(TAG, "UDP search received null device.")
                        return
                    }
                    
                    // Get device details with additional error checking
                    val ipAddressString = device.ipStr ?: run {
                        Log.w(TAG, "UDP device has null IP string.")
                        return
                    }
                    val macAddressString = device.macStr ?: "N/A (UDP)"

                    // Add more detailed logging
                    Log.i(TAG, "UDP Printer Found - Details: IP: $ipAddressString, MAC: $macAddressString")
                    Log.i(TAG, "All UDP device fields: ${device.toString()}")
                    
                    // No deviceName field in UdpDevice, construct one.
                    val discoveredDeviceName = "Xprinter @ $ipAddressString"

                    val newDevice = Device(
                        macAddress = macAddressString,
                        deviceName = "Printer: $discoveredDeviceName", 
                        ipAddress = ipAddressString,
                        latency = "N/A (UDP)",
                        isPrinter = true,
                        discoveryMethod = DiscoveryMethod.UDP
                    )

                    runOnUiThread {
                        // Add clear toast to show a device was found
                        addAppLog("Found printer via UDP: $ipAddressString", "INFO")

                        val existingDevice = deviceAdapter.getDeviceByIp(ipAddressString)
                        if (existingDevice == null) {
                            deviceAdapter.addDevice(newDevice)
                        } else {
                            // Update if it wasn't marked as printer or to show UDP found it too
                            if (!existingDevice.isPrinter || existingDevice.discoveryMethod != DiscoveryMethod.UDP){
                                 val updatedDevice = existingDevice.copy(
                                    isPrinter = true, 
                                    // Use a consistent naming, prefer existing name if already detailed
                                    deviceName = if (existingDevice.deviceName.startsWith("Printer:")) 
                                                    existingDevice.deviceName 
                                                 else 
                                                    "Printer: $discoveredDeviceName",
                                    macAddress = if(macAddressString != "N/A (UDP)") macAddressString else existingDevice.macAddress,
                                    discoveryMethod = DiscoveryMethod.UDP // Update to UDP as it's our preferred method
                                )
                                deviceAdapter.updateDevice(updatedDevice)
                            }
                        }
                    }
                }
            })

            addAppLog("Searching for Xprinters via UDP...", "INFO")
        } catch (e: Exception) {
            Log.e(TAG, "Error calling searchNetDevice", e)
            udpScanActive = false
            addAppLog("Error starting UDP printer search: ${e.message}", "ERROR")
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("networkscannerlock")
            multicastLock?.let { lock ->
                if (!isMulticastLockAcquired) {
                    lock.acquire()
                    isMulticastLockAcquired = true
                    Log.d(TAG, "Multicast lock acquired successfully")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring multicast lock: ${e.message}")
        }
    }

    private fun performDirectPrint(ipAddress: String) {
        Log.d(TAG, "Attempting printer firmware self-test to $ipAddress")
        // Removed Toast notification
        addAppLog("Starting Test Print to $ipAddress")

        lifecycleScope.launch(Dispatchers.IO) {
            var socket: java.net.Socket? = null
            try {
                // Create a direct socket connection to the printer
                socket = java.net.Socket()
                val timeout = 7000 // Increased timeout slightly

                withContext(Dispatchers.Main) {
                    addAppLog("Connecting to $ipAddress for Test Print...")
                }

                socket.connect(java.net.InetSocketAddress(ipAddress, 9100), timeout)

                if (socket.isConnected) {
                    withContext(Dispatchers.Main) {
                        addAppLog("Connected to $ipAddress. Sending print data...")
                    }

                    val outputStream = socket.getOutputStream()
                    val device = deviceAdapter.getDeviceByIp(ipAddress)
                    val macAddress = device?.macAddress ?: "Unknown"
                    val currentDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

                    // **Critical: Force printer reset and clear any pending jobs**
                    outputStream.write(byteArrayOf(0x1B, 0x40)) // ESC @ - Initialize/reset printer
                    outputStream.flush()
                    delay(500) // Longer delay for printer to reset

                    outputStream.write(byteArrayOf(0x10, 0x14, 0x01, 0x00, 0x00)) // DC4 - Cancel last job/clear buffer
                    outputStream.flush()
                    delay(300) // Delay after cancel
                    
                    outputStream.write(byteArrayOf(0x1B, 0x40)) // ESC @ - Re-Initialize printer again just in case
                    outputStream.flush()
                    delay(300)

                    try {
                        // Title
                        outputStream.write(byteArrayOf(0x1B, 0x21, 0x08)) // Larger font
                        outputStream.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center alignment
                        outputStream.write("PRINTER INFO TEST\n\n".toByteArray(Charsets.UTF_8))
                        outputStream.flush()
                        delay(100)

                        // Back to normal, left aligned
                        outputStream.write(byteArrayOf(0x1B, 0x21, 0x00)) // Normal size
                        outputStream.write(byteArrayOf(0x1B, 0x61, 0x00)) // Left alignment
                        outputStream.flush()
                        delay(100)

                        // Printer IP (Bold and Underline)
                        outputStream.write("Printer IP: ".toByteArray(Charsets.UTF_8))
                        outputStream.write(PrinterUtils.TXT_BOLD_ON)
                        outputStream.write(PrinterUtils.TXT_UNDERLINE_ON)
                        outputStream.write("$ipAddress\n".toByteArray(Charsets.UTF_8))
                        outputStream.write(PrinterUtils.TXT_UNDERLINE_OFF)
                        outputStream.write(PrinterUtils.TXT_BOLD_OFF)
                        outputStream.flush()
                        delay(100)

                        // MAC Address (Bold and Underline)
                        outputStream.write("MAC Address: ".toByteArray(Charsets.UTF_8))
                        outputStream.write(PrinterUtils.TXT_BOLD_ON)
                        outputStream.write(PrinterUtils.TXT_UNDERLINE_ON)
                        outputStream.write("$macAddress\n".toByteArray(Charsets.UTF_8))
                        outputStream.write(PrinterUtils.TXT_UNDERLINE_OFF)
                        outputStream.write(PrinterUtils.TXT_BOLD_OFF)
                        outputStream.flush()
                        delay(100)

                        // Subnet
                        val subnet = if (currentNetworkPrefixLength > 0) {
                            "$ipAddress/$currentNetworkPrefixLength"
                        } else {
                            val ipParts = ipAddress.split(".")
                            if (ipParts.size == 4) "${ipParts[0]}.${ipParts[1]}.${ipParts[2]}.0/24" else "Unknown"
                        }
                        outputStream.write("Subnet: $subnet\n".toByteArray(Charsets.UTF_8))
                        outputStream.flush()
                        delay(100)

                        // Gateway
                        val gateway = getGatewayAddress()
                        outputStream.write("Gateway: $gateway\n".toByteArray(Charsets.UTF_8))
                        outputStream.write("Test Time: $currentDate\n\n".toByteArray(Charsets.UTF_8))
                        outputStream.flush()
                        delay(100)

                        // Separator
                        outputStream.write("--------------------------------\n\n".toByteArray(Charsets.UTF_8))
                        outputStream.flush()

                        // App Info
                        outputStream.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center
                        outputStream.write(byteArrayOf(0x1B, 0x45, 0x01)) // Bold
                        outputStream.write("StoreHub Diagnostic Tool\n".toByteArray(Charsets.UTF_8))
                        outputStream.write(byteArrayOf(0x1B, 0x45, 0x00)) // Bold off
                        outputStream.write("Test Print Successful\n\n".toByteArray(Charsets.UTF_8))
                        outputStream.flush()
                        delay(100)

                        // Feed and cut
                        outputStream.write(byteArrayOf(0x0A, 0x0A, 0x0A, 0x0A, 0x0A)) // 5 Line feeds
                        outputStream.write(byteArrayOf(0x1D, 0x56, 0x01)) // Full cut
                        outputStream.flush()

                        withContext(Dispatchers.Main) {
                            // Removed Toast notification
                            addAppLog("Test Print to $ipAddress successful.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during performDirectPrint content generation: ${e.message}", e)
                        addAppLog("Error formatting Test Print: ${e.message}", "ERROR")
                        withContext(Dispatchers.Main) {
                            // Removed Toast notification
                        }
                        // Attempt a very basic print as fallback
                        try {
                            outputStream.write(byteArrayOf(0x1B, 0x40)) // Reset printer
                            outputStream.flush()
                            delay(300)
                            val fallbackText = "FALLBACK TEST PRINT\nIP: $ipAddress\nMAC: $macAddress\nError during detailed print.\n\n\n\n"
                            outputStream.write(fallbackText.toByteArray(Charsets.UTF_8))
                            outputStream.write(byteArrayOf(0x1D, 0x56, 0x00)) // Partial cut
                            outputStream.flush()
                        } catch (fe: Exception) {
                            Log.e(TAG, "Fallback print also failed: ${fe.message}", fe)
                            addAppLog("Fallback Test Print also failed for $ipAddress: ${fe.message}", "ERROR")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        // Removed Toast notification
                        addAppLog("Could not connect to printer $ipAddress for Test Print.", "ERROR")
                    }
                }
            } catch (e: java.net.ConnectException) {
                Log.e(TAG, "DirectPrint - Connection refused: ${e.message}")
                addAppLog("Test Print to $ipAddress failed: Connection refused. Check IP & port 9100. Error: ${e.message}", "ERROR")
                withContext(Dispatchers.Main) {
                    // Removed Toast notification
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "DirectPrint - Connection timed out: ${e.message}")
                addAppLog("Test Print to $ipAddress failed: Connection timed out. Printer might be off/unreachable. Error: ${e.message}", "ERROR")
                withContext(Dispatchers.Main) {
                    // Removed Toast notification
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in performDirectPrint: ${e.message}", e)
                addAppLog("Generic error in Test Print to $ipAddress: ${e.message}", "ERROR")
                withContext(Dispatchers.Main) {
                    // Removed Toast notification
                }
            } finally {
                try {
                    socket?.close()
                    addAppLog("Socket closed for Test Print to $ipAddress.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing socket in performDirectPrint: ${e.message}")
                    addAppLog("Error closing socket for Test Print to $ipAddress: ${e.message}", "ERROR")
                }
            }
        }
    }

    private fun performEscPosSelfTest(ipAddress: String) {
        Log.d(TAG, "Attempting ESC/POS sample receipt to $ipAddress")
        Toast.makeText(this, "Printing sample receipt...", Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val socket = java.net.Socket()
                socket.soTimeout = 8000 // 8 second timeout
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Connecting to printer...", Toast.LENGTH_SHORT).show()
                }
                
                try {
                    // Connect to standard printer port
                    socket.connect(java.net.InetSocketAddress(ipAddress, 9100), 5000)
                    
                    if (socket.isConnected) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Connected! Sending sample receipt...", Toast.LENGTH_SHORT).show()
                        }
                        
                        val outputStream = socket.getOutputStream()
                        
                        // Initialize printer - this is essential to avoid hex dump mode
                        outputStream.write(byteArrayOf(0x1B, 0x40))  // ESC @ - Initialize printer
                        outputStream.flush()
                        delay(200) // Wait for initialization
                        
                        // ---- Sample Receipt ----
                        
                        // 1. Store header (centered, emphasized)
                        outputStream.write(byteArrayOf(0x1B, 0x61, 0x01)) // ESC a 1 - Center alignment
                        outputStream.write(byteArrayOf(0x1B, 0x45, 0x01)) // ESC E 1 - Bold on
                        outputStream.write(byteArrayOf(0x1B, 0x21, 0x30)) // ESC ! 48 - Double-width and Double-height (0x30 = 0x10 + 0x20)
                        outputStream.write("SAMPLE STORE\n".toByteArray())
                        outputStream.write(byteArrayOf(0x1B, 0x21, 0x08)) // ESC ! 8 - Large size
                        outputStream.write("123 Main Street\n".toByteArray())
                        outputStream.write("City, State 12345\n".toByteArray())
                        outputStream.write("Tel: (123) 456-7890\n\n".toByteArray())
                        outputStream.flush()
                        
                        // 2. Receipt information (left-aligned, larger than normal)
                        outputStream.write(byteArrayOf(0x1B, 0x61, 0x00)) // ESC a 0 - Left alignment
                        outputStream.write(byteArrayOf(0x1B, 0x45, 0x00)) // ESC E 0 - Bold off
                        outputStream.write(byteArrayOf(0x1B, 0x21, 0x01)) // ESC ! 1 - Larger font
                        
                        val currentDate = java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date())
                        val currentTime = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())
                        
                        outputStream.write("Date: $currentDate\n".toByteArray())
                        outputStream.write("Time: $currentTime\n".toByteArray())
                        outputStream.write("Receipt: #${(1000..9999).random()}\n".toByteArray())
                        outputStream.write("Cashier: Sample User\n".toByteArray())
                        
                        // Add printer technical information
                        val device = deviceAdapter.getDeviceByIp(ipAddress)
                        val macAddress = device?.macAddress ?: "Unknown"
                        outputStream.write("\nPRINTER DETAILS:\n".toByteArray())
                        
                        // Print IP with bold and underline
                        outputStream.write("IP: ".toByteArray())
                        outputStream.write(byteArrayOf(0x1B, 0x45, 0x01)) // Bold on
                        outputStream.write(byteArrayOf(0x1B, 0x2D, 0x01)) // Underline on
                        outputStream.write("$ipAddress".toByteArray())
                        outputStream.write(byteArrayOf(0x1B, 0x45, 0x00)) // Bold off
                        outputStream.write(byteArrayOf(0x1B, 0x2D, 0x00)) // Underline off
                        outputStream.write("\n".toByteArray())
                        
                        // Print MAC with bold and underline
                        outputStream.write("MAC: ".toByteArray())
                        outputStream.write(byteArrayOf(0x1B, 0x45, 0x01)) // Bold on
                        outputStream.write(byteArrayOf(0x1B, 0x2D, 0x01)) // Underline on
                        outputStream.write("$macAddress".toByteArray())
                        outputStream.write(byteArrayOf(0x1B, 0x45, 0x00)) // Bold off
                        outputStream.write(byteArrayOf(0x1B, 0x2D, 0x00)) // Underline off
                        outputStream.write("\n".toByteArray())
                        
                        // Calculate subnet
                        val subnet = if (currentNetworkPrefixLength > 0) {
                            "$ipAddress/${currentNetworkPrefixLength}"
                        } else {
                            val ipParts = ipAddress.split(".")
                            if (ipParts.size == 4) {
                                "${ipParts[0]}.${ipParts[1]}.${ipParts[2]}.0/24"
                            } else {
                                "Unknown"
                            }
                        }
                        outputStream.write("Subnet: $subnet\n\n".toByteArray())
                        outputStream.flush()
                        
                        // 3. Line separator
                        outputStream.write("--------------------------------\n".toByteArray())
                        
                        // 4. Item list
                        outputStream.write("ITEM                 QTY   PRICE\n".toByteArray())
                        outputStream.write("--------------------------------\n".toByteArray())
                        
                        // Some sample items
                        outputStream.write("Product A            1     $10.99\n".toByteArray())
                        outputStream.write("Product B            2     $15.50\n".toByteArray())
                        outputStream.write("Product C            3      $4.99\n\n".toByteArray())
                        
                        // 5. Totals (right aligned)
                        outputStream.write("--------------------------------\n".toByteArray())
                        outputStream.write("Subtotal:               $42.47\n".toByteArray())
                        outputStream.write("Tax (8%):                $3.40\n".toByteArray())
                        
                        // Total (bold)
                        outputStream.write(byteArrayOf(0x1B, 0x45, 0x01)) // ESC E 1 - Bold on
                        outputStream.write("TOTAL:                  $45.87\n\n".toByteArray())
                        outputStream.write(byteArrayOf(0x1B, 0x45, 0x00)) // ESC E 0 - Bold off
                        
                        // 6. Footer
                        outputStream.write(byteArrayOf(0x1B, 0x61, 0x01)) // ESC a 1 - Center alignment
                        outputStream.write("Thank you for your business!\n".toByteArray())
                        outputStream.write("StoreHub Diagnostic Tool\n\n".toByteArray())
                        
                        // 7. Cut paper
                        outputStream.write(byteArrayOf(0x0A, 0x0A, 0x0A)) // Line feeds for margin
                        outputStream.write(byteArrayOf(0x1D, 0x56, 0x01)) // GS V 1 - Full cut
                        outputStream.flush()
                        
                        // Close connection
                        socket.close()
                        
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Sample receipt printed successfully", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Receipt print error: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Receipt print error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    if (!socket.isClosed) {
                        try {
                            socket.close()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error closing socket: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in sample receipt print: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error in receipt print: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun performDocketPrint(ipAddress: String, quantity: Int) {
        Log.d(TAG, "Attempting to print $quantity kitchen docket(s) to $ipAddress")
        // Removed Toast notification
        addAppLog("Starting Docket Print ($quantity dockets) to $ipAddress")

        lifecycleScope.launch(Dispatchers.IO) {
            var socket: java.net.Socket? = null
            try {
                socket = java.net.Socket()
                val timeout = 7000 // Increased timeout

                withContext(Dispatchers.Main) {
                    addAppLog("Connecting to $ipAddress for Docket Print ($quantity dockets)...")
                }

                socket.connect(java.net.InetSocketAddress(ipAddress, 9100), timeout)

                if (socket.isConnected) {
                    withContext(Dispatchers.Main) {
                        addAppLog("Connected to $ipAddress. Printing $quantity docket(s)...")
                    }

                    val outputStream = socket.getOutputStream()

                    // **Critical: Force printer reset and clear any pending jobs before starting the loop**
                    outputStream.write(byteArrayOf(0x1B, 0x40)) // ESC @ - Initialize/reset printer
                    outputStream.flush()
                    delay(500) // Longer delay

                    outputStream.write(byteArrayOf(0x10, 0x14, 0x01, 0x00, 0x00)) // DC4 - Cancel last job
                    outputStream.flush()
                    delay(300)
                    
                    outputStream.write(byteArrayOf(0x1B, 0x40)) // ESC @ - Re-Initialize again
                    outputStream.flush()
                    delay(300)

                    var docketsPrintedSuccessfully = 0
                    for (i in 1..quantity) {
                        try {
                            addAppLog("Printing docket #$i of $quantity to $ipAddress...")
                            // **Reset printer state before each docket to ensure consistency**
                            outputStream.write(byteArrayOf(0x1B, 0x40)) // Reset printer
                            outputStream.flush()
                            delay(50) // Short delay for reset between dockets, reduced from 200

                            // Kitchen Docket Header
                            outputStream.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center alignment
                            outputStream.write(byteArrayOf(0x1B, 0x45, 0x01)) // Bold on
                            outputStream.write(byteArrayOf(0x1B, 0x21, 0x08)) // Larger font
                            outputStream.write("KITCHEN DOCKET\n".toByteArray(Charsets.UTF_8))
                            outputStream.write("ORDER #${String.format("%04d", i)}\n".toByteArray(Charsets.UTF_8))
                            outputStream.flush()
                            delay(25) // Reduced from 100

                            val currentDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                            val currentTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                            outputStream.write("$currentDate $currentTime\n\n".toByteArray(Charsets.UTF_8))
                            outputStream.flush()
                            delay(25) // Reduced from 100

                            // Normal text, left aligned
                            outputStream.write(byteArrayOf(0x1B, 0x61, 0x00)) // Left alignment
                            outputStream.write(byteArrayOf(0x1B, 0x45, 0x00)) // Bold off
                            outputStream.write(byteArrayOf(0x1B, 0x21, 0x00)) // Normal font
                            outputStream.flush()
                            delay(25) // Reduced from 100

                            // Separator & Items
                            outputStream.write("--------------------------------\n".toByteArray(Charsets.UTF_8))
                            outputStream.write("1 x Burger (Test Item)\n".toByteArray(Charsets.UTF_8))
                            outputStream.write("2 x Fries (Test Item)\n".toByteArray(Charsets.UTF_8))
                            outputStream.write("1 x Soda (Test Item)\n\n".toByteArray(Charsets.UTF_8))
                            outputStream.flush()
                            delay(25) // Reduced from 100

                            // Special Instructions
                            outputStream.write(byteArrayOf(0x1B, 0x45, 0x01)) // Bold on
                            outputStream.write("Special Instructions:\n".toByteArray(Charsets.UTF_8))
                            outputStream.write(byteArrayOf(0x1B, 0x45, 0x00)) // Bold off
                            outputStream.write("No pickles (Test Instruction)\n\n".toByteArray(Charsets.UTF_8))
                            outputStream.flush()
                            delay(25) // Reduced from 100

                            // Footer
                            outputStream.write("--------------------------------\n".toByteArray(Charsets.UTF_8))
                            
                            // Apply center alignment before applying large font for sequence
                            PrinterUtils.sendCommand(outputStream, PrinterUtils.TXT_ALIGN_CENTER)
                            
                            // Full line of large size sequence
                            PrinterUtils.sendCommand(outputStream, PrinterUtils.TXT_FONT_LARGE)
                            PrinterUtils.sendText(outputStream, "Sequence: $i of $quantity\n")
                            
                            // Return to normal font size and continue with centered text
                            PrinterUtils.sendCommand(outputStream, PrinterUtils.TXT_NORMAL)
                            PrinterUtils.sendText(outputStream, "StoreHub Diagnostic Tool\n\n")
                            outputStream.flush()
                            delay(25) // Reduced from 100

                            // Feed and cut
                            outputStream.write(byteArrayOf(0x0A, 0x0A, 0x0A, 0x0A, 0x0A)) // 5 Line feeds
                            outputStream.write(byteArrayOf(0x1D, 0x56, 0x00)) // Partial cut (more compatible)
                            outputStream.flush()

                            docketsPrintedSuccessfully++
                            addAppLog("Docket #$i of $quantity to $ipAddress printed successfully.")
                            
                            if (i < quantity) {
                                delay(100) // Longer delay between dockets for cutting and printer recovery, reduced from 500
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error printing docket #$i: ${e.message}", e)
                            addAppLog("Error printing docket #$i of $quantity to $ipAddress: ${e.message}", "ERROR")
                            withContext(Dispatchers.Main) {
                                // Removed Toast notification
                            }
                            // Try to recover for next docket if possible, by resetting again
                            try {
                                outputStream.write(byteArrayOf(0x1B, 0x40)) 
                                outputStream.flush()
                                delay(300)
                                addAppLog("Attempted recovery reset for docket #$i.", "WARN")
                            } catch (re: Exception) { 
                                Log.e(TAG, "Recovery reset failed", re) 
                                addAppLog("Recovery reset failed for docket #$i: ${re.message}", "ERROR")
                            }
                            // If one docket fails, we'll still try the next ones after a reset attempt.
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (docketsPrintedSuccessfully == quantity) {
                            // Removed Toast notification
                            addAppLog("Successfully printed all $quantity kitchen dockets to $ipAddress.")
                        } else {
                            // Removed Toast notification
                            addAppLog("Printed $docketsPrintedSuccessfully of $quantity dockets to $ipAddress. Some failed.", "WARN")
                        }
                    }

                } else {
                    withContext(Dispatchers.Main) {
                        // Removed Toast notification
                        addAppLog("Could not connect to printer $ipAddress for Docket Print.", "ERROR")
                    }
                }
            } catch (e: java.net.ConnectException) {
                Log.e(TAG, "DocketPrint - Connection refused: ${e.message}")
                addAppLog("Docket Print to $ipAddress failed: Connection refused. Error: ${e.message}", "ERROR")
                withContext(Dispatchers.Main) {
                    // Removed Toast notification
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "DocketPrint - Connection timed out: ${e.message}")
                addAppLog("Docket Print to $ipAddress failed: Connection timed out. Error: ${e.message}", "ERROR")
                withContext(Dispatchers.Main) {
                    // Removed Toast notification
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in performDocketPrint: ${e.message}", e)
                addAppLog("Generic error in Docket Print to $ipAddress: ${e.message}", "ERROR")
                withContext(Dispatchers.Main) {
                    // Removed Toast notification
                }
            } finally {
                try {
                    socket?.close()
                    addAppLog("Socket closed for Docket Print to $ipAddress.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing socket in performDocketPrint: ${e.message}")
                    addAppLog("Error closing socket for Docket Print to $ipAddress: ${e.message}", "ERROR")
                }
            }
        }
    }

    // Add this method after performDocketPrint
    private fun safePrint(ipAddress: String) {
        Log.d(TAG, "Attempting safe mode print to $ipAddress")
        addAppLog("Trying minimal command safe print to $ipAddress...", "INFO")
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val socket = java.net.Socket()
                
                try {
                    // Connect with longer timeout
                    socket.connect(java.net.InetSocketAddress(ipAddress, 9100), 8000)
                    socket.soTimeout = 8000
                    
                    if (socket.isConnected) {
                        val outputStream = socket.getOutputStream()
                        
                        // SAFE MODE: Almost no control sequences
                        // 1. Send just a reset command
                        outputStream.write(byteArrayOf(0x1B, 0x40)) // ESC @
                        outputStream.flush()
                        delay(500) // Very long delay
                        
                        // 2. Very simple text, minimal formatting, lots of line breaks
                        val safePrintText = """






SAFE MODE PRINT TEST

This is a test using minimal printer commands
to prevent hex dump mode.

IP: $ipAddress
TIME: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}

If you can read this text, then safe mode printing works!
Try using this printer with only basic text.






""".trimIndent()
                        
                        // Send the text in small chunks with delays
                        val lines = safePrintText.split("\n")
                        for (line in lines) {
                            outputStream.write("$line\n".toByteArray())
                            outputStream.flush()
                            delay(50) // Small delay between lines
                        }
                        
                        // Send line feeds and a simple cut command (the safest one)
                        outputStream.write("\n\n\n\n\n".toByteArray())
                        outputStream.flush()
                        delay(300)
                        
                        // Very simple partial cut
                        outputStream.write(byteArrayOf(0x1D, 0x56, 0x00))
                        outputStream.flush()
                        
                        try {
                            socket.close()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error closing socket: ${e.message}")
                        }
                        
                        withContext(Dispatchers.Main) {
                            addAppLog("Safe mode print sent to $ipAddress, check printer", "INFO")
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            addAppLog("Couldn't connect to printer at $ipAddress", "ERROR")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Safe print error: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        addAppLog("Safe print error to $ipAddress: ${e.message}", "ERROR")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Socket creation failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    addAppLog("Socket error for safe print to $ipAddress: ${e.message}", "ERROR")
                }
            }
        }
    }

    // New function to add messages to the in-app log
    private fun addAppLog(message: String, level: String = "INFO") {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        
        // Color coding based on log level
        val color = when (level.uppercase()) {
            "ERROR" -> "#FF0000" // Red
            "WARN" -> "#FFA500"  // Orange
            "DEBUG" -> "#008000" // Green
            else -> "#0000FF"    // Blue for INFO
        }
        
        val formattedMessage = "<font color='$color'>$timestamp [$level]</font> $message"
        
        // Log to Android's Logcat as well
        when (level.uppercase()) {
            "ERROR" -> Log.e(TAG, message)
            "WARN" -> Log.w(TAG, message)
            "DEBUG" -> Log.d(TAG, message)
            else -> Log.i(TAG, message)
        }
        
        // Update the UI on the main thread
        runOnUiThread {
            // Append the log entry with a proper HTML break
            appLogTextView.append(HtmlCompat.fromHtml(formattedMessage + "<br>", HtmlCompat.FROM_HTML_MODE_LEGACY))
            // Scroll to the bottom
            logScrollView.post {
                logScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
    
    // Add this function to customize the Toast appearance and position
    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        val toast = Toast.makeText(this, message, duration)
        toast.setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL, 0, 150)
        toast.show()
    }

    // Initialize app log with useful information
    private fun initializeAppLog() {
        // Clear any existing content
        appLogTextView.text = ""
        
        // Welcome message
        addAppLog("StoreHub Diagnostic Tool started", "INFO")
        
        // Device info
        val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        addAppLog("Device: ${Build.MANUFACTURER} ${Build.MODEL}", "INFO")
        addAppLog("OS: $androidVersion", "INFO")
        
        // App version
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = packageInfo.versionName
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            addAppLog("App version: $versionName ($versionCode)", "INFO")
        } catch (e: Exception) {
            addAppLog("Could not determine app version", "WARN")
        }
        
        addAppLog("App log initialized and ready", "INFO")
    }

    // Helper method for the quick service status check that updates the bubble
    private fun performQuickServiceCheck() {
        // Update UI to show checking status
        setAllStatusesToChecking()
        addAppLog("Checking StoreHub services status...", "INFO")
        
        lifecycleScope.launch(Dispatchers.IO) {
            val healthChecks = mutableListOf<HealthCheckResult>()
            var totalServices = 0
            var onlineServices = 0
            
            // Process each service and update UI for each
            for ((serviceName, url) in healthCheckServices) {
                totalServices++
                try {
                    Log.d(TAG, "Quick checking service: $serviceName at URL: $url")
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", "NetworkScanner/1.0")
                    val responseCode = connection.responseCode
                    Log.d(TAG, "Response code for $serviceName: $responseCode")
                    
                    if (responseCode == 200) {
                        onlineServices++
                        healthChecks.add(HealthCheckResult(serviceName, url, "Online"))
                        addAppLog("$serviceName: Online", "INFO")
                        
                        // Update UI for this specific service
                        withContext(Dispatchers.Main) {
                            updateServiceStatus(serviceName, true)
                        }
                    } else {
                        val status = "Offline (HTTP $responseCode)"
                        healthChecks.add(HealthCheckResult(serviceName, url, status))
                        addAppLog("$serviceName: $status", "WARN")
                        
                        // Update UI for this specific service
                        withContext(Dispatchers.Main) {
                            updateServiceStatus(serviceName, false)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking $serviceName: ${e.message}", e)
                    healthChecks.add(HealthCheckResult(serviceName, url, "Error"))
                    addAppLog("$serviceName: Error - ${e.message}", "ERROR")
                    
                    // Update UI for this specific service
                    withContext(Dispatchers.Main) {
                        updateServiceStatus(serviceName, false)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                // Also update the health check panel data
                healthCheckAdapter.updateData(healthChecks)
                
                // Add a summary log entry
                if (onlineServices == totalServices) {
                    addAppLog("All StoreHub services are online ($onlineServices/$totalServices)", "INFO")
                } else if (onlineServices > 0) {
                    addAppLog("Some StoreHub services are offline ($onlineServices/$totalServices online)", "WARN")
                } else {
                    addAppLog("All StoreHub services appear to be offline!", "ERROR")
                }
            }
        }
    }
    
    // Helper method to set all status text views to "Checking..."
    private fun setAllStatusesToChecking() {
        val checkingColor = Color.GRAY
        beepitStatusText.text = "Checking..."
        beepitStatusText.setTextColor(checkingColor)
        
        storehubHQStatusText.text = "Checking..."
        storehubHQStatusText.setTextColor(checkingColor)
        
        storehubMeStatusText.text = "Checking..."
        storehubMeStatusText.setTextColor(checkingColor)
        
        paymentAPIStatusText.text = "Checking..."
        paymentAPIStatusText.setTextColor(checkingColor)
    }
    
    // Helper method to update an individual service status text view
    private fun updateServiceStatus(serviceName: String, isOnline: Boolean) {
        val textView = when (serviceName) {
            "BeepIT" -> beepitStatusText
            "StoreHub HQ" -> storehubHQStatusText
            "StoreHub Merchandise" -> storehubMeStatusText
            "Payment API" -> paymentAPIStatusText
            else -> null
        }
        
        textView?.let {
            val statusText = if (isOnline) "Online" else "Offline"
            val textColor = if (isOnline) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
            
            it.text = statusText
            it.setTextColor(textColor)
        }
    }
    
    private fun showAppInfoDialog() {
        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        var versionName = "Unknown"
        var versionCode = "Unknown"
        
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            versionName = packageInfo.versionName
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting package info: ${e.message}", e)
        }
        
        // Create a TabLayout and ViewPager for tabbed dialog
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_info, null)
        val tabLayout = dialogView.findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabLayout)
        val viewPager = dialogView.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager)
        
        // Create adapter for the ViewPager
        val adapter = object : androidx.viewpager2.adapter.FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 2
            
            override fun createFragment(position: Int): androidx.fragment.app.Fragment {
                return when (position) {
                    0 -> {
                        // About Tab
                        val aboutText = """
                            StoreHub Diagnostic Tool
                            Version: $versionName ($versionCode)
                            Build Date: $currentDate
                            
                            Created by Kenn Teoh
                            Copyright © 2023-2024 StoreHub
                            
                            All rights reserved. This application is the property of 
                            StoreHub and is protected by copyright laws and 
                            international treaty provisions. Unauthorized reproduction 
                            or distribution of this application or any portion of it may
                            result in severe civil and criminal penalties.
                        """.trimIndent()
                        ContentFragment.newInstance(aboutText)
                    }
                    1 -> {
                        // Printer List Tab
                        val printerListText = """
                            Compatible Printer Models:
                            
                            XPRINTER:
                            • CP-Q6+ (iOS & Android)
                            • CP-Q3 (iOS & Android)
                            • Q838L (iOS & Android)
                            • CP-Q3s (Android)
                            • CP-Q3x (Android)
                            • XP-Q80i (iOS & Android)
                            • CP-Q2 (iOS & Android)
                            
                            Note: This app is designed to work best with 
                            Android-compatible printers.
                            
                            For more information, visit:
                            https://care.storehub.com/en/articles/7252231-faq-pos-hardware-supplementary-device-support
                        """.trimIndent()
                        
                        // Create a fragment with the printer list and clickable link
                        val fragment = ContentFragment()
                        fragment.arguments = Bundle().apply {
                            putString("content", printerListText)
                            putBoolean("has_link", true)
                        }
                        fragment
                    }
                    else -> ContentFragment()
                }
            }
        }
        
        // Setup ViewPager with TabLayout
        viewPager.adapter = adapter
        com.google.android.material.tabs.TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "About"
                1 -> "Printer List"
                else -> "Tab"
            }
        }.attach()
        
        // Create and show the dialog
        val dialog = AlertDialog.Builder(this)
            .setTitle("StoreHub Diagnostic Tool")
            .setView(dialogView)
            .setPositiveButton("OK", null)
            
        // Try to get the app icon
        try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val appIcon = packageManager.getApplicationIcon(appInfo)
            dialog.setIcon(appIcon)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading app icon: ${e.message}")
        }
        
        dialog.show()
    }

    // Add these utility functions for network connectivity checks
    private fun pingHost(ipAddress: String): Boolean {
        return try {
            val runtime = Runtime.getRuntime()
            val command = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // On newer Android, limit to 1 ping with 1 second timeout
                runtime.exec("ping -c 1 -W 1 $ipAddress")
            } else {
                // Older Android versions might not support the -W flag
                runtime.exec("ping -c 1 $ipAddress")
            }
            
            val exitValue = command.waitFor()
            exitValue == 0
        } catch (e: Exception) {
            Log.e(TAG, "Error pinging host $ipAddress: ${e.message}")
            false
        }
    }
    
    private fun isPortOpen(ipAddress: String, port: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ipAddress, port), 3000) // 3 second timeout
            val isConnected = socket.isConnected
            try {
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing socket: ${e.message}")
            }
            isConnected
        } catch (e: Exception) {
            Log.d(TAG, "Port $port is closed on $ipAddress: ${e.message}")
            false
        }
    }

    /**
     * Helper function to identify ISP based on name patterns and AS numbers
     */
    private fun identifyISP(isp: String, org: String, asn: String, countryCode: String): String {
        // Combine ISP and org data for better matching
        val combinedData = "$isp $org $asn"
        
        return when (countryCode) {
            "MY" -> {
                when {
                    // Malaysia ISPs with more specific matching patterns
                    combinedData.containsAny("Telekom Malaysia", "TM Net", "TMNET", "Unifi", "AS4788") -> 
                        "Telekom Malaysia/Unifi (MY)"
                    combinedData.containsAny("Maxis", "AS10030") -> 
                        "Maxis (MY)"
                    combinedData.containsAny("TIME", "TIME dotCom", "TIMEdotCom", "AS9930") -> 
                        "TIME dotCom (MY)"
                    combinedData.containsAny("DIGI", "DiGi", "AS10184") -> 
                        "Digi Telecommunications (MY)"
                    combinedData.containsAny("Celcom", "Celcom Axiata", "AS4818", "AS88256") -> 
                        "Celcom Axiata (MY)"
                    combinedData.containsAny("U Mobile", "U-Mobile", "AS45960") -> 
                        "U Mobile (MY)"
                    combinedData.containsAny("YTL", "Yes", "YTL Communications", "AS38466") -> 
                        "YTL Communications/Yes (MY)"
                    combinedData.containsAny("REDtone", "Redtone", "AS9930") -> 
                        "REDtone (MY)"
                    combinedData.containsAny("Astro", "MEASAT", "AS10085") -> 
                        "Astro IPTV (MY)"
                    combinedData.containsAny("Jaring", "JARING", "AS4788") -> 
                        "JARING (MY)"
                    combinedData.containsAny("P1", "Webe", "AS17971") -> 
                        "P1/Webe (MY)"
                    // Add a default for Malaysian IP but unknown ISP
                    countryCode == "MY" -> "$isp (MY)"
                    else -> "$isp (MY)"
                }
            }
            "TH" -> {
                when {
                    // Thailand ISPs
                    combinedData.containsAny("True", "True Corporation", "True Internet", "True Online", "AS7470", "AS38794", "AS131090", "AS45758") ->
                        "True Corporation (TH)"
                    combinedData.containsAny("AIS", "Advanced Info Service", "Advanced Wireless", "AWN", "AS45430", "AS23884", "AS133481") ->
                        "AIS/Advanced Info Service (TH)"
                    combinedData.containsAny("TOT", "Telephone Organization of Thailand", "AS9287", "AS132061") ->
                        "TOT (TH)"
                    combinedData.containsAny("National Telecom", "CAT Telecom", "TOT Public", "NT", "AS7568", "AS9931") ->
                        "NT/CAT/TOT (TH)"
                    combinedData.containsAny("3BB", "Triple T", "Triple T Broadband", "AS7650") ->
                        "3BB/Triple T Broadband (TH)"
                    combinedData.containsAny("DTAC", "DTN", "Total Access", "AS24378", "AS131219") ->
                        "DTAC/Total Access Communication (TH)"
                    combinedData.containsAny("CS LOXINFO", "CSL", "Loxinfo", "LoxInfo", "AS4750") ->
                        "CS LOXINFO (TH)"
                    combinedData.containsAny("Symphony", "Symphony Communication", "AS9335") ->
                        "Symphony Communication (TH)"
                    combinedData.containsAny("JasTel", "Jasmine", "AS45629", "AS131445") ->
                        "JasTel Network (TH)"
                    combinedData.containsAny("INET", "Internet Thailand", "INET-TH", "AS9389") ->
                        "Internet Thailand/INET (TH)"
                    combinedData.containsAny("TrueMove", "AS131158") ->
                        "TrueMove H (TH)"
                    // Add a default for Thai IP but unknown ISP
                    countryCode == "TH" -> "$isp (TH)"
                    else -> "$isp (TH)"
                }
            }
            "PH" -> {
                when {
                    // Philippines ISPs
                    combinedData.containsAny("PLDT", "Philippine Long Distance", "ePLDT", "AS9299", "AS4775", "AS23930") ->
                        "PLDT (PH)"
                    combinedData.containsAny("Globe", "Globe Telecom", "Globe Telecoms", "AS132199", "AS17639") ->
                        "Globe Telecom (PH)"
                    combinedData.containsAny("Sky", "Sky Broadband", "Sky Cable", "SKYCable", "AS132072", "AS18396") ->
                        "Sky Broadband/Sky Cable (PH)"
                    combinedData.containsAny("Converge", "ComClark", "AS17639", "AS23944", "AS18106") ->
                        "Converge ICT (PH)"
                    combinedData.containsAny("DITO", "DITO Telecommunity", "AS138915") ->
                        "DITO Telecommunity (PH)"
                    combinedData.containsAny("Smart", "Smart Communications", "AS10139") ->
                        "Smart Communications (PH)"
                    combinedData.containsAny("Eastern Telecom", "Eastern Telecoms", "Eastern Communications", "AS4796") ->
                        "Eastern Telecoms (PH)"
                    combinedData.containsAny("Rise", "Blast Metronet", "AS138430") ->
                        "Rise (PH)"
                    combinedData.containsAny("Sun", "Sun Cellular", "AS18399") ->
                        "Sun Cellular (PH)"
                    combinedData.containsAny("Bayantel", "Bayan", "AS9658") ->
                        "BayanTel (PH)"
                    combinedData.containsAny("InfiniVAN", "Infinivan", "AS38553") ->
                        "InfiniVAN (PH)"
                    combinedData.containsAny("Now Telecom", "Now Corp", "AS135607") ->
                        "Now Telecom (PH)"
                    combinedData.containsAny("PT&T", "PT", "Philippine Telegraph", "AS9821") ->
                        "PT&T (PH)"
                    combinedData.containsAny("Radius", "Radius Telecoms", "AS132761") ->
                        "Radius Telecoms (PH)"
                    // Add a default for Philippines IP but unknown ISP
                    countryCode == "PH" -> "$isp (PH)"
                    else -> "$isp (PH)"
                }
            }
            else -> {
                // Try to look for patterns in the ASN and ISP names anyway
                when {
                    // Malaysia ISPs detection by ASN or company names
                    combinedData.containsAny("Telekom Malaysia", "TM Net", "TMNET", "Unifi", "AS4788") -> 
                        "Telekom Malaysia/Unifi (MY)"
                    combinedData.containsAny("TIME", "TIME dotCom", "TIMEdotCom", "AS9930") -> 
                        "TIME dotCom (MY)"
                    combinedData.containsAny("Celcom", "Celcom Axiata", "AS4818") -> 
                        "Celcom Axiata (MY)"
                    
                    // Thailand ISPs detection 
                    combinedData.containsAny("True Corporation", "True Internet", "AS7470") ->
                        "True Corporation (TH)"
                    combinedData.containsAny("AIS", "Advanced Info Service", "AS45430") ->
                        "AIS (TH)"
                    
                    // Philippines ISPs detection
                    combinedData.containsAny("PLDT", "Philippine Long Distance", "AS9299") ->
                        "PLDT (PH)"
                    combinedData.containsAny("Globe Telecom", "Globe Telecoms", "AS132199") ->
                        "Globe Telecom (PH)"
                    
                    // Just return the ISP name if no specific matching
                    else -> isp
                }
            }
        }
    }

    /**
     * Extension function to check if a string contains any of the given keywords
     */
    private fun String.containsAny(vararg keywords: String): Boolean {
        for (keyword in keywords) {
            if (this.contains(keyword, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun showSpeedTestOptionsDialog() {
        val options = arrayOf("LAN Speed Test (Local Network)", "Internet Speed Test (Online)")
        AlertDialog.Builder(this)
            .setTitle("Select Speed Test Type")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> performLanSpeedTest()
                    1 -> performOnlineSpeedTest()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun performOnlineSpeedTest() {
        // Show the speed test card with progress layout
        hideAllResultViews()
        speedTestPanelCard.visibility = View.VISIBLE
        speedTestPanelCard.startAnimation(fadeInAnimation)
        
        // Show progress and hide results
        speedTestProgressLayout.visibility = View.VISIBLE
        speedTestResultsLayout.visibility = View.GONE
        speedTestProgressText.text = "Connecting to speed test server...\nThis will take about 20-30 seconds."
        
        // Create online speed test manager
        val onlineSpeedTestManager = OnlineSpeedTestManager(this)
        
        // Set up listener for test progress and results
        onlineSpeedTestManager.setSpeedTestListener(object : OnlineSpeedTestManager.SpeedTestListener {
            override fun onTestStarted() {
                runOnUiThread {
                    addAppLog("Online speed test started.")
                }
            }
            
            override fun onTestFinished(result: OnlineSpeedTestManager.SpeedTestResult) {
                runOnUiThread {
                    addAppLog("Online speed test completed. Download: ${String.format("%.2f", result.downloadMbps)} Mbps, " +
                              "Upload: ${String.format("%.2f", result.uploadMbps)} Mbps, " +
                              "Ping: ${result.latencyMs.toInt()} ms")
                    
                    // Display the results
                    displayOnlineSpeedTestResults(result)
                }
            }
            
            override fun onTestProgress(speedMbps: Float, progressPercent: Int, isDownload: Boolean) {
                runOnUiThread {
                    val phase = if (isDownload) "Downloading" else "Uploading"
                    speedTestProgressText.text = "$phase: ${String.format("%.2f", speedMbps)} Mbps\nProgress: $progressPercent%"
                }
            }
            
            override fun onTestFailed(reason: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Speed test failed: $reason", Toast.LENGTH_LONG).show()
                    addAppLog("Online speed test failed: $reason", "ERROR")
                    speedTestPanelCard.visibility = View.GONE
                }
            }
        })
        
        lifecycleScope.launch {
            try {
                onlineSpeedTestManager.startTest()
            } catch (e: Exception) {
                Log.e(TAG, "Error during online speed test: ${e.message}", e)
                Toast.makeText(this@MainActivity, "Error during online speed test: ${e.message}", Toast.LENGTH_LONG).show()
                speedTestPanelCard.visibility = View.GONE
            }
        }
    }
    
    private fun displayOnlineSpeedTestResults(result: OnlineSpeedTestManager.SpeedTestResult) {
        // Hide progress and show results
        speedTestProgressLayout.visibility = View.GONE
        speedTestResultsLayout.visibility = View.VISIBLE
        
        // Set the result values in the panel
        tvGatewayIp.text = "Internet (${result.serverName})"
        tvPingResult.text = "${result.latencyMs.toInt()} ms"
        tvUploadResult.text = "${String.format("%.2f", result.uploadMbps)} Mbps"
        
        // Add download result - we need to ensure this UI element exists in the layout
        if (speedTestResultsLayout.findViewById<TextView>(R.id.tvDownloadResult) != null) {
            speedTestResultsLayout.findViewById<TextView>(R.id.tvDownloadResult).text = 
                "${String.format("%.2f", result.downloadMbps)} Mbps"
            
            // Set download quality indicator if it exists
            if (speedTestResultsLayout.findViewById<ImageView>(R.id.ivDownloadQuality) != null) {
                setSpeedQualityIndicator(
                    speedTestResultsLayout.findViewById(R.id.ivDownloadQuality), 
                    result.downloadMbps
                )
            }
        }
        
        // Set quality indicators
        setPingQualityIndicator(ivPingQuality, result.latencyMs)
        setSpeedQualityIndicator(ivUploadQuality, result.uploadMbps)
    }
    
    private fun setSpeedQualityIndicator(imageView: ImageView, speedMbps: Float) {
        val quality = LanSpeedTestManager.getSpeedQuality(speedMbps)
        val colorResId = when (quality) {
            SpeedQuality.EXCELLENT -> android.R.drawable.presence_online
            SpeedQuality.GOOD -> android.R.drawable.presence_online
            SpeedQuality.AVERAGE -> android.R.drawable.presence_away
            SpeedQuality.POOR -> android.R.drawable.presence_busy
            SpeedQuality.BAD -> android.R.drawable.presence_offline
            else -> android.R.drawable.presence_invisible
        }
        
        val colorInt = when (quality) {
            SpeedQuality.EXCELLENT -> Color.parseColor("#4CAF50") // Green
            SpeedQuality.GOOD -> Color.parseColor("#8BC34A") // Light Green
            SpeedQuality.AVERAGE -> Color.parseColor("#FFC107") // Amber
            SpeedQuality.POOR -> Color.parseColor("#FF9800") // Orange
            SpeedQuality.BAD -> Color.parseColor("#F44336") // Red
            else -> Color.GRAY
        }
        
        imageView.setImageResource(colorResId)
        imageView.setColorFilter(colorInt)
    }
    
    private fun showNetworkInfoDialog(device: Device) {
        addAppLog("Checking network configuration for ${device.ipAddress}")
        
        lifecycleScope.launch {
            try {
                val networkConfigManager = XprinterNetworkConfigManager()
                val config = networkConfigManager.getNetworkConfiguration(device.ipAddress)
                
                withContext(Dispatchers.Main) {
                    val message = if (config != null) {
                        buildString {
                            appendLine("Network Configuration:")
                            appendLine("• DHCP: ${if (config.isDhcpEnabled) "Enabled" else "Disabled"}")
                            appendLine("• IP Address: ${config.ipAddress}")
                            appendLine("• Subnet Mask: ${config.subnetMask}")
                            appendLine("• Gateway: ${config.gateway}")
                            if (config.macAddress != "Unknown") {
                                appendLine("• MAC Address: ${config.macAddress}")
                            }
                            appendLine("• Configuration Method: ${config.configMethod}")
                        }
                    } else {
                        "Could not retrieve network configuration.\n\nThe printer may not support remote configuration or is not accessible via web interface or ESC/POS commands."
                    }
                    
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Network Information - ${device.ipAddress}")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Network Information Error")
                        .setMessage("Error retrieving network configuration: ${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
                addAppLog("Failed to get network info for ${device.ipAddress}: ${e.message}", "ERROR")
            }
        }
    }
    
    private fun showNetworkConfigDialog(device: Device) {
        addAppLog("Opening network configuration for ${device.ipAddress}")
        
        val dialog = NetworkConfigDialog.newInstance(device) { updatedDevice ->
            // Handle configuration changes - refresh the device info
            addAppLog("Network configuration updated for ${updatedDevice.ipAddress}")
            // You could refresh the device scan or update the adapter here
        }
        
        dialog.show(supportFragmentManager, "NetworkConfigDialog")
    }

    private fun startArpScan() {
        Log.i(TAG, "Starting ARP scan for layer 2 discovery...")
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    addAppLog("Starting ARP scan for layer 2 discovery...", "INFO")
                }
                
                val arpDevices = arpScanner.scanNetwork()
                
                withContext(Dispatchers.Main) {
                    if (arpDevices.isNotEmpty()) {
                        addAppLog("ARP scan found ${arpDevices.size} devices on layer 2", "INFO")
                        
                        // Add ARP discovered devices to the adapter
                        arpDevices.forEach { device ->
                            deviceAdapter.addDevice(device)
                            if (device.isPrinter) {
                                Log.d(TAG, "ARP discovered printer: ${device.ipAddress} (${device.macAddress})")
                            }
                        }
                    } else {
                        addAppLog("ARP scan completed - no additional devices found", "INFO")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during ARP scan: ${e.message}")
                withContext(Dispatchers.Main) {
                    addAppLog("ARP scan failed: ${e.message}", "ERROR")
                }
            }
        }
    }
}

// Fragment to display content in dialog tabs
class ContentFragment : androidx.fragment.app.Fragment() {
    
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: android.os.Bundle?
    ): android.view.View? {
        return inflater.inflate(R.layout.fragment_info_content, container, false)
    }
    
    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val content = arguments?.getString("content") ?: ""
        val hasLink = arguments?.getBoolean("has_link") ?: false
        val textView = view.findViewById<TextView>(R.id.textContent)
        
        if (hasLink) {
            // Use SpannableString to create a clickable link
            val text = SpannableString(content)
            val linkUrl = "https://care.storehub.com/en/articles/7252231-faq-pos-hardware-supplementary-device-support"
            val linkStart = content.indexOf(linkUrl)
            
            if (linkStart >= 0) {
                text.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            // Open link in browser
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl))
                            startActivity(intent)
                        }
                        
                        override fun updateDrawState(ds: TextPaint) {
                            super.updateDrawState(ds)
                            ds.color = resources.getColor(R.color.orange_primary)
                            ds.isUnderlineText = true
                        }
                    },
                    linkStart,
                    linkStart + linkUrl.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                
                textView.text = text
                textView.movementMethod = LinkMovementMethod.getInstance()
                textView.highlightColor = Color.TRANSPARENT
            } else {
                textView.text = content
            }
        } else {
            textView.text = content
        }
    }
    
    companion object {
        fun newInstance(content: String): ContentFragment {
            val fragment = ContentFragment()
            fragment.arguments = android.os.Bundle().apply {
                putString("content", content)
                putBoolean("has_link", false)
            }
            return fragment
        }
    }
}
