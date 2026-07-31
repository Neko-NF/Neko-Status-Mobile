package com.nekonf.nekostatus.feature.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_SOURCE_BYTES = 10L * 1024L * 1024L
private const val MAX_DECODE_DIMENSION = 2_048
private const val AVATAR_DIMENSION = 512
private const val JPEG_QUALITY = 85
private const val MAX_JPEG_BYTES = 750_000

internal suspend fun encodeAvatarDataUri(
    context: Context,
    uri: Uri,
): Result<String> =
    withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            require(resolver.getType(uri)?.startsWith("image/") == true) { "Unsupported avatar format" }
            resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                require(descriptor.length < 0L || descriptor.length <= MAX_SOURCE_BYTES) { "Avatar source is too large" }
            }
            val bitmap =
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > MAX_DECODE_DIMENSION) {
                        val scale = MAX_DECODE_DIMENSION.toFloat() / longest
                        decoder.setTargetSize(
                            (info.size.width * scale).roundToInt().coerceAtLeast(1),
                            (info.size.height * scale).roundToInt().coerceAtLeast(1),
                        )
                    }
                }
            bitmap.toAvatarDataUri()
        }
    }

private fun Bitmap.toAvatarDataUri(): String {
    val cropSize = min(width, height)
    require(cropSize > 0) { "Avatar image is empty" }
    val cropped =
        if (width == height) {
            this
        } else {
            Bitmap.createBitmap(
                this,
                (width - cropSize) / 2,
                (height - cropSize) / 2,
                cropSize,
                cropSize,
            )
        }
    val scaled =
        if (cropped.width == AVATAR_DIMENSION && cropped.height == AVATAR_DIMENSION) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, AVATAR_DIMENSION, AVATAR_DIMENSION, true)
        }
    return try {
        val bytes =
            ByteArrayOutputStream().use { output ->
                require(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) { "Could not encode avatar" }
                output.toByteArray()
            }
        require(bytes.size <= MAX_JPEG_BYTES) { "Encoded avatar is too large" }
        "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    } finally {
        if (scaled !== cropped) scaled.recycle()
        if (cropped !== this) cropped.recycle()
        recycle()
    }
}
