package com.example.safezone.data.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * 서버로 보내기 전 사진 줄이기. (Bitmap 을 쓰므로 Robolectric)
 * 색까지 확인하려면 실제로 그리고 JPEG 로 저장해야 해서 NATIVE 그래픽 모드를 쓴다.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UploadPhotoTest {

    /** 왼쪽 절반은 빨강, 오른쪽 절반은 파랑인 JPEG. 방향이 맞게 돌아갔는지 색으로 확인한다. */
    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = Color.RED
        canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), paint)
        paint.color = Color.BLUE
        canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        return out.toByteArray()
    }

    /** JPEG 맨 앞(SOI 바로 뒤)에 방향 정보만 든 EXIF 를 끼워 넣는다. */
    private fun withOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // 빅엔디언 TIFF 머리말, 첫 IFD 위치 8
            0x00, 0x01, // 항목 1개
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, orientation.toByte(), 0x00, 0x00, // 방향(0x0112)
            0x00, 0x00, 0x00, 0x00, // 다음 IFD 없음
        )
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun decode(bytes: ByteArray): Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    private fun assertRed(bitmap: Bitmap, x: Int, y: Int) {
        val color = bitmap.getPixel(x, y)
        assertTrue("($x, $y) 가 빨강이 아님: #%08X".format(color), Color.red(color) > 180 && Color.blue(color) < 80)
    }

    private fun assertBlue(bitmap: Bitmap, x: Int, y: Int) {
        val color = bitmap.getPixel(x, y)
        assertTrue("($x, $y) 가 파랑이 아님: #%08X".format(color), Color.blue(color) > 180 && Color.red(color) < 80)
    }

    @Test
    fun largePhoto_isShrunkToMaxSide() {
        val original = jpeg(4000, 3000)

        val result = decode(shrinkForUpload(original))

        assertEquals(1600, result.width)
        assertEquals(1200, result.height)
        // 내용은 그대로: 왼쪽 빨강, 오른쪽 파랑
        assertRed(result, 400, 600)
        assertBlue(result, 1200, 600)
    }

    @Test
    fun largePhoto_getsSmallerInBytes() {
        val original = jpeg(4000, 3000)

        assertTrue(shrinkForUpload(original).size < original.size)
    }

    @Test
    fun portraitPhoto_keepsItsShape() {
        val result = decode(shrinkForUpload(jpeg(3000, 4000)))

        assertEquals(1200, result.width)
        assertEquals(1600, result.height)
    }

    @Test
    fun photoWithinLimit_isSentAsIs() {
        val original = jpeg(1600, 1200)

        assertSame(original, shrinkForUpload(original))
    }

    @Test
    fun rotatedPhoto_isTurnedUpright() {
        // 방향 6 = "시계 방향으로 90도 돌려서 보라". 가로 4000x3000 으로 저장됐지만 실제로는 세로 사진이다.
        val original = withOrientation(jpeg(4000, 3000), 6)

        val result = decode(shrinkForUpload(original))

        assertEquals(1200, result.width)
        assertEquals(1600, result.height)
        // 왼쪽(빨강)이 위로, 오른쪽(파랑)이 아래로 간다
        assertRed(result, 600, 400)
        assertBlue(result, 600, 1200)
    }

    @Test
    fun notAPhoto_isSentAsIs() {
        val original = "사진이 아닌 데이터".toByteArray()

        assertSame(original, shrinkForUpload(original))
    }

    @Test
    fun sampleSize_neverGoesBelowMaxSide() {
        assertEquals(1, uploadSampleSize(1600, 1200, 1600))
        assertEquals(1, uploadSampleSize(3000, 2000, 1600)) // 2로 나누면 1500 < 1600
        assertEquals(2, uploadSampleSize(4000, 3000, 1600))
        assertEquals(4, uploadSampleSize(8000, 6000, 1600))
        assertEquals(2, uploadSampleSize(3000, 4000, 1600)) // 세로 사진도 긴 변 기준
    }
}
