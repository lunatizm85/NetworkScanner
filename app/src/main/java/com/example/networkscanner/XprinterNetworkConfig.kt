package com.example.networkscanner

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/**
 * Data class representing Xprinter network configuration
 */
data class XprinterNetworkConfig(
    val isDhcpEnabled: Boolean,
    val ipAddress: String,
    val subnetMask: String,
    val gateway: String,
    val macAddress: String,
    val configMethod: String // "WEB", "SNMP", "ESCPOS"
)

/**
 * Manager class for Xprinter network configuration
 * Based on SkyService documentation: https://support.skyservice.pro/en/xprinter-wi-fi-setup/
 */
class XprinterNetworkConfigManager {
    
    companion object {
        private const val TAG = "XprinterNetConfig"
        private const val DEFAULT_WEB_PORT = 80
        private const val DEFAULT_PRINTER_PORT = 9100
        private const val CONNECTION_TIMEOUT = 3000
    }
    
    /**
     * Detect which configuration method the printer supports
     */
    suspend fun detectConfigurationMethod(ipAddress: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                // Try web interface first (most common for Xprinter)
                if (checkWebInterface(ipAddress)) {
                    Log.d(TAG, "Printer at $ipAddress supports WEB configuration")
                    return@withContext "WEB"
                }
                
                // Try ESC/POS commands via printer port
                if (checkESCPOSAccess(ipAddress)) {
                    Log.d(TAG, "Printer at $ipAddress supports ESC/POS configuration")
                    return@withContext "ESCPOS"
                }
                
                Log.w(TAG, "No supported configuration method found for $ipAddress")
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error detecting configuration method for $ipAddress: ${e.message}")
                null
            }
        }
    }
    
    /**
     * Check if printer supports web interface configuration
     */
    private suspend fun checkWebInterface(ipAddress: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("http://$ipAddress/")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECTION_TIMEOUT
                connection.readTimeout = CONNECTION_TIMEOUT
                connection.requestMethod = "GET"
                
                val responseCode = connection.responseCode
                val isWebSupported = responseCode == 200 || responseCode == 401 // 401 might indicate login required
                
                Log.d(TAG, "Web interface check for $ipAddress: $responseCode (supported: $isWebSupported)")
                isWebSupported
            } catch (e: Exception) {
                Log.d(TAG, "Web interface not available for $ipAddress: ${e.message}")
                false
            }
        }
    }
    
    /**
     * Check if printer supports ESC/POS configuration commands
     */
    private suspend fun checkESCPOSAccess(ipAddress: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(java.net.InetSocketAddress(ipAddress, DEFAULT_PRINTER_PORT), CONNECTION_TIMEOUT)
                socket.close()
                Log.d(TAG, "ESC/POS port accessible for $ipAddress")
                true
            } catch (e: Exception) {
                Log.d(TAG, "ESC/POS port not accessible for $ipAddress: ${e.message}")
                false
            }
        }
    }
    
    /**
     * Get current network configuration from printer
     */
    suspend fun getNetworkConfiguration(ipAddress: String): XprinterNetworkConfig? {
        return withContext(Dispatchers.IO) {
            val method = detectConfigurationMethod(ipAddress) ?: return@withContext null
            
            when (method) {
                "WEB" -> getConfigViaWeb(ipAddress)
                "ESCPOS" -> getConfigViaESCPOS(ipAddress)
                else -> null
            }
        }
    }
    
    /**
     * Get configuration via web interface
     */
    private suspend fun getConfigViaWeb(ipAddress: String): XprinterNetworkConfig? {
        return withContext(Dispatchers.IO) {
            try {
                // Try to access the configuration page
                val configUrl = URL("http://$ipAddress/config.html") // Common Xprinter config page
                val connection = configUrl.openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECTION_TIMEOUT
                connection.readTimeout = CONNECTION_TIMEOUT
                
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    parseWebConfig(response, ipAddress)
                } else {
                    // Try alternative URLs
                    tryAlternativeConfigUrls(ipAddress)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting config via web for $ipAddress: ${e.message}")
                null
            }
        }
    }
    
    /**
     * Try alternative configuration URLs
     */
    private suspend fun tryAlternativeConfigUrls(ipAddress: String): XprinterNetworkConfig? {
        val urls = listOf(
            "http://$ipAddress/network.html",
            "http://$ipAddress/status.html",
            "http://$ipAddress/admin.html",
            "http://$ipAddress/"
        )
        
        for (url in urls) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECTION_TIMEOUT
                connection.readTimeout = CONNECTION_TIMEOUT
                
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val config = parseWebConfig(response, ipAddress)
                    if (config != null) return config
                }
            } catch (e: Exception) {
                Log.d(TAG, "Failed to access $url: ${e.message}")
                continue
            }
        }
        return null
    }
    
    /**
     * Parse web configuration response
     */
    private fun parseWebConfig(response: String, ipAddress: String): XprinterNetworkConfig? {
        return try {
            // Look for DHCP settings in the HTML response
            val isDhcp = response.contains("DHCP", ignoreCase = true) && 
                        (response.contains("enabled", ignoreCase = true) || 
                         response.contains("on", ignoreCase = true))
            
            // Extract network information (basic parsing)
            // This would need to be adjusted based on actual Xprinter web interface format
            XprinterNetworkConfig(
                isDhcpEnabled = isDhcp,
                ipAddress = ipAddress,
                subnetMask = "255.255.255.0", // Default, would parse from response
                gateway = "${ipAddress.substring(0, ipAddress.lastIndexOf('.'))}.1", // Default assumption
                macAddress = "Unknown", // Would parse from response
                configMethod = "WEB"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing web config: ${e.message}")
            null
        }
    }
    
    /**
     * Get configuration via ESC/POS commands
     */
    private suspend fun getConfigViaESCPOS(ipAddress: String): XprinterNetworkConfig? {
        return withContext(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(java.net.InetSocketAddress(ipAddress, DEFAULT_PRINTER_PORT), CONNECTION_TIMEOUT)
                
                val outputStream = socket.getOutputStream()
                val inputStream = socket.getInputStream()
                
                // Send ESC/POS command to get network information
                // Command from SkyService documentation: ESC i (network info)
                val networkInfoCommand = byteArrayOf(
                    0x1B, 0x69, 0x01, 0x00  // ESC i - Print network information
                )
                
                outputStream.write(networkInfoCommand)
                outputStream.flush()
                
                // Set timeout for reading response
                socket.soTimeout = 2000
                
                // Read response
                val buffer = ByteArray(1024)
                val bytesRead = inputStream.read(buffer)
                socket.close()
                
                if (bytesRead > 0) {
                    val response = String(buffer, 0, bytesRead)
                    parseESCPOSConfig(response, ipAddress)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting config via ESC/POS for $ipAddress: ${e.message}")
                null
            }
        }
    }
    
    /**
     * Parse ESC/POS configuration response
     */
    private fun parseESCPOSConfig(response: String, ipAddress: String): XprinterNetworkConfig? {
        return try {
            // Parse the printed network information
            val isDhcp = response.contains("DHCP:ON", ignoreCase = true) ||
                        response.contains("DHCP: ON", ignoreCase = true)
            
            XprinterNetworkConfig(
                isDhcpEnabled = isDhcp,
                ipAddress = ipAddress,
                subnetMask = "255.255.255.0", // Would parse from response
                gateway = "${ipAddress.substring(0, ipAddress.lastIndexOf('.'))}.1",
                macAddress = "Unknown", // Would parse from response
                configMethod = "ESCPOS"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ESC/POS config: ${e.message}")
            null
        }
    }
    
    /**
     * Enable DHCP on the printer
     */
    suspend fun enableDHCP(ipAddress: String): Boolean {
        return withContext(Dispatchers.IO) {
            val method = detectConfigurationMethod(ipAddress) ?: return@withContext false
            
            when (method) {
                "WEB" -> enableDHCPViaWeb(ipAddress)
                "ESCPOS" -> enableDHCPViaESCPOS(ipAddress)
                else -> false
            }
        }
    }
    
    /**
     * Set static IP configuration
     */
    suspend fun setStaticIP(ipAddress: String, newIP: String, subnetMask: String, gateway: String): Boolean {
        return withContext(Dispatchers.IO) {
            val method = detectConfigurationMethod(ipAddress) ?: return@withContext false
            
            when (method) {
                "WEB" -> setStaticIPViaWeb(ipAddress, newIP, subnetMask, gateway)
                "ESCPOS" -> setStaticIPViaESCPOS(ipAddress, newIP, subnetMask, gateway)
                else -> false
            }
        }
    }
    
    /**
     * Enable DHCP via web interface
     */
    private suspend fun enableDHCPViaWeb(ipAddress: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("http://$ipAddress/cgi-bin/config")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                
                val params = "action=set&dhcp=1"
                connection.outputStream.write(params.toByteArray())
                
                val success = connection.responseCode == 200
                Log.d(TAG, "DHCP enable via web for $ipAddress: $success")
                success
            } catch (e: Exception) {
                Log.e(TAG, "Error enabling DHCP via web for $ipAddress: ${e.message}")
                false
            }
        }
    }
    
    /**
     * Set static IP via web interface
     */
    private suspend fun setStaticIPViaWeb(ipAddress: String, newIP: String, subnetMask: String, gateway: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("http://$ipAddress/cgi-bin/config")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                
                val params = "action=set&dhcp=0&ip=$newIP&mask=$subnetMask&gateway=$gateway"
                connection.outputStream.write(params.toByteArray())
                
                val success = connection.responseCode == 200
                Log.d(TAG, "Static IP set via web for $ipAddress: $success")
                success
            } catch (e: Exception) {
                Log.e(TAG, "Error setting static IP via web for $ipAddress: ${e.message}")
                false
            }
        }
    }
    
    /**
     * Enable DHCP via ESC/POS commands
     */
    private suspend fun enableDHCPViaESCPOS(ipAddress: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(java.net.InetSocketAddress(ipAddress, DEFAULT_PRINTER_PORT), CONNECTION_TIMEOUT)
                
                val outputStream = socket.getOutputStream()
                
                // ESC/POS command to enable DHCP (based on SkyService documentation)
                // Command format: 1F 1B 1F 91 00 49 50 01 (enable DHCP)
                val enableDhcpCommand = byteArrayOf(
                    0x1F, 0x1B.toByte(), 0x1F, 0x91.toByte(), 0x00, 0x49, 0x50, 0x01
                )
                
                outputStream.write(enableDhcpCommand)
                outputStream.flush()
                socket.close()
                
                Log.d(TAG, "DHCP enable command sent via ESC/POS to $ipAddress")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error enabling DHCP via ESC/POS for $ipAddress: ${e.message}")
                false
            }
        }
    }
    
    /**
     * Set static IP via ESC/POS commands
     */
    private suspend fun setStaticIPViaESCPOS(ipAddress: String, newIP: String, subnetMask: String, gateway: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(java.net.InetSocketAddress(ipAddress, DEFAULT_PRINTER_PORT), CONNECTION_TIMEOUT)
                
                val outputStream = socket.getOutputStream()
                
                // Convert IP address to hex format for ESC/POS command
                val ipBytes = newIP.split(".").map { it.toInt().toByte() }.toByteArray()
                
                // ESC/POS command to set static IP (based on SkyService documentation)
                // Command format: 1F 1B 1F 91 00 49 50 [IP bytes]
                val setStaticIPCommand = byteArrayOf(
                    0x1F, 0x1B.toByte(), 0x1F, 0x91.toByte(), 0x00, 0x49, 0x50
                ) + ipBytes
                
                outputStream.write(setStaticIPCommand)
                outputStream.flush()
                socket.close()
                
                Log.d(TAG, "Static IP command sent via ESC/POS to $ipAddress")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error setting static IP via ESC/POS for $ipAddress: ${e.message}")
                false
            }
        }
    }
} 