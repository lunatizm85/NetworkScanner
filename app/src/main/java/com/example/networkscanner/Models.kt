package com.example.networkscanner

// Add an enum for discovery methods
enum class DiscoveryMethod {
    IP_SCAN,
    UDP,
    NSD,
    ARP_SCAN,  // Add ARP scanning as a new discovery method
    UNKNOWN;
    
    override fun toString(): String {
        return when(this) {
            IP_SCAN -> "IP Scan"
            UDP -> "UDP"
            NSD -> "mDNS/NSD"
            ARP_SCAN -> "ARP Scan"
            UNKNOWN -> "Unknown"
        }
    }
}

data class Device(
    val macAddress: String,
    val deviceName: String,
    val ipAddress: String,
    val latency: String,
    val isPrinter: Boolean = false,
    val port: Int = 9100,  // Default printer port
    val discoveryMethod: DiscoveryMethod = DiscoveryMethod.UNKNOWN, // Default to UNKNOWN
    var isDhcpEnabled: Boolean? = null, // null means unknown/not checked yet
    var isBeeperEnabled: Boolean? = null, // null means unknown/not checked yet
    val isDifferentSubnet: Boolean = false // Flag to indicate if device is on a different subnet
)

data class HealthCheckResult(
    val serviceName: String,
    val url: String,
    val status: String
) 