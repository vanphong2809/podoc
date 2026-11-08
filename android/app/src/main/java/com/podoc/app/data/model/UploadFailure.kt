package com.podoc.app.data.model

import android.net.Uri

data class UploadFailureItem(
    val fileName: String,
    val fileSizeMb: Double,
    val errorReason: String,
    val uri: Uri
)

data class UploadFailureDialogData(
    val successCount: Int,
    val totalCount: Int,
    val failures: List<UploadFailureItem>
)

