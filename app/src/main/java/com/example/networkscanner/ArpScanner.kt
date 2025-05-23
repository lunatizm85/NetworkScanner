package com.example.networkscanner

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket
import java.net.InetSocketAddress

/**
 * ArpScanner provides functionality to discover devices on the network
 * using ARP (Address Resolution Protocol) at OSI Layer 2.
 * This can find devices on different subnets but same physical network.
 */
class ArpScanner(private val context: Context) {
    private val TAG = "ArpScanner"
    
    // Common printer ports to check
    private val printerPorts = listOf(9100, 515, 631, 80, 443)
    
    /**
     * Scan the local network for devices using ARP protocol.
     * This method reads the ARP cache file and also executes ARP requests
     * to find devices on the local network.
     * 
     * @return a list of discovered devices
     */
    suspend fun scanNetwork(): List<Device> = withContext(Dispatchers.IO) {
        val discoveredDevices = mutableListOf<Device>()
        
        try {
            Log.d(TAG, "Starting ARP scan for layer 2 discovery...")
            
            // First, populate ARP cache by pinging various ranges
            populateArpCache()
            
            // Wait a bit for ARP responses
            delay(2000)
            
            // Read the ARP cache
            val arpEntries = readArpTable()
            Log.d(TAG, "Found ${arpEntries.size} entries in ARP cache")
            
            // Get current device's network info for subnet comparison
            val localIp = getLocalIpAddress()
            val localSubnet = getSubnetFromIp(localIp)
            
            // Process ARP entries and create Device objects
            for (entry in arpEntries) {
                val ipAddress = entry["ip"] ?: continue
                val macAddress = entry["mac"] ?: continue
                val flags = entry["flags"] ?: "0x0"
                
                // Skip incomplete entries or loopback
                if (macAddress == "00:00:00:00:00:00" || flags == "0x0" || ipAddress == "127.0.0.1") {
                    continue
                }
                
                try {
                    // Check if device is on different subnet
                    val deviceSubnet = getSubnetFromIp(ipAddress)
                    val isDifferentSubnet = localSubnet != deviceSubnet
                    
                    // Try to get hostname
                    val hostname = try {
                        val inetAddress = InetAddress.getByName(ipAddress)
                        inetAddress.hostName ?: ipAddress
                    } catch (e: Exception) {
                        ipAddress
                    }
                    
                    // Check if it's a printer by MAC address and ports
                    val isPrinterByMac = isPossiblePrinter(macAddress)
                    val isPrinterByPort = if (isPrinterByMac) checkPrinterPorts(ipAddress) else false
                    val isPrinter = isPrinterByMac || isPrinterByPort
                    
                    // Create device name
                    val deviceName = when {
                        isPrinter -> if (hostname != ipAddress) "Printer: $hostname" else "Printer: $ipAddress"
                        hostname != ipAddress -> hostname
                        else -> "Device at $ipAddress"
                    }
                    
                    val device = Device(
                        macAddress = macAddress,
                        deviceName = deviceName,
                        ipAddress = ipAddress,
                        latency = "N/A (ARP)",
                        isPrinter = isPrinter,
                        discoveryMethod = DiscoveryMethod.ARP_SCAN,
                        isDifferentSubnet = isDifferentSubnet
                    )
                    
                    discoveredDevices.add(device)
                    Log.d(TAG, "Found device via ARP: $ipAddress (${macAddress}) - Printer: $isPrinter, Different subnet: $isDifferentSubnet")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing ARP entry: ${e.message}")
                }
            }
            
            Log.d(TAG, "ARP scan completed. Found ${discoveredDevices.size} devices")
        } catch (e: Exception) {
            Log.e(TAG, "Error during ARP scan: ${e.message}")
        }
        
        return@withContext discoveredDevices
    }
    
    /**
     * Get local IP address
     */
    private fun getLocalIpAddress(): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipAddress = wifiManager.connectionInfo.ipAddress
            
