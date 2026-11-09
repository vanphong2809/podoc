package com.podoc.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MemoryStat(
    @SerialName("scope") val scope: String = "host",
    @SerialName("formatted_cache") val formattedCache: String = "0 B",
    @SerialName("total_bytes") val totalBytes: Long = 0L,
    @SerialName("used_bytes") val usedBytes: Long = 0L,
    @SerialName("free_bytes") val freeBytes: Long = 0L,
    @SerialName("percent_used") val percentUsed: Float = 0f,
    @SerialName("formatted_total") val formattedTotal: String = "0 B",
    @SerialName("formatted_used") val formattedUsed: String = "0 B",
    @SerialName("formatted_free") val formattedFree: String = "0 B"
)

@Serializable
data class DiskStat(
    @SerialName("total_bytes") val totalBytes: Long = 0L,
    @SerialName("used_bytes") val usedBytes: Long = 0L,
    @SerialName("free_bytes") val freeBytes: Long = 0L,
    @SerialName("percent_used") val percentUsed: Float = 0f,
    @SerialName("formatted_total") val formattedTotal: String = "0 B",
    @SerialName("formatted_used") val formattedUsed: String = "0 B",
    @SerialName("formatted_free") val formattedFree: String = "0 B"
)

@Serializable
data class CpuStat(
    @SerialName("cores") val cores: Int = 1,
    @SerialName("percent_used") val percentUsed: Float = 0f,
    @SerialName("model_name") val modelName: String = "",
    @SerialName("load_avg") val loadAvg: List<Float> = emptyList()
)

@Serializable
data class ServerSystemStats(
    @SerialName("status") val status: String = "ok",
    @SerialName("version") val version: String = "",
    @SerialName("uptime_seconds") val uptimeSeconds: Long = 0L,
    @SerialName("os_name") val osName: String = "",
    @SerialName("ram") val ram: MemoryStat = MemoryStat(),
    @SerialName("disk") val disk: DiskStat = DiskStat(),
    @SerialName("cpu") val cpu: CpuStat = CpuStat()
)

