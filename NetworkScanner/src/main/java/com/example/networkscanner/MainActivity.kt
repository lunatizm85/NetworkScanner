package com.example.networkscanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private lateinit var wifiStatus: TextView
    private lateinit var ethernetStatus: TextView
    private lateinit var scanButton: Button
    private lateinit var addMacButton: Button
    private lateinit var devicesRecyclerView: RecyclerView
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val PERMISSIONS_REQUEST_CODE = 123
    private val commonPorts = listOf(80, 443, 8080, 8443, 8000, 8888)
    private val discoveredDevices = mutableListOf<NetworkDevice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wifiStatus = findViewById(R.id.wifiStatus)
        ethernetStatus = findViewById(R.id.ethernetStatus)
        scanButton = findViewById(R.id.scanButton)
        addMacButton = findViewById(R.id.addMacButton)
        devicesRecyclerView = findViewById(R.id.devicesRecyclerView)

        devicesRecyclerView.layoutManager = LinearLayoutManager(this)

        checkPermissions()
        setupNetworkCallback()
        updateNetworkInfo()

        scanButton.setOnClickListener {
            if (checkPermissions()) {
                startNetworkScan()
            }
        }

        addMacButton.setOnClickListener {
            showAddMacDialog()
        }
    }

    private fun checkPermissions(): Boolean {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest, PERMISSIONS_REQUEST_CODE)
            return false
        }
        return true
    }

    private fun setupNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                updateNetworkInfo()
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                updateNetworkInfo()
            }
        }

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
    }

    private fun updateNetworkInfo() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo

        runOnUiThread {
            // Update WiFi status
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                val ssid = wifiInfo.ssid.replace("\"", "")
                val signalStrength = wifiInfo.rssi
                val macAddress = wifiInfo.macAddress
                wifiStatus.text = "WiFi Connected\nSSID: $ssid\nSignal Strength: $signalStrength dBm\nMAC: $macAddress"
            } else {
                wifiStatus.text = "WiFi: Not Connected"
            }

            // Update Ethernet status
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) {
                val ethernetMac = getEthernetMacAddress()
                ethernetStatus.text = "Ethernet: Connected\nMAC: $ethernetMac"
            } else {
                ethernetStatus.text = "Ethernet: Not Connected"
            }
        }
    }

    private fun getEthernetMacAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.name.contains("eth0", ignoreCase = true)) {
                    val macBytes = networkInterface.hardwareAddress
                    if (macBytes != null) {
                        val sb = StringBuilder()
                        for (b in macBytes) {
                            sb.append(String.format("%02X:", b))
                        }
                        if (sb.isNotEmpty()) {
                            sb.deleteCharAt(sb.length - 1)
                        }
                        return sb.toString()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "Unknown"
    }

    private fun showAddMacDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_mac, null)
        val macEditText = dialogView.findViewById<EditText>(R.id.macEditText)

        AlertDialog.Builder(this)
            .setTitle("Add Device by MAC Address")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val macAddress = macEditText.text.toString().trim()
                if (isValidMacAddress(macAddress)) {
                    addDeviceByMac(macAddress)
                } else {
                    Toast.makeText(this, "Invalid MAC address format", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isValidMacAddress(mac: String): Boolean {
        val macPattern = "^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$"
        return mac.matches(Regex(macPattern))
    }

    private fun addDeviceByMac(macAddress: String) {
        scope.launch(Dispatchers.IO) {
            try {
                // Try to resolve IP from MAC using ARP
                val ip = getIpFromMac(macAddress)
                if (ip != null) {
                    val hostname = try {
                        InetAddress.getByName(ip).hostName
                    } catch (e: Exception) {
                        ip
                    }
                    val device = NetworkDevice(ip, hostname, macAddress)
                    discoveredDevices.add(device)
                    updateDeviceList()
                } else {
                    // If IP not found, add device with unknown IP
                    val device = NetworkDevice("Unknown", "Unknown", macAddress)
                    discoveredDevices.add(device)
                    updateDeviceList()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error adding device: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getIpFromMac(macAddress: String): String? {
        try {
            val process = Runtime.getRuntime().exec("cat /proc/net/arp")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            
            // Skip header line
            reader.readLine()
            
            while (reader.readLine().also { line = it } != null) {
                val parts = line?.split("\\s+".toRegex())
                if (parts?.size ?: 0 >= 4) {
                    val ip = parts?.get(0) ?: ""
                    val mac = parts?.get(3) ?: ""
                    if (mac.equals(macAddress, ignoreCase = true)) {
                        return ip
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun updateDeviceList() {
        runOnUiThread {
            devicesRecyclerView.adapter = NetworkDeviceAdapter(discoveredDevices) { device, isHttps ->
                checkDeviceWebInterface(device, isHttps)
            }
        }
    }

    private fun startNetworkScan() {
        scope.launch(Dispatchers.IO) {
            try {
                discoveredDevices.clear()
                
                // Get ARP table
                val arpTable = getArpTable()
                discoveredDevices.addAll(arpTable)

                // Scan local network
                val localIp = getLocalIpAddress()
                val networkPrefix = localIp.substring(0, localIp.lastIndexOf(".") + 1)

                for (i in 1..254) {
                    val ip = "$networkPrefix$i"
                    if (InetAddress.getByName(ip).isReachable(1000)) {
                        val hostname = InetAddress.getByName(ip).hostName
                        val macAddress = getMacFromArpCache(ip)
                        if (!discoveredDevices.any { it.ip == ip }) {
                            discoveredDevices.add(NetworkDevice(ip, hostname, macAddress))
                        }
                    }
                }

                updateDeviceList()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Scan failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkDeviceWebInterface(device: NetworkDevice, isHttps: Boolean) {
        scope.launch(Dispatchers.IO) {
            try {
                val protocol = if (isHttps) "https" else "http"
                var foundPort = -1

                // If IP is unknown, try to resolve it from MAC
                val targetIp = if (device.ip == "Unknown") {
                    getIpFromMac(device.macAddress) ?: device.ip
                } else {
                    device.ip
                }

                if (targetIp == "Unknown") {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "Cannot resolve IP for MAC: ${device.macAddress}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                // Try common ports
                for (port in commonPorts) {
                    try {
                        val url = URL("$protocol://$targetIp:$port")
                        val connection = url.openConnection() as HttpURLConnection
                        connection.connectTimeout = 1000
                        connection.readTimeout = 1000
                        connection.requestMethod = "HEAD"
                        
                        val responseCode = connection.responseCode
                        if (responseCode in 200..399) {
                            foundPort = port
                            break
                        }
                    } catch (e: Exception) {
                        continue
                    }
                }

                withContext(Dispatchers.Main) {
                    if (foundPort != -1) {
                        val url = "$protocol://$targetIp:$foundPort"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "No web interface found on ${device.macAddress}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Error accessing device: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun getArpTable(): List<NetworkDevice> {
        val devices = mutableListOf<NetworkDevice>()
        try {
            val process = Runtime.getRuntime().exec("cat /proc/net/arp")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            
            // Skip header line
            reader.readLine()
            
            while (reader.readLine().also { line = it } != null) {
                val parts = line?.split("\\s+".toRegex())
                if (parts?.size ?: 0 >= 4) {
                    val ip = parts?.get(0) ?: ""
                    val mac = parts?.get(3) ?: ""
                    if (mac != "00:00:00:00:00:00") {
                        val hostname = try {
                            InetAddress.getByName(ip).hostName
                        } catch (e: Exception) {
                            ip
                        }
                        devices.add(NetworkDevice(ip, hostname, mac))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return devices
    }

    private fun getMacFromArpCache(ip: String): String {
        try {
            val process = Runtime.getRuntime().exec("cat /proc/net/arp")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            
            // Skip header line
            reader.readLine()
            
            while (reader.readLine().also { line = it } != null) {
                val parts = line?.split("\\s+".toRegex())
                if (parts?.size ?: 0 >= 4 && parts?.get(0) == ip) {
                    return parts?.get(3) ?: "Unknown"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "Unknown"
    }

    private fun getLocalIpAddress(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address.hostAddress.indexOf(':') < 0) {
                    return address.hostAddress
                }
            }
        }
        return "127.0.0.1"
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

data class NetworkDevice(
    val ip: String,
    val hostname: String,
    val macAddress: String = "Unknown"
)

class NetworkDeviceAdapter(
    private val devices: List<NetworkDevice>,
    private val onWebInterfaceClick: (NetworkDevice, Boolean) -> Unit
) : RecyclerView.Adapter<NetworkDeviceAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ipAddress: TextView = view.findViewById(R.id.ipAddress)
        val macAddress: TextView = view.findViewById(R.id.macAddress)
        val hostname: TextView = view.findViewById(R.id.hostname)
        val httpButton: Button = view.findViewById(R.id.httpButton)
        val httpsButton: Button = view.findViewById(R.id.httpsButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_network_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        holder.ipAddress.text = device.ip
        holder.macAddress.text = device.macAddress
        holder.hostname.text = "Hostname: ${device.hostname}"

        holder.httpButton.setOnClickListener {
            onWebInterfaceClick(device, false)
        }

        holder.httpsButton.setOnClickListener {
            onWebInterfaceClick(device, true)
        }

        // Make IP and MAC clickable
        holder.ipAddress.setOnClickListener {
            val clipboard = android.content.ClipboardManager.getInstance(holder.itemView.context)
            val clip = android.content.ClipData.newPlainText("IP Address", device.ip)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(holder.itemView.context, "IP address copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        holder.macAddress.setOnClickListener {
            val clipboard = android.content.ClipboardManager.getInstance(holder.itemView.context)
            val clip = android.content.ClipData.newPlainText("MAC Address", device.macAddress)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(holder.itemView.context, "MAC address copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount() = devices.size
} 