            String.format(
                "%d.%d.%d.%d",
                (ipAddress and 0xff),
                (ipAddress shr 8 and 0xff),
                (ipAddress shr 16 and 0xff),
                (ipAddress shr 24 and 0xff)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP: ${e.message}")
            "192.168.1.1" // Fallback
        }
    }
    
    /**
     * Extract subnet from IP address (first 3 octets)
     */
    private fun getSubnetFromIp(ipAddress: String): String {
        return try {
            val parts = ipAddress.split(".")
            if (parts.size >= 3) {
                "${parts[0]}.${parts[1]}.${parts[2]}"
            } else {
                "unknown"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    /**
     * Populate ARP cache by pinging various network ranges
     */
    private suspend fun populateArpCache() = withContext(Dispatchers.IO) {
        try {
            val localIp = getLocalIpAddress()
            val networkPrefix = localIp.substring(0, localIp.lastIndexOf(".") + 1)
            
            Log.d(TAG, "Populating ARP cache for network $networkPrefix")
            
            // Ping common addresses to populate ARP cache
            val commonAddresses = listOf(
                "${networkPrefix}1",    // Gateway
                "${networkPrefix}254",  // Common gateway/broadcast range
                "${networkPrefix}100",  // Common DHCP range
                "${networkPrefix}101",
                "${networkPrefix}102",
                "${networkPrefix}150",
                "${networkPrefix}200"
            )
            
            // Also try some different subnet common ranges that might be on same L2
            val differentSubnets = listOf(
                "192.168.1.1", "192.168.1.100", "192.168.1.200",
                "192.168.0.1", "192.168.0.100", "192.168.0.200", 
                "10.0.0.1", "10.0.0.100", "10.0.1.1",
                "172.16.0.1", "172.16.1.1"
            )
            
            val allTargets = commonAddresses + differentSubnets
            
            for (targetIp in allTargets) {
                try {
                    // Use arping if available, otherwise use ping
                    val process = Runtime.getRuntime().exec("ping -c 1 -W 1 $targetIp")
                    process.waitFor()
                    
                    // Also try arping for better ARP population
                    try {
                        val arpProcess = Runtime.getRuntime().exec("arping -c 1 -f $targetIp")
                        arpProcess.waitFor()
                    } catch (e: Exception) {
                        // arping might not be available on all devices
                    }
                    
                } catch (e: Exception) {
                    Log.d(TAG, "Error pinging $targetIp: ${e.message}")
                }
            }
            
            // Force ARP table refresh
            try {
                val process = Runtime.getRuntime().exec("ip neighbor show")
                process.waitFor()
            } catch (e: Exception) {
                Log.d(TAG, "ip neighbor command not available")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error populating ARP cache: ${e.message}")
        }
    }
    
    /**
     * Check if device has printer ports open
     */
    private suspend fun checkPrinterPorts(ipAddress: String): Boolean = withContext(Dispatchers.IO) {
        for (port in printerPorts) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ipAddress, port), 2000)
                socket.close()
                Log.d(TAG, "Found open printer port $port on $ipAddress")
                return@withContext true
            } catch (e: Exception) {
                // Port not open, continue checking
            }
        }
        return@withContext false
    }
    
    /**
     * Reads the ARP table from /proc/net/arp
     * 
     * @return List of map entries with IP, MAC, and flags
     */
    private fun readArpTable(): List<Map<String, String>> {
        val entries = mutableListOf<Map<String, String>>()
        
        try {
            val file = File("/proc/net/arp")
            if (file.exists()) {
                val reader = BufferedReader(FileReader(file))
                var line: String?
                
                // Skip header
                reader.readLine()
                
                // Parse each line
                while (reader.readLine().also { line = it } != null) {
                    line?.let {
                        val parts = it.trim().split("\\s+".toRegex())
                        if (parts.size >= 6) {
                            val entry = mapOf(
                                "ip" to parts[0],
                                "hwtype" to parts[1],
                                "flags" to parts[2],
                                "mac" to parts[3],
                                "mask" to parts[4],
                                "device" to parts[5]
                            )
                            entries.add(entry)
                        }
                    }
                }
                reader.close()
            } else {
                Log.w(TAG, "ARP cache file not found, trying alternative methods")
                
                // Try alternative method using 'ip neighbor'
                try {
                    val process = Runtime.getRuntime().exec("ip neighbor show")
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line: String?
                    
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { 
                            // Parse 'ip neighbor' output: 192.168.1.1 dev wlan0 lladdr aa:bb:cc:dd:ee:ff REACHABLE
                            val parts = it.split("\\s+".toRegex())
                            if (parts.size >= 5 && parts.contains("lladdr")) {
                                val ipIndex = 0
                                val macIndex = parts.indexOf("lladdr") + 1
                                if (macIndex < parts.size) {
                                    val entry = mapOf(
                                        "ip" to parts[ipIndex],
                                        "hwtype" to "0x1",
                                        "flags" to "0x2", 
                                        "mac" to parts[macIndex],
                                        "mask" to "*",
                                        "device" to "wlan0"
                                    )
                                    entries.add(entry)
                                }
                            }
                        }
                    }
                    reader.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error using ip neighbor: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading ARP table: ${e.message}")
        }
        
        return entries
    }
    
    /**
     * Check if a device is likely a printer based on its MAC address OUI (first 6 characters)
     * 
     * @param macAddress The MAC address to check
     * @return true if likely a printer, false otherwise
     */
    fun isPossiblePrinter(macAddress: String): Boolean {
        // Common printer manufacturer OUIs (first 6 chars of MAC)
        val printerOUIs = listOf(
            "00:17:A5", // XPrinter
            "00:0D:F0", // XPrinter
            "00:19:0F", // XPrinter
            "70:B3:D5", // XPrinter
            "00:20:7A", // XPrinter
            "00:12:1B", // XPrinter
            "00:00:48", // Epson
            "00:26:AB", // Epson
            "64:00:F1", // Epson
            "00:0E:C4", // Zebra
            "00:07:4D", // Zebra
            "00:15:70", // Zebra
            "E0:AE:ED", // HP
            "00:1F:29", // HP
            "04:7D:7B", // HP
            "00:17:08", // Canon
            "00:0D:93", // Canon
            "00:80:92", // Brother
            "00:1A:3F", // Brother
            "A4:5D:36", // Lexmark
            "00:10:83"  // Various printer manufacturers
        )
        
        // Normalize MAC address
        val normalizedMac = macAddress.uppercase().replace("-", ":")
        
        // Check if MAC matches any known printer manufacturer
        return printerOUIs.any { oui ->
            normalizedMac.startsWith(oui.uppercase())
        }
    }
} 