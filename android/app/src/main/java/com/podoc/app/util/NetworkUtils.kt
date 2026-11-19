package com.podoc.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkUtils {

    /**
     * Kiểm tra xem thiết bị có đang kết nối Wi-Fi (hoặc Ethernet) hay không.
     * TUYỆT ĐỐI KHÔNG CHẤP NHẬN mạng di động (3G, 4G, 5G) để tránh tốn dung lượng của người dùng.
     */
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false

        val hasWifiOrEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

        val hasCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

        return hasWifiOrEthernet && !hasCellular
    }

    /**
     * Lấy mô tả chi tiết trạng thái kết nối mạng hiện tại.
     */
    fun getNetworkStatusDescription(context: Context): NetworkStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkStatus.Offline("Không thể truy cập dịch vụ mạng")

        val activeNetwork = cm.activeNetwork
            ?: return NetworkStatus.Offline("Không có kết nối mạng (Ngoại tuyến)")

        val capabilities = cm.getNetworkCapabilities(activeNetwork)
            ?: return NetworkStatus.Offline("Không có kết nối mạng")

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                NetworkStatus.Wifi("Đang kết nối Wi-Fi (Sẵn sàng đồng bộ)")
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                NetworkStatus.Wifi("Đang kết nối Ethernet (Sẵn sàng đồng bộ)")
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                NetworkStatus.Cellular("Đang bật mạng di động 3G/4G/5G (Đã chặn đồng bộ để bảo vệ dung lượng)")
            }
            else -> {
                NetworkStatus.Offline("Mạng không xác định")
            }
        }
    }

    sealed class NetworkStatus(val message: String) {
        class Wifi(message: String) : NetworkStatus(message)
        class Cellular(message: String) : NetworkStatus(message)
        class Offline(message: String) : NetworkStatus(message)

        val isEligibleForSync: Boolean get() = this is Wifi
    }
}
