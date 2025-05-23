package com.example.networkscanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HealthCheckAdapter : RecyclerView.Adapter<HealthCheckAdapter.ViewHolder>() {
    private var healthChecks = listOf<HealthCheckResult>()

    fun updateData(newData: List<HealthCheckResult>) {
        healthChecks = newData
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_health_check, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val healthCheck = healthChecks[position]
        holder.serviceNameText.text = healthCheck.serviceName
        holder.serviceUrlText.text = healthCheck.url
        holder.statusText.text = healthCheck.status
        holder.statusText.setTextColor(
            holder.itemView.context.getColor(
                if (healthCheck.status == "Online") R.color.green_online else R.color.red_failed
            )
        )
    }

    override fun getItemCount() = healthChecks.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val serviceNameText: TextView = view.findViewById(R.id.serviceNameText)
        val serviceUrlText: TextView = view.findViewById(R.id.serviceUrlText)
        val statusText: TextView = view.findViewById(R.id.statusText)
    }
} 