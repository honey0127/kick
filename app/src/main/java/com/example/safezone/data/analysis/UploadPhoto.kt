package com.example.safezone.data.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** 서버로 보낼 사진의 긴 변 최대 길이(px). 서버 모델은 이보다 작게 줄여서 보므로 판정에 쓰는 정보는 남는다. */
const val UPLOAD_MAX_SIDE = 1600

private const val JPEG_QUALITY = 90
private const val TAG = "UploadPhoto"

/**
 * 서버로 보내기 전에 사진을 줄인다.
 *
 * 카메라 원본은 수 MB 라서 그대로 보내면 느린 통신망에서 오래 걸리고 시간 초과가 나기 쉽다.
 * 긴 변이 [maxSide] 를 넘으면 그 길이에 맞춰 줄이고 JPEG 로 다시 저장한다.
 *
 * - 사진에 적힌 방향(EXIF)은 픽셀에 반영해 똑바로 세운다 (다시 저장하면 방향 정보가 없어지므로).
 * - 이미 작은 사진은 손대지 않고 그대로 돌려준다.
 * - 사진으로 읽을 수 없거나 줄이다가 실패하면 원본을 그대로 돌려준다
 *   (줄이기는 속도를 위한 것이라, 실패했다고 반납을 막지 않는다. 판단은 서버가 한다).
 */
fun shrinkForUpload(original: ByteArray, maxSide: Int = UPLOAD_MAX_SIDE): ByteArray =
    try {
        shrinkOrNull(original, maxSide) ?: original
    } catch (e: Exception) {
        Log.w(TAG, "사진을 줄이지 못해 원본을 보냅니다", e)
        original
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "메모리가 부족해 원본을 보냅니다", e)
        original
    }

/**
 * 원본을 처음 읽을 때 몇 분의 1 로 줄여 읽을지 (2의 거듭제곱).
 * 큰 사진을 통째로 메모리에 올리지 않기 위한 값으로, 읽은 결과의 긴 변이 [maxSide] 보다 작아지지 않는 가장 큰 값이다.
 */
internal fun uploadSampleSize(width: Int, height: Int, maxSide: Int): Int {
    val longSide = maxOf(width, height)
    var sample = 1
    while (longSide / (sample * 2) >= maxSide) sample *= 2
    return sample
}

/** 줄일 필요가 없거나 사진으로 읽을 수 없으면 null. */
private fun shrinkOrNull(original: ByteArray, maxSide: Int): ByteArray? {
    // 1) 픽셀을 읽지 않고 크기만 확인한다
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return null
    if (maxOf(width, height) <= maxSide) return null

    // 2) 필요한 크기에 가깝게 줄여서 읽는다
    val options = BitmapFactory.Options().apply { inSampleSize = uploadSampleSize(width, height, maxSide) }
    val decoded = BitmapFactory.decodeByteArray(original, 0, original.size, options) ?: return null

    // 3) 긴 변을 maxSide 에 맞추면서, 사진에 적힌 방향대로 돌린다
    val scale = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
    val matrix = Matrix().apply {
        if (scale < 1f) postScale(scale, scale)
        applyOrientation(this, readOrientation(original))
    }
    val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

    // 4) JPEG 로 다시 저장한다
    val out = ByteArrayOutputStream()
    val saved = result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
    if (result !== decoded) result.recycle()
    decoded.recycle()
    return if (saved) out.toByteArray() else null
}

/** 사진에 적힌 방향(EXIF). 읽지 못하면 '그대로'로 본다. */
private fun readOrientation(bytes: ByteArray): Int =
    try {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

private fun applyOrientation(matrix: Matrix, orientation: Int) {
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    }
}
