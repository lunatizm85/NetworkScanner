package com.example.networkscanner

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class NetworkConfigDialog(
    private val device: Device,
    private val onConfigurationChanged: (Device) -> Unit
) : DialogFragment() {

    private lateinit var switchDhcp: SwitchCompat
    private lateinit var etStaticIP: TextInputEditText
    private lateinit var etSubnetMask: TextInputEditText
    private lateinit var etGateway: TextInputEditText
    private lateinit var tvConfigDetails: TextView
    
    private val networkConfigManager = XprinterNetworkConfigManager()
    private var currentConfig: XprinterNetworkConfig? = null

    companion object {
        private const val TAG = "NetworkConfigDialog"
        
        fun newInstance(device: Device, onConfigurationChanged: (Device) -> Unit): NetworkConfigDialog {
            return NetworkConfigDialog(device, onConfigurationChanged)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = LayoutInflater.from(requireContext())
        val view = inflater.inflate(R.layout.dialog_network_config, null)
        
        initializeViews(view)
        setupListeners()
        loadCurrentConfiguration()
        
        return AlertDialog.Builder(requireContext())
            .setView(view)
            .setTitle("Network Configuration")
            .setPositiveButton("Apply") { _, _ ->
                applyConfiguration()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Refresh") { _, _ ->
                loadCurrentConfiguration()
            }
            .create()
    }
    
    private fun initializeViews(view: View) {
        switchDhcp = view.findViewById(R.id.switchDhcp)
        etStaticIP = view.findViewById(R.id.etStaticIP)
        etSubnetMask = view.findViewById(R.id.etSubnetMask)
        etGateway = view.findViewById(R.id.etGateway)
        tvConfigDetails = view.findViewById(R.id.tvConfigDetails)
        
        // Set current IP as default for static configuration
        etStaticIP.setText(device.ipAddress)
        etSubnetMask.setText("255.255.255.0")
        etGateway.setText(getDefaultGateway(device.ipAddress))
    }
    
    private fun setupListeners() {
        switchDhcp.setOnCheckedChangeListener { _, isChecked ->
            updateStaticFieldsVisibility(!isChecked)
        }
    }
    
    private fun updateStaticFieldsVisibility(visible: Boolean) {
        val alpha = if (visible) 1.0f else 0.5f
        etStaticIP.alpha = alpha
        etSubnetMask.alpha = alpha
        etGateway.alpha = alpha
        
        etStaticIP.isEnabled = visible
        etSubnetMask.isEnabled = visible
        etGateway.isEnabled = visible
    }
    
    private fun getDefaultGateway(ipAddress: String): String {
        return try {
            val parts = ipAddress.split(".")
            if (parts.size == 4) {
                "${parts[0]}.${parts[1]}.${parts[2]}.1"
            } else {
                "192.168.1.1"
            }
        } catch (e: Exception) {
            "192.168.1.1"
        }
    }
    
    private fun loadCurrentConfiguration() {
        tvConfigDetails.text = "Loading configuration..."
        
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Loading network configuration for ${device.ipAddress}")
                currentConfig = networkConfigManager.getNetworkConfiguration(device.ipAddress)
                
                currentConfig?.let { config ->
                    Log.d(TAG, "Configuration loaded: DHCP=${config.isDhcpEnabled}, Method=${config.configMethod}")
                    updateUIWithConfig(config)
                } ?: run {
                    Log.w(TAG, "Could not load configuration for ${device.ipAddress}")
                    updateUIWithError("Could not retrieve network configuration. The printer may not support remote configuration or is not accessible.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading configuration: ${e.message}")
                updateUIWithError("Error loading configuration: ${e.message}")
            }
        }
    }
    
    private fun updateUIWithConfig(config: XprinterNetworkConfig) {
        switchDhcp.isChecked = config.isDhcpEnabled
        updateStaticFieldsVisibility(!config.isDhcpEnabled)
        
        if (!config.isDhcpEnabled) {
            etStaticIP.setText(config.ipAddress)
            etSubnetMask.setText(config.subnetMask)
            etGateway.setText(config.gateway)
        }
        
        tvConfigDetails.text = buildString {
            appendLine("Current Configuration:")
            appendLine("• DHCP: ${if (config.isDhcpEnabled) "Enabled" else "Disabled"}")
            appendLine("• IP Address: ${config.ipAddress}")
            appendLine("• Subnet Mask: ${config.subnetMask}")
            appendLine("• Gateway: ${config.gateway}")
            if (config.macAddress != "Unknown") {
                appendLine("• MAC Address: ${config.macAddress}")
            }
            appendLine("• Configuration Method: ${config.configMethod}")
        }
    }
    
    private fun updateUIWithError(message: String) {
        tvConfigDetails.text = message
        // Enable manual configuration even if auto-detection failed
        switchDhcp.isChecked = false
        updateStaticFieldsVisibility(true)
    }
    
    private fun applyConfiguration() {
        val isDhcpEnabled = switchDhcp.isChecked
        
        lifecycleScope.launch {
            try {
                val success = if (isDhcpEnabled) {
                    enableDHCP()
                } else {
                    setStaticIP()
                }
                
                if (success) {
                    Toast.makeText(requireContext(), "Configuration applied successfully", Toast.LENGTH_SHORT).show()
                    // Refresh the device information after a delay
                    kotlinx.coroutines.delay(2000)
                    loadCurrentConfiguration()
                    onConfigurationChanged(device)
                } else {
                    Toast.makeText(requireContext(), "Failed to apply configuration", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error applying configuration: ${e.message}")
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private suspend fun enableDHCP(): Boolean {
        Log.d(TAG, "Enabling DHCP for ${device.ipAddress}")
        return networkConfigManager.enableDHCP(device.ipAddress)
    }
    
    private suspend fun setStaticIP(): Boolean {
        val newIP = etStaticIP.text.toString().trim()
        val subnetMask = etSubnetMask.text.toString().trim()
        val gateway = etGateway.text.toString().trim()
        
        if (!isValidIP(newIP)) {
            Toast.makeText(requireContext(), "Invalid IP address", Toast.LENGTH_SHORT).show()
            return false
        }
        
        if (!isValidIP(subnetMask)) {
            Toast.makeText(requireContext(), "Invalid subnet mask", Toast.LENGTH_SHORT).show()
            return false
        }
        
        if (!isValidIP(gateway)) {
            Toast.makeText(requireContext(), "Invalid gateway", Toast.LENGTH_SHORT).show()
            return false
        }
        
        Log.d(TAG, "Setting static IP for ${device.ipAddress}: $newIP/$subnetMask via $gateway")
        return networkConfigManager.setStaticIP(device.ipAddress, newIP, subnetMask, gateway)
    }
    
    private fun isValidIP(ip: String): Boolean {
        return try {
            val parts = ip.split(".")
            if (parts.size != 4) return false
            
            parts.forEach { part ->
                val num = part.toInt()
                if (num < 0 || num > 255) return false
            }
            true
        } catch (e: Exception) {
            false
        }
    }
} 