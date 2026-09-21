package com.camscanner.pro.core.barcode

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class BarcodeResult(
    val displayValue: String,
    val formatName: String,
    val valueType: Int,
    val url: String? = null
)

object BarcodeScannerManager {

    private val scanner by lazy {
        BarcodeScanning.getClient()
    }

    suspend fun scanBarcode(bitmap: Bitmap): Result<BarcodeResult?> {
        return suspendCancellableCoroutine { continuation ->
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        if (barcodes.isNotEmpty()) {
                            val b = barcodes.first()
                            val format = getFormatName(b.format)
                            val url = if (b.valueType == Barcode.TYPE_URL) b.url?.url else null
                            val res = BarcodeResult(
                                displayValue = b.displayValue ?: b.rawValue ?: "Unknown",
                                formatName = format,
                                valueType = b.valueType,
                                url = url
                            )
                            continuation.resume(Result.success(res))
                        } else {
                            continuation.resume(Result.success(null))
                        }
                    }
                    .addOnFailureListener { e ->
                        continuation.resume(Result.failure(e))
                    }
            } catch (e: Throwable) {
                continuation.resume(Result.failure(e))
            }
        }
    }

    private fun getFormatName(format: Int): String {
        return when (format) {
            Barcode.FORMAT_QR_CODE -> "QR Code"
            Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
            Barcode.FORMAT_AZTEC -> "Aztec"
            Barcode.FORMAT_PDF417 -> "PDF417"
            Barcode.FORMAT_CODE_128 -> "Code 128"
            Barcode.FORMAT_CODE_39 -> "Code 39"
            Barcode.FORMAT_EAN_13 -> "EAN-13"
            Barcode.FORMAT_EAN_8 -> "EAN-8"
            Barcode.FORMAT_UPC_A -> "UPC-A"
            Barcode.FORMAT_UPC_E -> "UPC-E"
            else -> "Barcode"
        }
    }
}
