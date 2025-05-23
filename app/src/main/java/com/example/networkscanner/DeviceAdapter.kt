package com.example.networkscanner

import android.app.AlertDialog
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeviceAdapter(
    private val onTestPrintClick: (Device) -> Unit,
    private val onDocketPrintClick: (Device, Int) -> Unit,
    private val onNetworkInfoClick: (Device) -> Unit,
    private val onNetworkConfigClick: (Device) -> Unit,
    private val coroutineScope: CoroutineScope
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    private val devices = mutableListOf<Device>()

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardView: CardView = itemView.findViewById(R.id.cardView)
        val deviceIcon: ImageView = itemView.findViewById(R.id.deviceIcon)
        val deviceName: TextView = itemView.findViewById(R.id.deviceName)
        val ipAddress: TextView = itemView.findViewById(R.id.ipAddress)
        val deviceInfo: TextView = itemView.findViewById(R.id.deviceInfo)
        val discoveryMethod: TextView = itemView.findViewById(R.id.discoveryMethod)
        val actionButtons: LinearLayout = itemView.findViewById(R.id.actionButtons)
        val testPrintButton: Button = itemView.findViewById(R.id.testPrintButton)
        val docketPrintButton: Button = itemView.findViewById(R.id.docketPrintButton)
        val networkConfigButtons: LinearLayout = itemView.findViewById(R.id.networkConfigButtons)
        val checkNetworkButton: Button = itemView.findViewById(R.id.checkNetworkButton)
        val configNetworkButton: Button = itemView.findViewById(R.id.configNetworkButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        
        // Set device name and IP
        holder.deviceName.text = device.deviceName
        holder.ipAddress.text = device.ipAddress
        
        // Set discovery method
        holder.discoveryMethod.text = "Found via: ${device.discoveryMethod}"
        
        // Set MAC and latency info
        if (device.isPrinter && device.isDhcpEnabled != null) {
            val dhcpStatus = if (device.isDhcpEnabled == true) "DHCP: Enabled" else "DHCP: Static IP"
            holder.deviceInfo.text = "MAC: ${device.macAddress} | ${dhcpStatus} | Latency: ${device.latency}"
        } else {
            holder.deviceInfo.text = "MAC: ${device.macAddress} | Latency: ${device.latency}"
        }
        
        // Set icon based on device type
        if (device.isPrinter) {
            holder.deviceIcon.setImageResource(R.drawable.ic_print)
            holder.actionButtons.visibility = View.VISIBLE
            
            // Setup test print button
            holder.testPrintButton.setOnClickListener {
                onTestPrintClick(device)
            }
            
            // Setup docket print button with number picker dialog
            holder.docketPrintButton.setOnClickListener {
                showQuantityPickerDialog(device, holder)
            }
            
            // Show network configuration buttons only for layer 2 detected printers
            if (device.discoveryMethod == DiscoveryMethod.ARP_SCAN) {
                holder.networkConfigButtons.visibility = View.VISIBLE
                
                // Setup network info button
                holder.checkNetworkButton.setOnClickListener {
                    onNetworkInfoClick(device)
                }
                
                // Setup network configuration button
                holder.configNetworkButton.setOnClickListener {
                    onNetworkConfigClick(device)
                }
            } else {
                holder.networkConfigButtons.visibility = View.GONE
            }
        } else {
            holder.deviceIcon.setImageResource(R.drawable.ic_device)
            holder.actionButtons.visibility = View.GONE
            holder.networkConfigButtons.visibility = View.GONE
        }
        
        // Set background color based on discovery method and subnet
        when {
            // Layer 2 discovered printers (ARP scan) - distinct purple color for easy identification
            device.isPrinter && device.discoveryMethod == DiscoveryMethod.ARP_SCAN -> {
                if (device.isDifferentSubnet) {
                    // Layer 2 printer on different subnet - strong purple
                    holder.cardView.setCardBackgroundColor(Color.parseColor("#D1C4E9")) // Medium purple
                    holder.discoveryMethod.text = "Found via: ${device.discoveryMethod} (Layer 2 - Different Subnet)"
                } else {
                    // Layer 2 printer on same subnet - light purple
                    holder.cardView.setCardBackgroundColor(Color.parseColor("#E8D4F8")) // Light purple
                    holder.discoveryMethod.text = "Found via: ${device.discoveryMethod} (Layer 2)"
                }
            }
            // Layer 2 discovered non-printers on different subnet
            !device.isPrinter && device.discoveryMethod == DiscoveryMethod.ARP_SCAN && device.isDifferentSubnet -> {
                holder.cardView.setCardBackgroundColor(Color.parseColor("#FFE0B2")) // Light orange
                holder.discoveryMethod.text = "Found via: ${device.discoveryMethod} (Layer 2 - Different Subnet)"
            }
            // Layer 2 discovered non-printers on same subnet
            !device.isPrinter && device.discoveryMethod == DiscoveryMethod.ARP_SCAN -> {
                holder.cardView.setCardBackgroundColor(Color.parseColor("#F3E5F5")) // Very light purple
                holder.discoveryMethod.text = "Found via: ${device.discoveryMethod} (Layer 2)"
            }
            // Other devices on different subnet (discovered via other methods)
            device.isDifferentSubnet -> {
                if (device.isPrinter) {
                    // Printer on different subnet - light red
                    holder.cardView.setCardBackgroundColor(Color.parseColor("#FFCCCC"))
                } else {
                    // Non-printer on different subnet - light yellow
                    holder.cardView.setCardBackgroundColor(Color.parseColor("#FFFFCC"))
                }
                // Add a visual indicator in the discovery method text
                holder.discoveryMethod.text = "Found via: ${device.discoveryMethod} (Different Subnet)"
            }
            // Normal devices on same subnet
            else -> {
                if (device.isPrinter) {
                    // Printer on same subnet - light green
                    holder.cardView.setCardBackgroundColor(Color.parseColor("#E0F2E9"))
                } else {
                    // Regular device - white
                    holder.cardView.setCardBackgroundColor(Color.WHITE)
                }
                holder.discoveryMethod.text = "Found via: ${device.discoveryMethod}"
            }
        }
    }

    override fun getItemCount(): Int = devices.size

    fun updateData(newDevices: List<Device>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }
    
    fun updateDevice(newDevice: Device) {
        val existingIndex = devices.indexOfFirst { it.ipAddress == newDevice.ipAddress }
        if (existingIndex >= 0) {
            devices[existingIndex] = newDevice
            notifyItemChanged(existingIndex)
        } else {
            devices.add(newDevice)
            notifyItemInserted(devices.size - 1)
        }
    }
    
    fun getDeviceByIp(ipAddress: String): Device? {
        return devices.find { it.ipAddress == ipAddress }
    }
    
    fun removeDeviceByIp(ipAddress: String?): Boolean {
        if (ipAddress == null) return false
        val index = devices.indexOfFirst { it.ipAddress == ipAddress }
        if (index >= 0) {
            devices.removeAt(index)
            notifyItemRemoved(index)
            return true
        }
        return false
    }
    
    fun addDevice(device: Device) {
        // Check if device already exists by IP
        val index = devices.indexOfFirst { it.ipAddress == device.ipAddress }
        if (index >= 0) {
            // Update existing device
            devices[index] = device
            notifyItemChanged(index)
        } else {
            // Add new device
            devices.add(device)
            notifyItemInserted(devices.size - 1)
        }
    }
    
    private fun showQuantityPickerDialog(device: Device, holder: ViewHolder) {
        val context = holder.itemView.context
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_quantity_picker, null)
        val numberPicker = dialogView.findViewById<NumberPicker>(R.id.numberPicker)
        
        numberPicker.minValue = 1
        numberPicker.maxValue = 10
        numberPicker.value = 1
        
        AlertDialog.Builder(context)
            .setTitle("Select Number of Dockets")
            .setView(dialogView)
            .setPositiveButton("Print") { _, _ ->
                val quantity = numberPicker.value
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        onDocketPrintClick(device, quantity)
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            // Handle error if needed
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}