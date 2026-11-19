package com.podoc.app.ui.screens.tools

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podoc.app.di.AppContainer
import com.podoc.app.service.sync.LocalMediaScanner
import com.podoc.app.service.sync.MediaSyncEngine
import com.podoc.app.service.sync.MediaSyncResult
import com.podoc.app.service.sync.MediaSyncScheduler
import com.podoc.app.service.sync.TodayMediaItem
import com.podoc.app.util.NetworkUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MediaSyncUiState(
    val isAutoSyncEnabled: Boolean = true,
    val isWifi: Boolean = false,
    val networkDescription: String = "Đang kiểm tra...",
    val hasPermissions: Boolean = false,
    val todayItems: List<TodayMediaItem> = emptyList(),
    val syncedKeys: Set<String> = emptySet(),
    val isScanning: Boolean = false,
    val isSyncing: Boolean = false,
    val syncProgress: Float = 0f,
    val syncProgressText: String = "",
    val currentFileName: String = "",
    val lastSyncTimeText: String = "Chưa có dữ liệu",
    val lastSyncSummary: String = "",
    val lastSyncStatus: String = "",
    val alertMessage: String? = null,
    val successToast: String? = null
)

class MediaSyncViewModel(
    private val context: Context,
    private val container: AppContainer
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediaSyncUiState())
    val uiState: StateFlow<MediaSyncUiState> = _uiState.asStateFlow()

    init {
        loadSettingsAndStatus()
    }

    fun loadSettingsAndStatus() {
        viewModelScope.launch {
            val autoEnabled = container.dataStoreManager.mediaAutoSyncEnabledFlow.first()
            val lastTime = container.dataStoreManager.mediaSyncLastTimeFlow.first()
            val lastSummary = container.dataStoreManager.mediaSyncLastSummaryFlow.first()
            val lastStatus = container.dataStoreManager.mediaSyncLastStatusFlow.first()
            val syncedKeys = container.dataStoreManager.mediaSyncSyncedKeysFlow.first()

            val lastTimeFormatted = if (lastTime > 0) {
                SimpleDateFormat("HH:mm - dd/MM/yyyy", Locale.getDefault()).format(Date(lastTime))
            } else {
                "Chưa đồng bộ lần nào"
            }

            val hasPerms = LocalMediaScanner.hasMediaPermissions(context)
            val isWifi = NetworkUtils.isWifiConnected(context)
            val netDesc = NetworkUtils.getNetworkStatusDescription(context).message

            _uiState.value = _uiState.value.copy(
                isAutoSyncEnabled = autoEnabled,
                lastSyncTimeText = lastTimeFormatted,
                lastSyncSummary = lastSummary,
                lastSyncStatus = lastStatus,
                syncedKeys = syncedKeys,
                hasPermissions = hasPerms,
                isWifi = isWifi,
                networkDescription = netDesc
            )

            if (hasPerms) {
                scanTodayMedia()
            }
        }
    }

    fun scanTodayMedia() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true)
            val hasPerms = LocalMediaScanner.hasMediaPermissions(context)
            val items = if (hasPerms) LocalMediaScanner.queryTodayMedia(context) else emptyList()
            val syncedKeys = container.dataStoreManager.mediaSyncSyncedKeysFlow.first()

            _uiState.value = _uiState.value.copy(
                isScanning = false,
                hasPermissions = hasPerms,
                todayItems = items,
                syncedKeys = syncedKeys
            )
        }
    }

    fun toggleAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            container.dataStoreManager.setMediaAutoSyncEnabled(enabled)
            _uiState.value = _uiState.value.copy(isAutoSyncEnabled = enabled)
            if (enabled) {
                MediaSyncScheduler.scheduleDailySync(context)
            } else {
                MediaSyncScheduler.cancelDailySync(context)
            }
        }
    }

    /**
     * Nút "Đồng bộ ngay" (Sync Now) để người dùng chủ động kiểm tra và test tức thì.
     * TUYỆT ĐỐI CHẶN nếu đang dùng mạng 3G/4G/5G hoặc ngoại tuyến.
     */
    fun syncNow() {
        if (_uiState.value.isSyncing) return

        // 1. Kiểm tra quyền
        val hasPerms = LocalMediaScanner.hasMediaPermissions(context)
        if (!hasPerms) {
            _uiState.value = _uiState.value.copy(
                alertMessage = "Vui lòng cấp quyền truy cập Ảnh và Video để PoDoc có thể đồng bộ các tệp hôm nay."
            )
            return
        }

        // 2. Kiểm tra điều kiện Wi-Fi: Chặn mạng di động (3G/4G/5G)
        val isWifi = NetworkUtils.isWifiConnected(context)
        if (!isWifi) {
            val netDesc = NetworkUtils.getNetworkStatusDescription(context).message
            _uiState.value = _uiState.value.copy(
                alertMessage = "⚠️ Không thể đồng bộ: $netDesc.\n\nTheo quy định, tính năng này chỉ đồng bộ khi thiết bị kết nối Wi-Fi để tránh làm tiêu hao gói cước 3G/4G/5G của bạn."
            )
            return
        }

        // 3. Tiến hành đồng bộ
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isSyncing = true,
                syncProgress = 0f,
                syncProgressText = "Đang chuẩn bị danh sách...",
                currentFileName = ""
            )

            val result = MediaSyncEngine.performSync(context, container) { current, total, name ->
                val progress = if (total > 0) current.toFloat() / total.toFloat() else 0f
                _uiState.value = _uiState.value.copy(
                    syncProgress = progress,
                    syncProgressText = "Đang tải $current/$total",
                    currentFileName = name
                )
            }

            _uiState.value = _uiState.value.copy(isSyncing = false)

            when (result) {
                is MediaSyncResult.Success -> {
                    val msg = "Đã đồng bộ thành công ${result.syncedImages} ảnh (vào danh mục Hình ảnh) và ${result.syncedVideos} video (vào danh mục Video)."
                    _uiState.value = _uiState.value.copy(
                        successToast = msg
                    )
                    loadSettingsAndStatus()
                }
                is MediaSyncResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        alertMessage = result.message
                    )
                    loadSettingsAndStatus()
                }
            }
        }
    }

    fun dismissAlert() {
        _uiState.value = _uiState.value.copy(alertMessage = null)
    }

    fun dismissSuccessToast() {
        _uiState.value = _uiState.value.copy(successToast = null)
    }
}
