package com.example.safezone.data.location

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress

/** 위치 확인·반납 완료 요청과 응답 변환을 가짜 서버로 확인한다. (org.json 때문에 Robolectric) */
@RunWith(AndroidJUnit4::class)
class RemoteReturnApiTest {

    private lateinit var server: HttpServer
    private var responseCode = 200
    private var responseBody = ""
    private var receivedPath = ""
    private var receivedJson = JSONObject()

    /** 서버가 요청을 받은 순간(응답을 보내기 전)에 실행할 일 */
    private var onRequest: () -> Unit = {}

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            receivedPath = ex.requestURI.path
            receivedJson = JSONObject(String(ex.requestBody.readBytes()))
            onRequest()
            val bytes = responseBody.toByteArray()
            ex.sendResponseHeaders(responseCode, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    private fun api(session: ReturnSession) = RemoteReturnApi(
        serverUrl = "http://127.0.0.1:${server.address.port}/",
        session = session,
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun allowedLocation_storesToken_andSendsCoordinates() = runTest {
        responseBody = """{"ok":true,"code":"PARKING_ZONE","message":"지정 주차구역이에요: 북문 주차구역","zone":{"name":"북문 주차구역","type":"parking"},"token":"T1"}"""
        val session = ReturnSession()
        val r = api(session).checkLocation(DeviceLocation(35.89, 128.61, 5f))

        assertTrue(r.ok)
        assertEquals("북문 주차구역", r.zone)
        assertEquals("T1", session.token)
        assertEquals("/api/return/location", receivedPath)
        assertEquals(35.89, receivedJson.getDouble("lat"), 1e-9)
        assertEquals(128.61, receivedJson.getDouble("lon"), 1e-9)
        assertEquals(5.0, receivedJson.getDouble("accuracy"), 1e-9)
    }

    @Test
    fun deniedLocation_showsServerMessage_andClearsOldToken() = runTest {
        responseBody = """{"ok":false,"code":"NO_PARKING","message":"주차 금지 구역이에요.","zone":null}"""
        val session = ReturnSession().apply { token = "OLD" }
        val r = api(session).checkLocation(DeviceLocation(35.89, 128.61, null))

        assertFalse(r.ok)
        assertEquals("주차 금지 구역이에요.", r.message)
        assertNull(r.zone)
        assertNull(session.token)
        assertFalse(receivedJson.has("accuracy"))
    }

    @Test
    fun complete_sendsToken_andEndsSession() = runTest {
        responseBody = """{"ok":true,"vehicle_id":"SAFEZONE-APP","zone":"북문","return_id":"RT1"}"""
        val session = ReturnSession().apply { token = "T1" }
        api(session).complete()

        assertEquals("/api/return/complete", receivedPath)
        assertEquals("T1", receivedJson.getString("token"))
        assertNull(session.token)
    }

    @Test
    fun complete_withExpiredToken_throwsSessionExpired_andForgetsToken() = runTest {
        responseCode = 410
        responseBody = """{"detail":"반납 세션이 만료됐어요. 위치 확인부터 다시 해주세요."}"""
        val session = ReturnSession().apply { token = "T1" }
        try {
            api(session).complete()
            fail("예외가 나야 한다")
        } catch (e: ReturnSessionExpiredException) {
            assertNull(session.token)
        }
    }

    @Test
    fun complete_keepsTokenIssuedWhileWaiting() = runTest {
        // 반납 완료 응답을 기다리는 사이 새 위치 확인으로 토큰이 바뀌었다면, 새 토큰까지 지우면 안 된다.
        responseBody = """{"ok":true,"vehicle_id":"SAFEZONE-APP","zone":null,"return_id":"RT1"}"""
        val session = ReturnSession().apply { token = "OLD" }
        onRequest = { session.token = "NEW" }
        api(session).complete()

        assertEquals("OLD", receivedJson.getString("token"))
        assertEquals("NEW", session.token)
    }

    @Test
    fun complete_withoutToken_sendsNothing() = runTest {
        api(ReturnSession()).complete()
        assertEquals("", receivedPath)
    }
}
