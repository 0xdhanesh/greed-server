package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.burpsuite.TaskExecutionEngine
import burp.api.montoya.collaborator.*
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.core.ByteArray
import burp.api.montoya.core.Annotations
import burp.api.montoya.core.HighlightColor
import burp.api.montoya.http.Http
import burp.api.montoya.http.HttpMode
import burp.api.montoya.http.HttpProtocol
import burp.api.montoya.http.message.HttpHeader
import burp.api.montoya.http.message.HttpRequestResponse as MontoyaHttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.intruder.Intruder
import burp.api.montoya.organizer.Organizer
import burp.api.montoya.organizer.OrganizerItem
import burp.api.montoya.organizer.OrganizerItemFilter
import burp.api.montoya.organizer.OrganizerItemStatus
import burp.api.montoya.repeater.Repeater
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import burp.api.montoya.proxy.Proxy
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import burp.api.montoya.utilities.Base64Utils
import burp.api.montoya.utilities.RandomUtils
import burp.api.montoya.utilities.URLUtils
import burp.api.montoya.utilities.Utilities
import io.mockk.*
import io.mockk.coEvery
import java.net.InetAddress
import java.time.ZonedDateTime
import java.util.Optional
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.portswigger.mcp.KtorServerManager
import net.portswigger.mcp.ServerState
import net.portswigger.mcp.TestSseMcpClient
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.schema.HttpRequestResponse
import net.portswigger.mcp.schema.toSerializableForm
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessApprovalHandler
import net.portswigger.mcp.security.DataAccessType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import javax.swing.JTextArea

class ToolsKtTest {
    
    private val client = TestSseMcpClient()
    private val api = mockk<MontoyaApi>(relaxed = true)
    private val serverManager = KtorServerManager(api)
    private val testPort = findAvailablePort()
    private var serverStarted = false
    private val config: McpConfig
    private val mockHeaders = mutableListOf<HttpHeader>()
    private val capturedRequest = slot<HttpRequest>()
    private var dataAccessApprovalRequired = false

    init {
        val persistedObject = mockk<PersistedObject>().apply {
            every { getBoolean("enabled") } returns true
            every { getBoolean("configEditingTooling") } returns true
            every { getBoolean("requireHttpRequestApproval") } returns false
            every { getBoolean("requireDataAccessApproval") } answers { dataAccessApprovalRequired }
            every { getBoolean("_alwaysAllowHttpHistory") } returns false
            every { getBoolean("_alwaysAllowWebSocketHistory") } returns false
            every { getBoolean("_alwaysAllowOrganizer") } returns false
            every { getString("host") } returns "127.0.0.1"
            every { getString("_autoApproveTargets") } returns ""
            every { getInteger("port") } returns testPort
            every { setBoolean(any(), any()) } returns Unit
            every { setString(any(), any()) } returns Unit
            every { setInteger(any(), any()) } returns Unit
        }
        val mockLogging = mockk<Logging>().apply {
            every { logToError(any<String>()) } returns Unit
            every { logToOutput(any<String>()) } returns Unit
        }

        config = McpConfig(persistedObject, mockLogging)
        
        mockkStatic(HttpHeader::class)
        mockkStatic(burp.api.montoya.http.HttpService::class)
        mockkStatic(HttpRequest::class)
    }

    private fun CallToolResult?.expectTextContent(
        expected: String? = null,
    ): String {
        assertNotNull(this, "Tool result cannot be null")
        val result = this!!

        val content = result.content
        assertNotNull(content, "Tool result content cannot be null")

        val nonNullContent = content
        assertEquals(1, nonNullContent.size, "Expected exactly one content element")

        val textContent = nonNullContent.firstOrNull() as? TextContent
        assertNotNull(textContent, "Expected content to be TextContent")

        val text = textContent!!.text
        assertNotNull(text, "Text content cannot be null")

        if (expected != null) {
            assertEquals(expected, text, "Text content doesn't match expected value")
        }

        return text!!
    }

    private fun setupHttpHeaderMocks() {
        every { HttpHeader.httpHeader(any<String>(), any<String>()) } answers {
            val name = firstArg<String>()
            val value = secondArg<String>()
            mockk<HttpHeader>().also {
                every { it.name() } returns name
                every { it.value() } returns value
                mockHeaders.add(it)
            }
        }

        every { burp.api.montoya.http.HttpService.httpService(any(), any(), any()) } answers {
            val host = firstArg<String>()
            val port = secondArg<Int>()
            val secure = thirdArg<Boolean>()
            mockk<burp.api.montoya.http.HttpService>().also {
                every { it.host() } returns host
                every { it.port() } returns port
                every { it.secure() } returns secure
            }
        }
    }
    
    @BeforeEach
    fun setup() {
        setupHttpHeaderMocks()

        serverManager.start(config) { state ->
            if (state is ServerState.Running) serverStarted = true
        }

        runBlocking {
            var attempts = 0
            while (!serverStarted && attempts < 30) {
                delay(100)
                attempts++
            }
            if (!serverStarted) throw IllegalStateException("Server failed to start after timeout")

            client.connectToServer("http://127.0.0.1:${testPort}")
            assertNotNull(client.ping(), "Ping should return a result")
        }
    }

    private fun findAvailablePort() = ServerSocket(0).use { it.localPort }

    @AfterEach
    fun tearDown() {
        runBlocking { if (client.isConnected()) client.close() }
        serverManager.stop {}
    }

    @Nested
    inner class HttpToolsTests {
        @Test
        fun `http1 line endings should be normalized`() {
            val httpService = mockk<Http>()
            val httpResponse = mockk<burp.api.montoya.http.message.HttpRequestResponse>()
            val contentSlot = slot<String>()

            every { HttpRequest.httpRequest(any(), capture(contentSlot)) } answers {
                val content = secondArg<String>()
                mockk<HttpRequest>().also {
                    every { it.toString() } returns content
                }
            }
            every { api.http() } returns httpService
            every { httpResponse.toString() } returns "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nResponse body"
            every { httpService.sendRequest(capture(capturedRequest)) } returns httpResponse

            runBlocking {
                val result = client.callTool(
                    "send_http1_request", mapOf(
                        "content" to "GET /foo HTTP/1.1\nHost: example.com\n\n",
                        "targetHostname" to "example.com",
                        "targetPort" to 80,
                        "usesHttps" to false
                    )
                )

                delay(100)
                val text = result.expectTextContent()
                assertFalse(text.contains("Error"), 
                    "Expected success response but got error: $text")
            }

            verify(exactly = 1) { httpService.sendRequest(any<HttpRequest>()) }
            assertEquals("GET /foo HTTP/1.1\r\nHost: example.com\r\n\r\n", capturedRequest.captured.toString(), "Request body should match")
        }

        @Test
        fun `http1 request should handle no response`() {
            val httpService = mockk<Http>()
            val contentSlot = slot<String>()

            every { HttpRequest.httpRequest(any(), capture(contentSlot)) } answers {
                val content = secondArg<String>()
                mockk<HttpRequest>().also {
                    every { it.toString() } returns content
                }
            }
            every { api.http() } returns httpService
            every { httpService.sendRequest(any()) } returns null

            runBlocking {
                val result = client.callTool(
                    "send_http1_request", mapOf(
                        "content" to "GET /foo HTTP/1.1\r\nHost: example.com\r\n\r\n",
                        "targetHostname" to "example.com",
                        "targetPort" to 80,
                        "usesHttps" to false
                    )
                )

                delay(100)
                result.expectTextContent("<no response>")
            }
        }

        @Test
        fun `http2 request should be formatted properly`() {
            val httpService = mockk<Http>()
            val httpResponse = mockk<burp.api.montoya.http.message.HttpRequestResponse>()
            val httpRequest = mockk<HttpRequest>()
            val requestSlot = slot<HttpRequest>()
            val headersSlot = slot<List<HttpHeader>>()
            val bodySlot = slot<String>()

            every { HttpRequest.http2Request(any(), capture(headersSlot), capture(bodySlot)) } returns httpRequest
            every { httpResponse.toString() } returns "HTTP/2 200 OK\r\nContent-Type: text/plain\r\n\r\nResponse body"
            every { api.http() } returns httpService
            every { httpService.sendRequest(capture(requestSlot), HttpMode.HTTP_2) } returns httpResponse

            val pseudoHeaders = mapOf(
                "authority" to "example.com", "scheme" to "https", "method" to "GET", ":path" to "/test"
            )
            val headers = mapOf(
                "User-Agent" to "Test Agent", "Accept" to "*/*"
            )
            val requestBody = "Test body"

            runBlocking {
                val result = client.callTool(
                    "send_http2_request", mapOf(
                        "pseudoHeaders" to Json.encodeToJsonElement(pseudoHeaders),
                        "headers" to Json.encodeToJsonElement(headers),
                        "requestBody" to requestBody,
                        "targetHostname" to "example.com",
                        "targetPort" to 443,
                        "usesHttps" to true
                    )
                )

                delay(100)
                val text = result.expectTextContent()
                assertFalse(text.contains("Error"), 
                    "Expected success response but got error: $text")
            }

            verify(exactly = 1) { HttpRequest.http2Request(any(), any(), any<String>()) }
            
            assertEquals("Test body", bodySlot.captured, "Request body should match")
            
            val pseudoHeaderList = headersSlot.captured.filter { it.name().startsWith(":") }
            val normalHeaderList = headersSlot.captured.filter { !it.name().startsWith(":") }
            
            assertTrue(pseudoHeaderList.any { it.name() == ":scheme" && it.value() == "https" })
            assertTrue(pseudoHeaderList.any { it.name() == ":method" && it.value() == "GET" })
            assertTrue(pseudoHeaderList.any { it.name() == ":path" && it.value() == "/test" })
            assertTrue(pseudoHeaderList.any { it.name() == ":authority" && it.value() == "example.com" })
            
            assertTrue(normalHeaderList.any { it.name() == "user-agent" && it.value() == "Test Agent" })
            assertTrue(normalHeaderList.any { it.name() == "accept" && it.value() == "*/*" })
        }
        
        @Test
        fun `http2 request should handle null response`() {
            val httpService = mockk<Http>()
            val httpRequest = mockk<HttpRequest>()

            every { HttpRequest.http2Request(any(), any(), any<String>()) } returns httpRequest
            every { api.http() } returns httpService
            every { httpService.sendRequest(any(), HttpMode.HTTP_2) } returns null

            val pseudoHeaders = mapOf("method" to "GET", "path" to "/test")
            val headers = mapOf("User-Agent" to "Test Agent")

            runBlocking {
                val result = client.callTool(
                    "send_http2_request", mapOf(
                        "pseudoHeaders" to Json.encodeToJsonElement(pseudoHeaders),
                        "headers" to Json.encodeToJsonElement(headers),
                        "requestBody" to "",
                        "targetHostname" to "example.com",
                        "targetPort" to 443,
                        "usesHttps" to true
                    )
                )

                delay(100)
                result.expectTextContent("<no response>")
            }
        }
        
        @Test
        fun `http2 pseudo headers should be ordered correctly`() {
            val httpService = mockk<Http>()
            val httpResponse = mockk<burp.api.montoya.http.message.HttpRequestResponse>()
            val httpRequest = mockk<HttpRequest>()
            val headersSlot = slot<List<HttpHeader>>()

            every { HttpRequest.http2Request(any(), capture(headersSlot), any<String>()) } returns httpRequest
            every { httpResponse.toString() } returns "HTTP/2 200 OK"
            every { api.http() } returns httpService
            every { httpService.sendRequest(any(), HttpMode.HTTP_2) } returns httpResponse

            val pseudoHeaders = mapOf(
                "path" to "/test",
                ":authority" to "example.com", 
                "method" to "GET",
                "scheme" to "https"
            )

            runBlocking {
                val result = client.callTool(
                    "send_http2_request", mapOf(
                        "pseudoHeaders" to Json.encodeToJsonElement(pseudoHeaders),
                        "headers" to Json.encodeToJsonElement(emptyMap<String, String>()),
                        "requestBody" to "",
                        "targetHostname" to "example.com",
                        "targetPort" to 443,
                        "usesHttps" to true
                    )
                )
                
                delay(100)
                assertNotNull(result)
            }
            
            val pseudoHeaderNames = headersSlot.captured
                .filter { it.name().startsWith(":") }
                .map { it.name() }
            
            val expectedOrder = listOf(":scheme", ":method", ":path", ":authority")
            for (i in 0 until minOf(expectedOrder.size, pseudoHeaderNames.size)) {
                assertEquals(expectedOrder[i], pseudoHeaderNames[i],
                    "Pseudo headers should follow the order: scheme, method, path, authority")
            }
        }

        @Test
        fun `create repeater tab http2 should build http2 request`() {
            val repeater = mockk<burp.api.montoya.repeater.Repeater>(relaxed = true)
            val httpRequest = mockk<HttpRequest>()
            val headersSlot = slot<List<HttpHeader>>()
            val bodySlot = slot<String>()

            every { HttpRequest.http2Request(any(), capture(headersSlot), capture(bodySlot)) } returns httpRequest
            every { api.repeater() } returns repeater

            val pseudoHeaders = mapOf(
                "method" to "POST", "path" to "/api/x", "authority" to "example.com", "scheme" to "https"
            )
            val headers = mapOf("Content-Type" to "application/json")
            val requestBody = "{\"k\":\"v\"}"

            runBlocking {
                val result = client.callTool(
                    "create_repeater_tab_http2", mapOf(
                        "tabName" to "h2-tab",
                        "pseudoHeaders" to Json.encodeToJsonElement(pseudoHeaders),
                        "headers" to Json.encodeToJsonElement(headers),
                        "requestBody" to requestBody,
                        "targetHostname" to "example.com",
                        "targetPort" to 443,
                        "usesHttps" to true
                    )
                )

                delay(100)
                assertNotNull(result)
            }

            verify(exactly = 1) { repeater.sendToRepeater(httpRequest, "h2-tab") }
            assertEquals("{\"k\":\"v\"}", bodySlot.captured, "Request body should be passed through unchanged")

            val pseudoHeaderNames = headersSlot.captured.filter { it.name().startsWith(":") }.map { it.name() }
            assertEquals(listOf(":scheme", ":method", ":path", ":authority"), pseudoHeaderNames)
            assertTrue(headersSlot.captured.any { it.name() == "content-type" && it.value() == "application/json" })
        }
    }

    @Nested
    inner class UtilityToolsTests {
        @Test
        fun `url encode should work properly`() {
            val urlUtils = mockk<URLUtils>()
            val utilities = mockk<Utilities>()
            
            every { api.utilities() } returns utilities
            every { utilities.urlUtils() } returns urlUtils
            every { urlUtils.encode(any<String>()) } returns "test+string+with+spaces"
            
            runBlocking {
                val result = client.callTool(
                    "url_encode", mapOf(
                        "content" to "test string with spaces"
                    )
                )
                
                delay(100)
                result.expectTextContent("test+string+with+spaces")
            }
            
            verify(exactly = 1) { urlUtils.encode(any<String>()) }
        }
        
        @Test
        fun `url decode should work properly`() {
            val urlUtils = mockk<URLUtils>()
            val utilities = mockk<Utilities>()
            
            every { api.utilities() } returns utilities
            every { utilities.urlUtils() } returns urlUtils
            every { urlUtils.decode(any<String>()) } returns "test string with spaces"
            
            runBlocking {
                val result = client.callTool(
                    "url_decode", mapOf(
                        "content" to "test+string+with+spaces"
                    )
                )
                
                delay(100)
                result.expectTextContent("test string with spaces")
            }
            
            verify(exactly = 1) { urlUtils.decode(any<String>()) }
        }
        
        @Test
        fun `base64 encode should work properly`() {
            val base64Utils = mockk<Base64Utils>()
            val utilities = mockk<Utilities>()
            
            every { api.utilities() } returns utilities
            every { utilities.base64Utils() } returns base64Utils
            every { base64Utils.encodeToString(any<String>()) } returns "dGVzdCBzdHJpbmc="
            
            runBlocking {
                val result = client.callTool(
                    "base64_encode", mapOf(
                        "content" to "test string"
                    )
                )
                
                delay(100)
                result.expectTextContent("dGVzdCBzdHJpbmc=")
            }
            
            verify(exactly = 1) { base64Utils.encodeToString(any<String>()) }
        }
        
        @Test
        fun `base64 decode should work properly`() {
            val base64Utils = mockk<Base64Utils>()
            val utilities = mockk<Utilities>()
            val burpByteArray = mockk<ByteArray>()
            
            every { api.utilities() } returns utilities
            every { utilities.base64Utils() } returns base64Utils
            every { base64Utils.decode(any<String>()) } returns burpByteArray
            every { burpByteArray.toString() } returns "test string"
            
            runBlocking {
                val result = client.callTool(
                    "base64_decode", mapOf(
                        "content" to "dGVzdCBzdHJpbmc="
                    )
                )
                
                delay(100)
                result.expectTextContent("test string")
            }
            
            verify(exactly = 1) { base64Utils.decode(any<String>()) }
        }
        
        @Test
        fun `generate random string should work properly`() {
            val randomUtils = mockk<RandomUtils>()
            val utilities = mockk<Utilities>()
            
            every { api.utilities() } returns utilities
            every { utilities.randomUtils() } returns randomUtils
            every { randomUtils.randomString(any<Int>(), any<String>()) } returns "1a2b3c1a2b"
            
            runBlocking {
                val result = client.callTool(
                    "generate_random_string", mapOf(
                        "length" to 10,
                        "characterSet" to "abc123"
                    )
                )
                
                delay(100)
                result.expectTextContent("1a2b3c1a2b")
            }
            
            verify(exactly = 1) { randomUtils.randomString(any<Int>(), any<String>()) }
        }
    }
    
    @Nested
    inner class ConfigurationToolsTests {
        @Test
        fun `set task execution engine state should work properly`() {
            val taskExecutionEngine = mockk<TaskExecutionEngine>()
            val burpSuite = mockk<burp.api.montoya.burpsuite.BurpSuite>()
            
            every { api.burpSuite() } returns burpSuite
            every { burpSuite.taskExecutionEngine() } returns taskExecutionEngine
            every { taskExecutionEngine.state = any() } just runs
            
            runBlocking {
                val result = client.callTool(
                    "set_task_execution_engine_state", mapOf(
                        "running" to true
                    )
                )
                
                delay(100)
                result.expectTextContent("Task execution engine is now running")
            }
            
            verify(exactly = 1) { taskExecutionEngine.state = TaskExecutionEngine.TaskExecutionEngineState.RUNNING }
            
            clearMocks(taskExecutionEngine, answers = false)
            
            runBlocking {
                val result = client.callTool(
                    "set_task_execution_engine_state", mapOf(
                        "running" to false
                    )
                )
                
                delay(100)
                result.expectTextContent("Task execution engine is now paused")
            }
            
            verify(exactly = 1) { taskExecutionEngine.state = TaskExecutionEngine.TaskExecutionEngineState.PAUSED }
        }
        
        @Test
        fun `set proxy intercept state should work properly`() {
            val proxy = mockk<Proxy>()
            
            every { api.proxy() } returns proxy
            every { proxy.enableIntercept() } just runs
            every { proxy.disableIntercept() } just runs
            
            runBlocking {
                val result = client.callTool(
                    "set_proxy_intercept_state", mapOf(
                        "intercepting" to true
                    )
                )
                
                delay(100)
                result.expectTextContent("Intercept has been enabled")
            }
            
            verify(exactly = 1) { proxy.enableIntercept() }
            
            clearMocks(proxy, answers = false)
            
            runBlocking {
                val result = client.callTool(
                    "set_proxy_intercept_state", mapOf(
                        "intercepting" to false
                    )
                )
                
                delay(100)
                result.expectTextContent("Intercept has been disabled")
            }
            
            verify(exactly = 1) { proxy.disableIntercept() }
        }
        
        @Test
        fun `config editing tools should respect config settings`() {
            val burpSuite = mockk<burp.api.montoya.burpsuite.BurpSuite>()
            
            every { api.burpSuite() } returns burpSuite
            every { burpSuite.importProjectOptionsFromJson(any()) } just runs
            every { api.logging().logToOutput(any()) } just runs
            
            runBlocking {
                val result = client.callTool(
                    "set_project_options", mapOf(
                        "json" to "{\"test\": true}"
                    )
                )
                
                delay(100)
                result.expectTextContent("Project configuration has been applied")
            }
            
            verify(exactly = 1) { burpSuite.importProjectOptionsFromJson(any()) }
            
            clearMocks(burpSuite, answers = false)
            
            every { config.configEditingTooling } returns false
            
            runBlocking {
                
                val result = client.callTool(
                    "set_project_options", mapOf(
                        "json" to "{\"test\": true}"
                    )
                )
                
                delay(100)
                result.expectTextContent("User has disabled configuration editing. They can enable it in the MCP tab in Burp by selecting 'Enable tools that can edit your config'")
            }
            
            verify(exactly = 0) { burpSuite.importProjectOptionsFromJson(any()) }
        }
    }

    @Nested
    inner class EditorTests {
        @Test
        fun `get active editor contents should handle no editor`() {
            mockkStatic("net.portswigger.mcp.tools.ToolsKt")
            
            every { getActiveEditor(api) } returns null
            
            runBlocking {
                val result = client.callTool("get_active_editor_contents", emptyMap())
                
                delay(100)
                result.expectTextContent("<No active editor>")
            }
        }
        
        @Test
        fun `get active editor contents should return text`() {
            mockkStatic("net.portswigger.mcp.tools.ToolsKt")
            
            val textArea = mockk<JTextArea>()
            every { getActiveEditor(api) } returns textArea
            every { textArea.text } returns "Editor content"
            
            runBlocking {
                val result = client.callTool("get_active_editor_contents", emptyMap())
                
                delay(100)
                result.expectTextContent("Editor content")
            }
        }
        
        @Test
        fun `set active editor contents should handle no editor`() {
            mockkStatic("net.portswigger.mcp.tools.ToolsKt")
            
            every { getActiveEditor(api) } returns null
            
            runBlocking {
                val result = client.callTool(
                    "set_active_editor_contents", mapOf(
                        "text" to "New content"
                    )
                )
                
                delay(100)
                result.expectTextContent("<No active editor>")
            }
        }
        
        @Test
        fun `set active editor contents should handle non-editable editor`() {
            mockkStatic("net.portswigger.mcp.tools.ToolsKt")
            
            val textArea = mockk<JTextArea>()
            every { getActiveEditor(api) } returns textArea
            every { textArea.isEditable } returns false
            
            runBlocking {
                val result = client.callTool(
                    "set_active_editor_contents", mapOf(
                        "text" to "New content"
                    )
                )
                
                delay(100)
                result.expectTextContent("<Current editor is not editable>")
            }
        }
        
        @Test
        fun `set active editor contents should update text`() {
            mockkStatic("net.portswigger.mcp.tools.ToolsKt")
            
            val textArea = mockk<JTextArea>()
            every { getActiveEditor(api) } returns textArea
            every { textArea.isEditable } returns true
            every { textArea.text = any() } just runs
            
            runBlocking {
                val result = client.callTool(
                    "set_active_editor_contents", mapOf(
                        "text" to "New content"
                    )
                )
                
                delay(100)
                result.expectTextContent("Editor text has been set")
            }
            
            verify(exactly = 1) { textArea.text = "New content" }
        }
    }
    
    @Nested
    inner class PaginatedToolsTests {
        private fun organizerItem(
            id: Int,
            url: String,
            status: OrganizerItemStatus = OrganizerItemStatus.NEW,
            notes: String = "organizer note",
            highlight: HighlightColor? = HighlightColor.YELLOW,
            responseStatus: Short? = 200,
            requestText: String = "GET $url HTTP/1.1\r\nAuthorization: REQUEST_HEADER_SECRET\r\n\r\nREQUEST_BODY_SECRET",
            responseText: String = "HTTP/1.1 200 OK\r\nSet-Cookie: RESPONSE_HEADER_SECRET\r\n\r\nRESPONSE_BODY_SECRET"
        ): OrganizerItem {
            val item = mockk<OrganizerItem>()
            val request = mockk<HttpRequest>()
            val response = responseStatus?.let { mockk<HttpResponse>() }
            val annotations = mockk<Annotations>()
            every { item.id() } returns id
            every { item.status() } returns status
            every { item.request() } returns request
            every { item.response() } returns response
            every { item.hasResponse() } returns (response != null)
            every { item.annotations() } returns annotations
            every { item.contains(any<java.util.regex.Pattern>()) } answers {
                firstArg<java.util.regex.Pattern>().matcher(url).find()
            }
            every { request.method() } returns "GET"
            every { request.url() } returns url
            every { request.toString() } returns requestText
            every { annotations.notes() } returns notes
            every { annotations.hasHighlightColor() } returns (highlight != null)
            every { annotations.highlightColor() } returns (highlight ?: HighlightColor.NONE)
            if (response != null) {
                every { response.statusCode() } returns responseStatus
                every { response.toString() } returns responseText
            }
            return item
        }

        private fun historyItem(
            id: Int,
            url: String,
            body: String = "secret-request-body",
            initialNotes: String = "test note",
            initialHighlight: HighlightColor? = null,
            initialResponse: HttpResponse? = null
        ): ProxyHttpRequestResponse {
            mockkStatic("net.portswigger.mcp.schema.SerializationKt")
            val item = mockk<ProxyHttpRequestResponse>()
            val request = mockk<HttpRequest>()
            every { request.method() } returns "GET"
            every { request.url() } returns url
            every { item.id() } returns id
            every { item.time() } returns ZonedDateTime.parse("2026-10-29T10:00:00Z")
            every { item.finalRequest() } returns null
            every { item.request() } returns request
            every { item.edited() } returns false
            val annotations = mockk<Annotations>()
            var notesValue = initialNotes
            every { annotations.notes() } answers { notesValue }
            every { annotations.setNotes(any()) } answers {
                notesValue = firstArg()
            }
            var highlightValue = initialHighlight
            every { annotations.hasHighlightColor() } answers { highlightValue != null }
            every { annotations.highlightColor() } answers { highlightValue ?: HighlightColor.NONE }
            every { annotations.setHighlightColor(any()) } answers {
                highlightValue = firstArg<HighlightColor>().takeUnless { it == HighlightColor.NONE }
            }
            every { item.annotations() } returns annotations
            every { item.response() } returns initialResponse
            every { item.contains(any<java.util.regex.Pattern>()) } answers {
                firstArg<java.util.regex.Pattern>().matcher(url).find()
            }
            every { item.toSerializableForm() } returns HttpRequestResponse(
                request = "GET $url HTTP/1.1\r\n\r\n$body",
                response = "<no response>",
                notes = "test note"
            )
            return item
        }

        @Test
        fun `history summary filters before pagination and excludes message bodies`() {
            val proxy = mockk<Proxy>()
            every { api.proxy() } returns proxy
            every { proxy.history() } returns listOf(
                historyItem(71, "https://one.test/match", "REQUEST_SECRET"),
                historyItem(72, "https://two.test/skip", "REQUEST_SECRET"),
                historyItem(73, "https://three.test/match", "REQUEST_SECRET")
            )

            runBlocking {
                val first = client.callTool("get_proxy_http_history_summary", mapOf(
                    "count" to 1, "offset" to 0, "regex" to "match"
                )).expectTextContent()
                val second = client.callTool("get_proxy_http_history_summary", mapOf(
                    "count" to 1, "offset" to 1, "regex" to "match"
                )).expectTextContent()

                assertTrue(first.contains("\"historyId\":71"))
                assertTrue(second.contains("\"historyId\":73"))
                assertFalse(first.contains("REQUEST_SECRET"))
                assertFalse(first.contains("secret-request-body"))
                assertFalse(first.contains("<no response>"))
            }
        }

        @Test
        fun `history summary accepts omitted regex`() {
            val proxy = mockk<Proxy>()
            every { api.proxy() } returns proxy
            every { proxy.history() } returns listOf(historyItem(81, "https://one.test/"))

            runBlocking {
                val text = client.callTool("get_proxy_http_history_summary", mapOf(
                    "count" to 1, "offset" to 0
                )).expectTextContent()
                assertTrue(text.contains("\"historyId\":81"))
            }
        }

        @Test
        fun `invalid history summary regex returns tool error`() {
            val proxy = mockk<Proxy>()
            every { api.proxy() } returns proxy
            every { proxy.history() } returns emptyList()

            runBlocking {
                val result = client.callTool("get_proxy_http_history_summary", mapOf(
                    "count" to 1, "offset" to 0, "regex" to "["
                ))
                assertTrue(result?.isError ?: false)
                assertTrue(((result?.content?.firstOrNull() as? TextContent)?.text ?: "").contains("Unclosed"))
            }
        }

        @Test
        fun `history summary and item respect denied history access`() {
            val previousHandler = DataAccessSecurity.approvalHandler
            val denyingHandler = mockk<DataAccessApprovalHandler>()
            DataAccessSecurity.approvalHandler = denyingHandler
            dataAccessApprovalRequired = true
            coEvery { denyingHandler.requestDataAccess(DataAccessType.HTTP_HISTORY, config) } returns false
            try {
                runBlocking {
                    assertEquals("HTTP history access denied by Burp Suite", client.callTool(
                        "get_proxy_http_history_summary", mapOf("count" to 1, "offset" to 0)
                    ).expectTextContent())
                    assertEquals("HTTP history access denied by Burp Suite", client.callTool(
                        "get_proxy_http_history_item", mapOf("historyId" to 71)
                    ).expectTextContent())
                }
            } finally {
                dataAccessApprovalRequired = false
                DataAccessSecurity.approvalHandler = previousHandler
            }
        }

        @Test
        fun `history item resolves official ID and safely serializes absent response`() {
            val proxy = mockk<Proxy>()
            val item = historyItem(901, "https://one.test/", "REQUEST_SECRET")
            every { api.proxy() } returns proxy
            every { proxy.history() } returns listOf(item)
            mockkStatic("net.portswigger.mcp.schema.SerializationKt")
            every { item.toSerializableForm() } returns HttpRequestResponse(
                request = "GET / HTTP/1.1\r\n\r\nrequest body",
                response = "<no response>",
                notes = "test note"
            )

            runBlocking {
                val text = client.callTool("get_proxy_http_history_item", mapOf("historyId" to 901))
                    .expectTextContent()
                assertTrue(text.contains("<no response>"))
                verify(exactly = 1) { item.id() }
            }
        }

        @Test
        fun `history item reports unknown ID`() {
            val proxy = mockk<Proxy>()
            val item = historyItem(901, "https://one.test/")
            every { api.proxy() } returns proxy
            every { proxy.history() } returns listOf(item)

            runBlocking {
                assertEquals("No Proxy HTTP history item found with ID 999", client.callTool(
                    "get_proxy_http_history_item", mapOf("historyId" to 999)
                ).expectTextContent())
            }
        }

        @Test
        fun `send proxy history to repeater uses official ID and prefers final request`() {
            val proxy = mockk<Proxy>()
            val repeater = mockk<burp.api.montoya.repeater.Repeater>(relaxed = true)
            val first = historyItem(12, "https://first.test/first")
            val selected = historyItem(9876, "https://example.test/original")
            val finalRequest = mockk<HttpRequest>()
            val service = mockk<burp.api.montoya.http.HttpService>()
            every { finalRequest.method() } returns "POST"
            every { finalRequest.url() } returns "https://example.test/final?token=SECRET"
            every { finalRequest.path() } returns "/final"
            every { finalRequest.httpService() } returns service
            every { service.host() } returns "example.test"
            every { finalRequest.toString() } returns "Authorization: secret\r\nCookie: secret\r\n\r\nBODY_SECRET"
            every { selected.finalRequest() } returns finalRequest
            every { api.proxy() } returns proxy
            every { api.repeater() } returns repeater
            every { proxy.history() } returns listOf(first, selected)

            runBlocking {
                val text = client.callTool("send_proxy_history_to_repeater", mapOf(
                    "historyId" to 9876,
                    "tabName" to "History acceptance test"
                )).expectTextContent()

                assertTrue(text.contains("9876"))
                assertTrue(text.contains("POST example.test/final"))
                assertTrue(text.contains("History acceptance test"))
                assertTrue(text.contains("Request was not sent"))
                assertFalse(text.contains("Authorization"))
                assertFalse(text.contains("Cookie"))
                assertFalse(text.contains("SECRET"))
                assertFalse(text.contains("BODY_SECRET"))
                verify(exactly = 1) { selected.id() }
                verify(exactly = 1) { selected.finalRequest() }
                verify(exactly = 0) { selected.request() }
                verify(exactly = 1) { repeater.sendToRepeater(finalRequest, "History acceptance test") }
                verify(exactly = 0) { api.http() }
            }
        }

        @Test
        fun `send proxy history to repeater falls back and chooses deterministic name`() {
            val proxy = mockk<Proxy>()
            val repeater = mockk<burp.api.montoya.repeater.Repeater>(relaxed = true)
            val item = historyItem(51, "https://example.test/path")
            val request = mockk<HttpRequest>()
            val service = mockk<burp.api.montoya.http.HttpService>()
            every { item.request() } returns request
            every { request.method() } returns "GET"
            every { request.url() } returns "https://example.test/path"
            every { request.path() } returns "/path"
            every { request.httpService() } returns service
            every { service.host() } returns "example.test"
            every { api.proxy() } returns proxy
            every { api.repeater() } returns repeater
            every { proxy.history() } returns listOf(item)

            runBlocking {
                val text = client.callTool("send_proxy_history_to_repeater", mapOf(
                    "historyId" to 51, "tabName" to "  "
                )).expectTextContent()
                assertTrue(text.contains("History 51"))
                verify(exactly = 1) { item.finalRequest() }
                verify(exactly = 1) { item.request() }
                verify(exactly = 1) { repeater.sendToRepeater(request, "History 51") }
            }
        }

        @Test
        fun `send proxy history to repeater handles omitted tab name`() {
            val proxy = mockk<Proxy>()
            val repeater = mockk<burp.api.montoya.repeater.Repeater>(relaxed = true)
            val item = historyItem(52, "https://example.test/path")
            val request = mockk<HttpRequest>()
            val service = mockk<burp.api.montoya.http.HttpService>()
            every { item.request() } returns request
            every { request.method() } returns "GET"
            every { request.path() } returns "/path"
            every { request.httpService() } returns service
            every { service.host() } returns "example.test"
            every { api.proxy() } returns proxy
            every { api.repeater() } returns repeater
            every { proxy.history() } returns listOf(item)

            runBlocking {
                val text = client.callTool("send_proxy_history_to_repeater", mapOf("historyId" to 52))
                    .expectTextContent()
                assertTrue(text.contains("History 52"))
                verify(exactly = 1) { repeater.sendToRepeater(request, "History 52") }
                verify(exactly = 0) { api.http() }
            }
        }

        @Test
        fun `send proxy history to repeater rejects unknown ID and missing request`() {
            val proxy = mockk<Proxy>()
            val repeater = mockk<burp.api.montoya.repeater.Repeater>(relaxed = true)
            val item = mockk<ProxyHttpRequestResponse>()
            every { item.id() } returns 8
            every { item.finalRequest() } returns null
            every { item.request() } returns null
            every { api.proxy() } returns proxy
            every { api.repeater() } returns repeater
            every { proxy.history() } returns listOf(item)

            runBlocking {
                assertTrue(client.callTool("send_proxy_history_to_repeater", mapOf(
                    "historyId" to 9
                )).expectTextContent().contains("No Proxy HTTP history item found"))
                assertTrue(client.callTool("send_proxy_history_to_repeater", mapOf(
                    "historyId" to 8
                )).expectTextContent().contains("has no request"))
                verify(exactly = 0) { repeater.sendToRepeater(any<HttpRequest>(), any<String>()) }
                verify(exactly = 0) { api.http() }
            }
        }

        @Test
        fun `send proxy history to repeater respects denied access`() {
            val previousHandler = DataAccessSecurity.approvalHandler
            val denyingHandler = mockk<DataAccessApprovalHandler>()
            DataAccessSecurity.approvalHandler = denyingHandler
            dataAccessApprovalRequired = true
            coEvery { denyingHandler.requestDataAccess(DataAccessType.HTTP_HISTORY, config) } returns false
            try {
                runBlocking {
                    assertEquals("HTTP history access denied by Burp Suite", client.callTool(
                        "send_proxy_history_to_repeater", mapOf("historyId" to 7)
                    ).expectTextContent())
                    verify(exactly = 0) { api.repeater() }
                    verify(exactly = 0) { api.http() }
                }
            } finally {
                dataAccessApprovalRequired = false
                DataAccessSecurity.approvalHandler = previousHandler
            }
        }

        @Test
        fun `send proxy history to intruder uses official ID and final request with safe output`() {
            val proxy = mockk<Proxy>()
            val intruder = mockk<Intruder>(relaxed = true)
            val other = historyItem(15, "https://other.test/?token=OTHER_SECRET")
            val selected = historyItem(
                9026,
                "https://shop.test/rest/products/search?q=QUERY_SECRET",
                initialNotes = "NOTES_SECRET",
                initialHighlight = HighlightColor.CYAN
            )
            val finalRequest = mockk<HttpRequest>()
            every { selected.finalRequest() } returns finalRequest
            every { finalRequest.toString() } returns "GET /?token=TOKEN_SECRET HTTP/1.1\r\nAuthorization: AUTH_SECRET\r\nCookie: COOKIE_SECRET\r\n\r\nBODY_SECRET"
            every { api.proxy() } returns proxy
            every { proxy.history() } returns listOf(other, selected)
            every { api.intruder() } returns intruder

            runBlocking {
                val text = client.callTool("send_proxy_history_to_intruder", mapOf(
                    "historyId" to 9026, "tabName" to "MCP History Intruder validation"
                )).expectTextContent()

                assertEquals(
                    "Opened Proxy history item 9026 in Intruder tab 'MCP History Intruder validation'. No Intruder attack was started and no HTTP request was transmitted.",
                    text
                )
                assertFalse(text.contains("shop.test"))
                assertFalse(text.contains("QUERY_SECRET"))
                assertFalse(text.contains("TOKEN_SECRET"))
                assertFalse(text.contains("AUTH_SECRET"))
                assertFalse(text.contains("COOKIE_SECRET"))
                assertFalse(text.contains("BODY_SECRET"))
                assertFalse(text.contains("NOTES_SECRET"))
                verify(exactly = 1) { other.id() }
                verify(exactly = 1) { selected.id() }
                verify(exactly = 1) { selected.finalRequest() }
                verify(exactly = 0) { selected.request() }
                verify(exactly = 1) { intruder.sendToIntruder(finalRequest, "MCP History Intruder validation") }
                verify(exactly = 0) { api.http() }
                verify(exactly = 0) { api.repeater() }
                verify(exactly = 0) { api.organizer() }
                verify(exactly = 0) { proxy.enableIntercept() }
                verify(exactly = 0) { proxy.disableIntercept() }
            }
        }

        @Test
        fun `send proxy history to intruder falls back to request and defaults tab name`() {
            val proxy = mockk<Proxy>()
            val intruder = mockk<Intruder>(relaxed = true)
            val item = historyItem(62, "https://shop.test/safe")
            val request = item.request()
            every { item.finalRequest() } returns null
            every { api.proxy() } returns proxy
            every { proxy.history() } returns listOf(item)
            every { api.intruder() } returns intruder

            runBlocking {
                val omitted = client.callTool("send_proxy_history_to_intruder", mapOf(
                    "historyId" to 62
                )).expectTextContent()
                val blank = client.callTool("send_proxy_history_to_intruder", mapOf(
                    "historyId" to 62, "tabName" to "  "
                )).expectTextContent()

                assertTrue(omitted.contains("History 62"))
                assertTrue(blank.contains("History 62"))
                assertTrue(omitted.contains("No Intruder attack was started"))
                assertTrue(omitted.contains("no HTTP request was transmitted"))
                verify(exactly = 2) { item.finalRequest() }
                verify(exactly = 3) { item.request() }
                verify(exactly = 2) { intruder.sendToIntruder(request, "History 62") }
                verify(exactly = 0) { api.http() }
                verify(exactly = 0) { api.repeater() }
                verify(exactly = 0) { api.organizer() }
            }
        }

        @Test
        fun `send proxy history to intruder handles unknown ID and missing request without opening`() {
            val proxy = mockk<Proxy>()
            val item = mockk<ProxyHttpRequestResponse>()
            every { item.id() } returns 71
            every { item.finalRequest() } returns null
            every { item.request() } returns null
            every { api.proxy() } returns proxy
            every { proxy.history() } returns listOf(item)

            runBlocking {
                assertEquals("No Proxy HTTP history item found with ID 72", client.callTool(
                    "send_proxy_history_to_intruder", mapOf("historyId" to 72)
                ).expectTextContent())
                assertTrue(client.callTool("send_proxy_history_to_intruder", mapOf(
                    "historyId" to 71
                )).expectTextContent().contains("has no request to open in Intruder"))
                verify(exactly = 0) { api.intruder() }
                verify(exactly = 0) { api.http() }
                verify(exactly = 0) { api.repeater() }
                verify(exactly = 0) { api.organizer() }
            }
        }

        @Test
        fun `send proxy history to intruder access denial prevents lookup and opening`() {
            val previousHandler = DataAccessSecurity.approvalHandler
            val denyingHandler = mockk<DataAccessApprovalHandler>()
            DataAccessSecurity.approvalHandler = denyingHandler
            dataAccessApprovalRequired = true
            coEvery { denyingHandler.requestDataAccess(DataAccessType.HTTP_HISTORY, config) } returns false
            try {
                runBlocking {
                    assertEquals("HTTP history access denied by Burp Suite", client.callTool(
                        "send_proxy_history_to_intruder", mapOf("historyId" to 81)
                    ).expectTextContent())
                    verify(exactly = 0) { api.proxy() }
                    verify(exactly = 0) { api.intruder() }
                    verify(exactly = 0) { api.http() }
                    verify(exactly = 0) { api.repeater() }
                    verify(exactly = 0) { api.organizer() }
                }
            } finally {
                dataAccessApprovalRequired = false
                DataAccessSecurity.approvalHandler = previousHandler
            }
        }

        @Test
        fun `set proxy history notes defaults to append for empty and existing notes`() {
            val proxy = mockk<Proxy>()
            val emptyNotesItem = historyItem(61, "https://one.test/", initialNotes = "")
            val existingNotesItem = historyItem(62, "https://two.test/", initialNotes = "Existing note")
            val emptyAnnotations = emptyNotesItem.annotations()
            val existingAnnotations = existingNotesItem.annotations()
            every { api.proxy() } returns proxy
            every { proxy.history() } returns listOf(emptyNotesItem, existingNotesItem)

            runBlocking {
                val emptyResult = client.callTool("set_proxy_history_notes", mapOf(
                    "historyId" to 61, "notes" to "New finding"
                )).expectTextContent()
                val appendResult = client.callTool("set_proxy_history_notes", mapOf(
                    "historyId" to 62, "notes" to "Second finding"
                )).expectTextContent()

                assertEquals("New finding", emptyAnnotations.notes())
                assertEquals("Existing note\nSecond finding", existingAnnotations.notes())
                assertTrue(emptyResult.contains("appended"))
                assertTrue(appendResult.contains("appended"))
                assertFalse(emptyResult.contains("New finding"))
                assertFalse(appendResult.contains("Second finding"))
                verify(exactly = 1) { emptyAnnotations.setNotes("New finding") }
                verify(exactly = 1) { existingAnnotations.setNotes("Existing note\nSecond finding") }
                verify(exactly = 0) { api.http() }
                verify(exactly = 0) { api.repeater() }
                verify(exactly = 0) { api.intruder() }
            }
        }

        @Test
        fun `set proxy history notes replaces only the selected item`() {
            val proxy = mockk<Proxy>()
            val selected = historyItem(71, "https://selected.test/", initialNotes = "Old note")
            val other = historyItem(72, "https://other.test/", initialNotes = "Other note")
            val selectedAnnotations = selected.annotations()
            val otherAnnotations = other.annotations()
            every { api.proxy() } returns proxy
            every { proxy.history() } returns listOf(other, selected)

            runBlocking {
                val result = client.callTool("set_proxy_history_notes", mapOf(
                    "historyId" to 71, "notes" to "Replacement note", "append" to false
                )).expectTextContent()

                assertEquals("Replacement note", selectedAnnotations.notes())
                assertEquals("Other note", otherAnnotations.notes())
                assertTrue(result.contains("replaced"))
                assertFalse(result.contains("Replacement note"))
                verify(exactly = 1) { selectedAnnotations.setNotes("Replacement note") }
                verify(exactly = 0) { otherAnnotations.setNotes(any()) }
                verify(exactly = 0) { api.http() }
                verify(exactly = 0) { api.repeater() }
                verify(exactly = 0) { api.intruder() }
            }
        }

        @Test
        fun `set proxy history notes rejects blank input without changing notes`() {
            val proxy = mockk<Proxy>()
            val item = historyItem(81, "https://one.test/", initialNotes = "Keep this note")
            val annotations = item.annotations()
            every { api.proxy() } returns proxy
            every { proxy.history() } returns listOf(item)

            runBlocking {
                for (invalidNotes in listOf("", "   \t\n")) {
                    val result = client.callTool("set_proxy_history_notes", mapOf(
                        "historyId" to 81, "notes" to invalidNotes
                    ))
                    assertTrue(result?.isError ?: false)
                    assertTrue(((result?.content?.firstOrNull() as? TextContent)?.text ?: "").contains("must not be blank"))
                }
                assertEquals("Keep this note", annotations.notes())
                verify(exactly = 0) { annotations.setNotes(any()) }
            }
        }

        @Test
        fun `set proxy history notes unknown ID does not modify another item`() {
            val proxy = mockk<Proxy>()
            val item = historyItem(91, "https://one.test/", initialNotes = "Preserve me")
            val annotations = item.annotations()
            every { api.proxy() } returns proxy
            every { proxy.history() } returns listOf(item)

            runBlocking {
                val result = client.callTool("set_proxy_history_notes", mapOf(
                    "historyId" to 999, "notes" to "Do not store"
                )).expectTextContent()
                assertTrue(result.contains("No Proxy HTTP history item found with ID 999"))
                assertEquals("Preserve me", annotations.notes())
                verify(exactly = 0) { annotations.setNotes(any()) }
                verify(exactly = 0) { api.http() }
                verify(exactly = 0) { api.repeater() }
                verify(exactly = 0) { api.intruder() }
            }
        }

        @Test
        fun `set proxy history notes denied access does not modify notes`() {
            val previousHandler = DataAccessSecurity.approvalHandler
            val denyingHandler = mockk<DataAccessApprovalHandler>()
            val proxy = mockk<Proxy>()
            val item = historyItem(101, "https://one.test/", initialNotes = "Preserve me")
            val annotations = item.annotations()
            DataAccessSecurity.approvalHandler = denyingHandler
            dataAccessApprovalRequired = true
            coEvery { denyingHandler.requestDataAccess(DataAccessType.HTTP_HISTORY, config) } returns false
            every { api.proxy() } returns proxy
            every { proxy.history() } returns listOf(item)
            try {
                runBlocking {
                    assertEquals("HTTP history access denied by Burp Suite", client.callTool(
                        "set_proxy_history_notes", mapOf("historyId" to 101, "notes" to "Denied note")
                    ).expectTextContent())
                    assertEquals("Preserve me", annotations.notes())
                    verify(exactly = 0) { annotations.setNotes(any()) }
                    verify(exactly = 0) { api.proxy() }
                    verify(exactly = 0) { api.http() }
                    verify(exactly = 0) { api.repeater() }
                    verify(exactly = 0) { api.intruder() }
                }
            } finally {
                dataAccessApprovalRequired = false
                DataAccessSecurity.approvalHandler = previousHandler
            }
        }

        @Test
        fun `set proxy history highlight accepts enum names case insensitively and trims whitespace`() {
            val proxy = mockk<Proxy>()
            val lower = historyItem(121, "https://one.test/")
            val mixed = historyItem(122, "https://two.test/")
            val spaced = historyItem(123, "https://three.test/")
            every { api.proxy() } returns proxy
            every { proxy.history() } returns listOf(lower, mixed, spaced)

            runBlocking {
                assertTrue(client.callTool("set_proxy_history_highlight", mapOf(
                    "historyId" to 121, "color" to "yellow"
                )).expectTextContent().contains("YELLOW"))
                assertTrue(client.callTool("set_proxy_history_highlight", mapOf(
                    "historyId" to 122, "color" to "rEd"
                )).expectTextContent().contains("RED"))
                assertTrue(client.callTool("set_proxy_history_highlight", mapOf(
                    "historyId" to 123, "color" to "  oRaNgE  "
                )).expectTextContent().contains("ORANGE"))

                assertEquals(HighlightColor.YELLOW, lower.annotations().highlightColor())
                assertEquals(HighlightColor.RED, mixed.annotations().highlightColor())
                assertEquals(HighlightColor.ORANGE, spaced.annotations().highlightColor())
            }
        }

        @Test
        fun `set proxy history highlight changes only matching item and returns safe confirmation`() {
            val proxy = mockk<Proxy>()
            val selected = historyItem(131, "https://selected.test/", body = "BODY_SECRET", initialNotes = "NOTE_SECRET")
            val other = historyItem(132, "https://other.test/", initialHighlight = HighlightColor.BLUE)
            val selectedAnnotations = selected.annotations()
            val otherAnnotations = other.annotations()
            every { api.proxy() } returns proxy
            every { proxy.history() } returns listOf(other, selected)

            runBlocking {
                val output = client.callTool("set_proxy_history_highlight", mapOf(
                    "historyId" to 131, "color" to "GREEN"
                )).expectTextContent()

                assertEquals("Highlight set to GREEN on Proxy history item 131", output)
                assertFalse(output.contains("selected.test"))
                assertFalse(output.contains("NOTE_SECRET"))
                assertFalse(output.contains("BODY_SECRET"))
                assertEquals(HighlightColor.GREEN, selectedAnnotations.highlightColor())
                assertEquals(HighlightColor.BLUE, otherAnnotations.highlightColor())
                verify(exactly = 1) { selectedAnnotations.setHighlightColor(HighlightColor.GREEN) }
                verify(exactly = 0) { otherAnnotations.setHighlightColor(any()) }
                verify(exactly = 0) { api.http() }
                verify(exactly = 0) { api.repeater() }
                verify(exactly = 0) { api.intruder() }
            }
        }

        @Test
        fun `set proxy history highlight rejects invalid enum without changing highlight`() {
            val proxy = mockk<Proxy>()
            val item = historyItem(141, "https://one.test/", initialHighlight = HighlightColor.RED)
            val annotations = item.annotations()
            every { api.proxy() } returns proxy
            every { proxy.history() } returns listOf(item)

            runBlocking {
                val result = client.callTool("set_proxy_history_highlight", mapOf(
                    "historyId" to 141, "color" to "NOT_A_COLOR"
                ))
                assertTrue(result?.isError ?: false)
                assertTrue(((result?.content?.firstOrNull() as? TextContent)?.text ?: "")
                    .contains("Unsupported highlight color"))
                assertEquals(HighlightColor.RED, annotations.highlightColor())
                verify(exactly = 0) { annotations.setHighlightColor(any()) }
            }
        }

        @Test
        fun `set proxy history highlight NONE clears color and summary reports state`() {
            val proxy = mockk<Proxy>()
            val item = historyItem(151, "https://one.test/", initialHighlight = HighlightColor.YELLOW)
            val annotations = item.annotations()
            every { api.proxy() } returns proxy
            every { proxy.history() } returns listOf(item)

            runBlocking {
                assertTrue(client.callTool("set_proxy_history_highlight", mapOf(
                    "historyId" to 151, "color" to "NONE"
                )).expectTextContent().contains("cleared"))
                assertFalse(annotations.hasHighlightColor())
                assertTrue(client.callTool("get_proxy_http_history_summary", mapOf(
                    "count" to 1, "offset" to 0
                )).expectTextContent().contains("\"highlightColor\":null"))

                client.callTool("set_proxy_history_highlight", mapOf(
                    "historyId" to 151, "color" to "yellow"
                ))
                assertTrue(client.callTool("get_proxy_http_history_summary", mapOf(
                    "count" to 1, "offset" to 0
                )).expectTextContent().contains("\"highlightColor\":\"YELLOW\""))
                verify(exactly = 1) { annotations.setHighlightColor(HighlightColor.NONE) }
            }
        }

        @Test
        fun `set proxy history highlight unknown ID does not modify any item`() {
            val proxy = mockk<Proxy>()
            val item = historyItem(161, "https://one.test/", initialHighlight = HighlightColor.CYAN)
            val annotations = item.annotations()
            every { api.proxy() } returns proxy
            every { proxy.history() } returns listOf(item)

            runBlocking {
                assertTrue(client.callTool("set_proxy_history_highlight", mapOf(
                    "historyId" to 999, "color" to "RED"
                )).expectTextContent().contains("No Proxy HTTP history item found with ID 999"))
                assertEquals(HighlightColor.CYAN, annotations.highlightColor())
                verify(exactly = 0) { annotations.setHighlightColor(any()) }
            }
        }

        @Test
        fun `set proxy history highlight denied access does not modify item`() {
            val previousHandler = DataAccessSecurity.approvalHandler
            val denyingHandler = mockk<DataAccessApprovalHandler>()
            val proxy = mockk<Proxy>()
            val item = historyItem(171, "https://one.test/", initialHighlight = HighlightColor.PINK)
            val annotations = item.annotations()
            DataAccessSecurity.approvalHandler = denyingHandler
            dataAccessApprovalRequired = true
            coEvery { denyingHandler.requestDataAccess(DataAccessType.HTTP_HISTORY, config) } returns false
            every { api.proxy() } returns proxy
            every { proxy.history() } returns listOf(item)
            try {
                runBlocking {
                    assertEquals("HTTP history access denied by Burp Suite", client.callTool(
                        "set_proxy_history_highlight", mapOf("historyId" to 171, "color" to "RED")
                    ).expectTextContent())
                    assertEquals(HighlightColor.PINK, annotations.highlightColor())
                    verify(exactly = 0) { annotations.setHighlightColor(any()) }
                    verify(exactly = 0) { api.proxy() }
                    verify(exactly = 0) { api.http() }
                    verify(exactly = 0) { api.repeater() }
                    verify(exactly = 0) { api.intruder() }
                }
            } finally {
                dataAccessApprovalRequired = false
                DataAccessSecurity.approvalHandler = previousHandler
            }
        }

        @Test
        fun `send proxy history to organizer wraps final request response and annotations`() {
            val previousHandler = DataAccessSecurity.approvalHandler
            val approvalHandler = mockk<DataAccessApprovalHandler>()
            val proxy = mockk<Proxy>()
            val organizer = mockk<Organizer>(relaxed = true)
            val other = historyItem(181, "https://other.test/")
            val response = mockk<HttpResponse>()
            val selected = historyItem(
                9001,
                "https://juice-shop.test/rest/admin/application-version",
                body = "REQUEST_BODY_SECRET",
                initialNotes = "NOTE_SECRET",
                initialHighlight = HighlightColor.YELLOW,
                initialResponse = response
            )
            val finalRequest = mockk<HttpRequest>()
            val annotations = selected.annotations()
            val wrapped = mockk<MontoyaHttpRequestResponse>()
            every { selected.finalRequest() } returns finalRequest
            every { finalRequest.toString() } returns "Authorization: AUTH_SECRET\r\nCookie: COOKIE_SECRET"
            every { response.toString() } returns "RESPONSE_BODY_SECRET"
            mockkStatic(MontoyaHttpRequestResponse::class)
            every {
                MontoyaHttpRequestResponse.httpRequestResponse(finalRequest, response, annotations)
            } returns wrapped
            every { api.proxy() } returns proxy
            every { api.organizer() } returns organizer
            every { proxy.history() } returns listOf(other, selected)
            DataAccessSecurity.approvalHandler = approvalHandler
            dataAccessApprovalRequired = true
            coEvery { approvalHandler.requestDataAccess(any(), config) } returns true
            try {
                runBlocking {
                    val text = client.callTool("send_proxy_history_to_organizer", mapOf(
                        "historyId" to 9001
                    )).expectTextContent()

                    assertTrue(text.contains("9001"), text)
                    assertTrue(text.contains("response included: true"))
                    assertTrue(text.contains("annotations preserved: true"))
                    assertTrue(text.contains("No HTTP request was transmitted"))
                    assertFalse(text.contains("AUTH_SECRET"))
                    assertFalse(text.contains("COOKIE_SECRET"))
                    assertFalse(text.contains("NOTE_SECRET"))
                    assertFalse(text.contains("REQUEST_BODY_SECRET"))
                    assertFalse(text.contains("RESPONSE_BODY_SECRET"))
                    coVerifySequence {
                        approvalHandler.requestDataAccess(DataAccessType.HTTP_HISTORY, config)
                        approvalHandler.requestDataAccess(DataAccessType.ORGANIZER, config)
                    }
                    verify(exactly = 1) { other.id() }
                    verify(exactly = 1) { selected.id() }
                    verify(exactly = 1) {
                        MontoyaHttpRequestResponse.httpRequestResponse(finalRequest, response, annotations)
                    }
                    verify(exactly = 1) { organizer.sendToOrganizer(wrapped) }
                    verify(exactly = 0) { api.http() }
                    verify(exactly = 0) { api.repeater() }
                    verify(exactly = 0) { api.intruder() }
                }
            } finally {
                dataAccessApprovalRequired = false
                DataAccessSecurity.approvalHandler = previousHandler
                unmockkStatic(MontoyaHttpRequestResponse::class)
            }
        }

        @Test
        fun `send proxy history to organizer uses request overload when response is absent`() {
            val proxy = mockk<Proxy>()
            val organizer = mockk<Organizer>(relaxed = true)
            val item = historyItem(191, "https://one.test/")
            val request = item.request()
            every { item.finalRequest() } returns null
            every { api.proxy() } returns proxy
            every { api.organizer() } returns organizer
            every { proxy.history() } returns listOf(item)
            mockkStatic(MontoyaHttpRequestResponse::class)

            runBlocking {
                val text = client.callTool("send_proxy_history_to_organizer", mapOf(
                    "historyId" to 191
                )).expectTextContent()
                assertTrue(text.contains("response included: false"))
                assertTrue(text.contains("annotations preserved: false"))
                assertTrue(text.contains("No HTTP request was transmitted"))
                verify(exactly = 1) { item.finalRequest() }
                verify(exactly = 2) { item.request() }
                verify(exactly = 1) { organizer.sendToOrganizer(request) }
                verify(exactly = 0) { MontoyaHttpRequestResponse.httpRequestResponse(any(), any(), any()) }
                verify(exactly = 0) { api.http() }
                verify(exactly = 0) { api.repeater() }
                verify(exactly = 0) { api.intruder() }
            }
            unmockkStatic(MontoyaHttpRequestResponse::class)
        }

        @Test
        fun `send proxy history to organizer unknown ID and missing request create nothing`() {
            val proxy = mockk<Proxy>()
            val organizer = mockk<Organizer>(relaxed = true)
            val item = mockk<ProxyHttpRequestResponse>()
            every { item.id() } returns 201
            every { item.finalRequest() } returns null
            every { item.request() } returns null
            every { api.proxy() } returns proxy
            every { api.organizer() } returns organizer
            every { proxy.history() } returns listOf(item)

            runBlocking {
                assertTrue(client.callTool("send_proxy_history_to_organizer", mapOf(
                    "historyId" to 202
                )).expectTextContent().contains("No Proxy HTTP history item found with ID 202"))
                assertTrue(client.callTool("send_proxy_history_to_organizer", mapOf(
                    "historyId" to 201
                )).expectTextContent().contains("has no request"))
                verify(exactly = 0) { organizer.sendToOrganizer(any<HttpRequest>()) }
                verify(exactly = 0) { organizer.sendToOrganizer(any<MontoyaHttpRequestResponse>()) }
                verify(exactly = 0) { api.http() }
                verify(exactly = 0) { api.repeater() }
                verify(exactly = 0) { api.intruder() }
            }
        }

        @Test
        fun `send proxy history to organizer history denial prevents insertion`() {
            val previousHandler = DataAccessSecurity.approvalHandler
            val approvalHandler = mockk<DataAccessApprovalHandler>()
            val organizer = mockk<Organizer>(relaxed = true)
            DataAccessSecurity.approvalHandler = approvalHandler
            dataAccessApprovalRequired = true
            coEvery { approvalHandler.requestDataAccess(DataAccessType.HTTP_HISTORY, config) } returns false
            try {
                runBlocking {
                    assertEquals("HTTP history access denied by Burp Suite", client.callTool(
                        "send_proxy_history_to_organizer", mapOf("historyId" to 211)
                    ).expectTextContent())
                    verify(exactly = 0) { api.proxy() }
                    verify(exactly = 0) { api.organizer() }
                    verify(exactly = 0) { organizer.sendToOrganizer(any<HttpRequest>()) }
                    verify(exactly = 0) { organizer.sendToOrganizer(any<MontoyaHttpRequestResponse>()) }
                }
            } finally {
                dataAccessApprovalRequired = false
                DataAccessSecurity.approvalHandler = previousHandler
            }
        }

        @Test
        fun `send proxy history to organizer organizer denial prevents insertion`() {
            val previousHandler = DataAccessSecurity.approvalHandler
            val approvalHandler = mockk<DataAccessApprovalHandler>()
            val proxy = mockk<Proxy>()
            val organizer = mockk<Organizer>(relaxed = true)
            DataAccessSecurity.approvalHandler = approvalHandler
            dataAccessApprovalRequired = true
            coEvery { approvalHandler.requestDataAccess(DataAccessType.HTTP_HISTORY, config) } returns true
            coEvery { approvalHandler.requestDataAccess(DataAccessType.ORGANIZER, config) } returns false
            every { api.proxy() } returns proxy
            every { api.organizer() } returns organizer
            try {
                runBlocking {
                    assertEquals("Organizer access denied by Burp Suite", client.callTool(
                        "send_proxy_history_to_organizer", mapOf("historyId" to 221)
                    ).expectTextContent())
                    coVerifySequence {
                        approvalHandler.requestDataAccess(DataAccessType.HTTP_HISTORY, config)
                        approvalHandler.requestDataAccess(DataAccessType.ORGANIZER, config)
                    }
                    verify(exactly = 0) { api.proxy() }
                    verify(exactly = 0) { api.organizer() }
                    verify(exactly = 0) { organizer.sendToOrganizer(any<HttpRequest>()) }
                    verify(exactly = 0) { organizer.sendToOrganizer(any<MontoyaHttpRequestResponse>()) }
                    verify(exactly = 0) { api.http() }
                    verify(exactly = 0) { api.repeater() }
                    verify(exactly = 0) { api.intruder() }
                }
            } finally {
                dataAccessApprovalRequired = false
                DataAccessSecurity.approvalHandler = previousHandler
            }
        }

        @Test
        fun `organizer summary returns compact official metadata and excludes HTTP contents`() {
            val organizer = mockk<Organizer>()
            val item = organizerItem(
                7301,
                "http://127.0.0.1:3000/rest/admin/application-version",
                status = OrganizerItemStatus.DONE,
                notes = "Finding note",
                highlight = HighlightColor.CYAN,
                responseStatus = 204
            )
            every { api.organizer() } returns organizer
            every { organizer.items() } returns listOf(item)

            runBlocking {
                val json = Json.parseToJsonElement(client.callTool(
                    "get_organizer_items_summary", mapOf("count" to 1, "offset" to 0)
                ).expectTextContent()).jsonObject

                assertEquals("7301", json.getValue("organizerId").jsonPrimitive.content)
                assertEquals("DONE", json.getValue("organizerStatus").jsonPrimitive.content)
                assertEquals("GET", json.getValue("method").jsonPrimitive.content)
                assertEquals("http://127.0.0.1:3000/rest/admin/application-version", json.getValue("url").jsonPrimitive.content)
                assertEquals("204", json.getValue("responseStatusCode").jsonPrimitive.content)
                assertEquals("Finding note", json.getValue("notes").jsonPrimitive.content)
                assertEquals("CYAN", json.getValue("highlightColor").jsonPrimitive.content)
                assertEquals("true", json.getValue("hasResponse").jsonPrimitive.content)
                assertEquals(
                    setOf("organizerId", "organizerStatus", "method", "url", "responseStatusCode", "notes", "highlightColor", "hasResponse"),
                    json.keys
                )
                val text = json.toString()
                assertFalse(text.contains("REQUEST_HEADER_SECRET"))
                assertFalse(text.contains("RESPONSE_HEADER_SECRET"))
                assertFalse(text.contains("REQUEST_BODY_SECRET"))
                assertFalse(text.contains("RESPONSE_BODY_SECRET"))
                verify(exactly = 0) { api.http() }
                verify(exactly = 0) { api.repeater() }
                verify(exactly = 0) { api.intruder() }
                verify(exactly = 0) { organizer.sendToOrganizer(any<HttpRequest>()) }
                verify(exactly = 0) { organizer.sendToOrganizer(any<MontoyaHttpRequestResponse>()) }
            }
        }

        @Test
        fun `organizer summary handles items without response and blank regex`() {
            val organizer = mockk<Organizer>()
            val item = organizerItem(7302, "https://empty.test/", notes = "", highlight = null, responseStatus = null)
            every { api.organizer() } returns organizer
            every { organizer.items() } returns listOf(item)

            runBlocking {
                val text = client.callTool("get_organizer_items_summary", mapOf(
                    "count" to 1, "offset" to 0, "regex" to "   "
                )).expectTextContent()
                val json = Json.parseToJsonElement(text).jsonObject
                assertEquals("7302", json.getValue("organizerId").jsonPrimitive.content)
                assertEquals("", json.getValue("notes").jsonPrimitive.content)
                assertEquals("null", json.getValue("highlightColor").toString())
                assertEquals("null", json.getValue("responseStatusCode").toString())
                assertEquals("false", json.getValue("hasResponse").jsonPrimitive.content)
                verify(exactly = 0) { item.response() }
                verify(exactly = 0) { organizer.items(any<OrganizerItemFilter>()) }
            }
        }

        @Test
        fun `organizer summary regex filtering occurs before pagination`() {
            val organizer = mockk<Organizer>()
            val first = organizerItem(741, "https://shop.test/match/one")
            val skipped = organizerItem(742, "https://shop.test/skip")
            val last = organizerItem(743, "https://shop.test/match/two")
            val allItems = listOf(first, skipped, last)
            every { api.organizer() } returns organizer
            every { organizer.items(any<OrganizerItemFilter>()) } answers {
                val filter = firstArg<OrganizerItemFilter>()
                allItems.filter(filter::matches)
            }

            runBlocking {
                val pageOne = client.callTool("get_organizer_items_summary", mapOf(
                    "count" to 1, "offset" to 0, "regex" to "match"
                )).expectTextContent()
                val pageTwo = client.callTool("get_organizer_items_summary", mapOf(
                    "count" to 1, "offset" to 1, "regex" to "match"
                )).expectTextContent()

                assertTrue(pageOne.contains("\"organizerId\":741"))
                assertFalse(pageOne.contains("742"))
                assertTrue(pageTwo.contains("\"organizerId\":743"))
                verify(exactly = 2) { organizer.items(any<OrganizerItemFilter>()) }
                verify(exactly = 0) { organizer.items() }
            }
        }

        @Test
        fun `organizer summary omitted regex paginates and reports end of items`() {
            val organizer = mockk<Organizer>()
            every { api.organizer() } returns organizer
            every { organizer.items() } returns listOf(
                organizerItem(751, "https://one.test/"),
                organizerItem(752, "https://two.test/")
            )

            runBlocking {
                val first = client.callTool("get_organizer_items_summary", mapOf(
                    "count" to 1, "offset" to 0
                )).expectTextContent()
                val second = client.callTool("get_organizer_items_summary", mapOf(
                    "count" to 1, "offset" to 1, "regex" to ""
                )).expectTextContent()
                val end = client.callTool("get_organizer_items_summary", mapOf(
                    "count" to 1, "offset" to 2
                )).expectTextContent()

                assertTrue(first.contains("\"organizerId\":751"))
                assertFalse(first.contains("752"))
                assertTrue(second.contains("\"organizerId\":752"))
                assertEquals("Reached end of items", end)
                verify(exactly = 3) { organizer.items() }
                verify(exactly = 0) { organizer.items(any<OrganizerItemFilter>()) }
            }
        }

        @Test
        fun `organizer invalid regex returns controlled error and connection remains usable`() {
            val organizer = mockk<Organizer>()
            every { api.organizer() } returns organizer
            every { organizer.items() } returns listOf(organizerItem(761, "https://one.test/"))

            runBlocking {
                val invalid = client.callTool("get_organizer_items_summary", mapOf(
                    "count" to 1, "offset" to 0, "regex" to "["
                ))
                assertTrue(invalid?.isError ?: false)
                assertTrue(((invalid?.content?.firstOrNull() as? TextContent)?.text ?: "").contains("Unclosed"))

                val valid = client.callTool("get_organizer_items_summary", mapOf(
                    "count" to 1, "offset" to 0
                )).expectTextContent()
                assertTrue(valid.contains("\"organizerId\":761"))
            }
        }

        @Test
        fun `organizer summary access denial returns existing denial message`() {
            val previousHandler = DataAccessSecurity.approvalHandler
            val denyingHandler = mockk<DataAccessApprovalHandler>()
            DataAccessSecurity.approvalHandler = denyingHandler
            dataAccessApprovalRequired = true
            coEvery { denyingHandler.requestDataAccess(DataAccessType.ORGANIZER, config) } returns false
            try {
                runBlocking {
                    assertEquals("Organizer access denied by Burp Suite", client.callTool(
                        "get_organizer_items_summary", mapOf("count" to 1, "offset" to 0)
                    ).expectTextContent())
                    verify(exactly = 0) { api.organizer() }
                }
            } finally {
                dataAccessApprovalRequired = false
                DataAccessSecurity.approvalHandler = previousHandler
            }
        }

        @Test
        fun `organizer item lookup uses official ID and returns serialized request and response`() {
            val organizer = mockk<Organizer>()
            val other = organizerItem(81, "https://other.test/")
            val selected = organizerItem(
                98765,
                "https://shop.test/rest/admin/application-version",
                status = OrganizerItemStatus.IN_PROGRESS,
                notes = "selected note",
                requestText = "GET /rest/admin/application-version HTTP/1.1\r\n\r\nselected request",
                responseText = "HTTP/1.1 200 OK\r\n\r\nselected response"
            )
            every { api.organizer() } returns organizer
            every { organizer.items() } returns listOf(other, selected)

            runBlocking {
                val text = client.callTool("get_organizer_item", mapOf("organizerId" to 98765)).expectTextContent()
                val json = Json.parseToJsonElement(text).jsonObject
                assertEquals("98765", json.getValue("id").jsonPrimitive.content)
                assertEquals("In progress", json.getValue("status").jsonPrimitive.content)
                assertTrue(json.getValue("request").jsonPrimitive.content.contains("selected request"))
                assertTrue(json.getValue("response").jsonPrimitive.content.contains("selected response"))
                assertEquals("selected note", json.getValue("notes").jsonPrimitive.content)
                verify(exactly = 1) { other.id() }
                verify(exactly = 2) { selected.id() }
                verify(exactly = 0) { api.http() }
                verify(exactly = 0) { api.repeater() }
                verify(exactly = 0) { api.intruder() }
                verify(exactly = 0) { organizer.sendToOrganizer(any<HttpRequest>()) }
                verify(exactly = 0) { organizer.sendToOrganizer(any<MontoyaHttpRequestResponse>()) }
            }
        }

        @Test
        fun `organizer item lookup handles no response and unknown official ID`() {
            val organizer = mockk<Organizer>()
            val item = organizerItem(91, "https://no-response.test/", responseStatus = null)
            every { api.organizer() } returns organizer
            every { organizer.items() } returns listOf(item)

            runBlocking {
                val text = client.callTool("get_organizer_item", mapOf("organizerId" to 91)).expectTextContent()
                val json = Json.parseToJsonElement(text).jsonObject
                assertEquals("<no response>", json.getValue("response").jsonPrimitive.content)
                assertEquals("No Organizer item found with ID 999", client.callTool(
                    "get_organizer_item", mapOf("organizerId" to 999)
                ).expectTextContent())
            }
        }

        @Test
        fun `organizer item access denial prevents lookup`() {
            val previousHandler = DataAccessSecurity.approvalHandler
            val denyingHandler = mockk<DataAccessApprovalHandler>()
            DataAccessSecurity.approvalHandler = denyingHandler
            dataAccessApprovalRequired = true
            coEvery { denyingHandler.requestDataAccess(DataAccessType.ORGANIZER, config) } returns false
            try {
                runBlocking {
                    assertEquals("Organizer access denied by Burp Suite", client.callTool(
                        "get_organizer_item", mapOf("organizerId" to 123)
                    ).expectTextContent())
                    verify(exactly = 0) { api.organizer() }
                }
            } finally {
                dataAccessApprovalRequired = false
                DataAccessSecurity.approvalHandler = previousHandler
            }
        }

        @Test
        fun `organizer item lookup uses bounded valid JSON and does not mutate annotations`() {
            val organizer = mockk<Organizer>()
            val item = organizerItem(
                801,
                "https://large.test/",
                requestText = "GET / HTTP/1.1\r\n\r\n${"Q".repeat(7000)}",
                responseText = "HTTP/1.1 200 OK\r\n\r\n${"R".repeat(7000)}"
            )
            val annotations = item.annotations()
            every { api.organizer() } returns organizer
            every { organizer.items() } returns listOf(item)

            runBlocking {
                val text = client.callTool("get_organizer_item", mapOf("organizerId" to 801)).expectTextContent()
                val json = Json.parseToJsonElement(text).jsonObject
                assertTrue(text.length <= 5000)
                assertTrue(text.contains("... (truncated)"))
                assertEquals("801", json.getValue("id").jsonPrimitive.content)
                verify(exactly = 0) { annotations.setNotes(any()) }
                verify(exactly = 0) { annotations.setHighlightColor(any()) }
                verify(exactly = 0) { api.http() }
                verify(exactly = 0) { api.repeater() }
                verify(exactly = 0) { api.intruder() }
                verify(exactly = 0) { organizer.sendToOrganizer(any<HttpRequest>()) }
                verify(exactly = 0) { organizer.sendToOrganizer(any<MontoyaHttpRequestResponse>()) }
            }
        }

        @Test
        fun `organizer item to repeater uses official ID and exact request with safe explicit tab name`() {
            val organizer = mockk<Organizer>()
            val repeater = mockk<Repeater>(relaxed = true)
            val other = organizerItem(80, "https://other.test/?token=OTHER_QUERY_SECRET")
            val selected = organizerItem(
                902,
                "https://shop.test/rest/admin/application-version?token=URL_SECRET",
                notes = "ORGANIZER_NOTE_SECRET",
                requestText = "GET /rest/admin/application-version?token=HEADER_SECRET HTTP/1.1\r\nAuthorization: AUTH_SECRET\r\nCookie: COOKIE_SECRET\r\n\r\nBODY_SECRET"
            )
            val selectedRequest = selected.request()
            val annotations = selected.annotations()
            every { api.organizer() } returns organizer
            every { organizer.items() } returns listOf(other, selected)
            every { api.repeater() } returns repeater

            runBlocking {
                val text = client.callTool("send_organizer_item_to_repeater", mapOf(
                    "organizerId" to 902, "tabName" to "MCP Organizer validation"
                )).expectTextContent()

                assertEquals(
                    "Opened Organizer item 902 in Repeater tab 'MCP Organizer validation'. Request was not transmitted.",
                    text
                )
                assertFalse(text.contains("shop.test"))
                assertFalse(text.contains("AUTH_SECRET"))
                assertFalse(text.contains("COOKIE_SECRET"))
                assertFalse(text.contains("BODY_SECRET"))
                assertFalse(text.contains("ORGANIZER_NOTE_SECRET"))
                assertFalse(text.contains("URL_SECRET"))
                assertFalse(text.contains("HEADER_SECRET"))
                verify(exactly = 1) { other.id() }
                verify(exactly = 1) { selected.id() }
                verify(exactly = 2) { selected.request() }
                verify(exactly = 1) { repeater.sendToRepeater(selectedRequest, "MCP Organizer validation") }
                verify(exactly = 0) { annotations.setNotes(any()) }
                verify(exactly = 0) { annotations.setHighlightColor(any()) }
                verify(exactly = 0) { api.http() }
                verify(exactly = 0) { api.intruder() }
                verify(exactly = 0) { api.proxy() }
                verify(exactly = 0) { organizer.sendToOrganizer(any<HttpRequest>()) }
                verify(exactly = 0) { organizer.sendToOrganizer(any<MontoyaHttpRequestResponse>()) }
            }
        }

        @Test
        fun `organizer item to repeater uses deterministic default for omitted and blank tab names`() {
            val organizer = mockk<Organizer>()
            val repeater = mockk<Repeater>(relaxed = true)
            val item = organizerItem(77, "https://shop.test/")
            val request = item.request()
            every { api.organizer() } returns organizer
            every { organizer.items() } returns listOf(item)
            every { api.repeater() } returns repeater

            runBlocking {
                assertTrue(client.callTool("send_organizer_item_to_repeater", mapOf(
                    "organizerId" to 77
                )).expectTextContent().contains("Organizer 77"))
                assertTrue(client.callTool("send_organizer_item_to_repeater", mapOf(
                    "organizerId" to 77, "tabName" to "   "
                )).expectTextContent().contains("Organizer 77"))
                verify(exactly = 2) { repeater.sendToRepeater(request, "Organizer 77") }
            }
        }

        @Test
        fun `organizer item to repeater fails safely for unknown ID or missing request`() {
            val organizer = mockk<Organizer>()
            val repeater = mockk<Repeater>(relaxed = true)
            val item = mockk<OrganizerItem>()
            every { item.id() } returns 99
            every { item.request() } returns null
            every { api.organizer() } returns organizer
            every { organizer.items() } returns listOf(item)
            every { api.repeater() } returns repeater

            runBlocking {
                assertEquals("No Organizer item found with ID 100", client.callTool(
                    "send_organizer_item_to_repeater", mapOf("organizerId" to 100)
                ).expectTextContent())
                assertTrue(client.callTool("send_organizer_item_to_repeater", mapOf(
                    "organizerId" to 99
                )).expectTextContent().contains("has no request"))
                verify(exactly = 0) { api.repeater() }
                verify(exactly = 0) { repeater.sendToRepeater(any<HttpRequest>()) }
                verify(exactly = 0) { repeater.sendToRepeater(any<HttpRequest>(), any()) }
                verify(exactly = 0) { api.http() }
            }
        }

        @Test
        fun `organizer item to repeater access denial prevents lookup and opening`() {
            val previousHandler = DataAccessSecurity.approvalHandler
            val denyingHandler = mockk<DataAccessApprovalHandler>()
            val repeater = mockk<Repeater>(relaxed = true)
            DataAccessSecurity.approvalHandler = denyingHandler
            dataAccessApprovalRequired = true
            coEvery { denyingHandler.requestDataAccess(DataAccessType.ORGANIZER, config) } returns false
            try {
                runBlocking {
                    assertEquals("Organizer access denied by Burp Suite", client.callTool(
                        "send_organizer_item_to_repeater", mapOf("organizerId" to 123)
                    ).expectTextContent())
                    verify(exactly = 0) { api.organizer() }
                    verify(exactly = 0) { api.repeater() }
                    verify(exactly = 0) { repeater.sendToRepeater(any<HttpRequest>()) }
                    verify(exactly = 0) { repeater.sendToRepeater(any<HttpRequest>(), any()) }
                    verify(exactly = 0) { api.http() }
                }
            } finally {
                dataAccessApprovalRequired = false
                DataAccessSecurity.approvalHandler = previousHandler
            }
        }

        @Test
        fun `organizer item to intruder uses official ID and exact request with safe explicit tab`() {
            val organizer = mockk<Organizer>()
            val intruder = mockk<Intruder>(relaxed = true)
            val other = organizerItem(80, "https://other.test/?token=OTHER_QUERY_SECRET")
            val selected = organizerItem(
                903,
                "https://shop.test/rest/admin/application-version?token=URL_SECRET",
                status = OrganizerItemStatus.DONE,
                notes = "ORGANIZER_NOTE_SECRET",
                highlight = HighlightColor.CYAN,
                requestText = "GET /rest/admin/application-version?token=HEADER_SECRET HTTP/1.1\r\nAuthorization: AUTH_SECRET\r\nCookie: COOKIE_SECRET\r\n\r\nBODY_SECRET"
            )
            val request = selected.request()
            val annotations = selected.annotations()
            every { api.organizer() } returns organizer
            every { organizer.items() } returns listOf(other, selected)
            every { api.intruder() } returns intruder

            runBlocking {
                val text = client.callTool("send_organizer_item_to_intruder", mapOf(
                    "organizerId" to 903, "tabName" to "MCP Organizer Intruder validation"
                )).expectTextContent()

                assertEquals(
                    "Opened Organizer item 903 in Intruder tab 'MCP Organizer Intruder validation'. No Intruder attack was started and no HTTP request was transmitted.",
                    text
                )
                assertFalse(text.contains("shop.test"))
                assertFalse(text.contains("URL_SECRET"))
                assertFalse(text.contains("HEADER_SECRET"))
                assertFalse(text.contains("AUTH_SECRET"))
                assertFalse(text.contains("COOKIE_SECRET"))
                assertFalse(text.contains("BODY_SECRET"))
                assertFalse(text.contains("ORGANIZER_NOTE_SECRET"))
                verify(exactly = 1) { other.id() }
                verify(exactly = 1) { selected.id() }
                verify(exactly = 2) { selected.request() }
                verify(exactly = 1) { intruder.sendToIntruder(request, "MCP Organizer Intruder validation") }
                assertEquals(OrganizerItemStatus.DONE, selected.status())
                verify(exactly = 0) { annotations.setNotes(any()) }
                verify(exactly = 0) { annotations.setHighlightColor(any()) }
                verify(exactly = 0) { api.http() }
                verify(exactly = 0) { api.repeater() }
                verify(exactly = 0) { api.proxy() }
                verify(exactly = 0) { organizer.sendToOrganizer(any<HttpRequest>()) }
                verify(exactly = 0) { organizer.sendToOrganizer(any<MontoyaHttpRequestResponse>()) }
            }
        }

        @Test
        fun `organizer item to intruder defaults omitted and blank tab names`() {
            val organizer = mockk<Organizer>()
            val intruder = mockk<Intruder>(relaxed = true)
            val item = organizerItem(78, "https://shop.test/")
            val request = item.request()
            every { api.organizer() } returns organizer
            every { organizer.items() } returns listOf(item)
            every { api.intruder() } returns intruder

            runBlocking {
                val omitted = client.callTool("send_organizer_item_to_intruder", mapOf(
                    "organizerId" to 78
                )).expectTextContent()
                val blank = client.callTool("send_organizer_item_to_intruder", mapOf(
                    "organizerId" to 78, "tabName" to "  "
                )).expectTextContent()

                assertTrue(omitted.contains("Organizer 78"))
                assertTrue(blank.contains("Organizer 78"))
                assertTrue(omitted.contains("No Intruder attack was started"))
                assertTrue(omitted.contains("no HTTP request was transmitted"))
                verify(exactly = 2) { intruder.sendToIntruder(request, "Organizer 78") }
                verify(exactly = 0) { api.http() }
                verify(exactly = 0) { api.repeater() }
                verify(exactly = 0) { api.proxy() }
                verify(exactly = 0) { organizer.sendToOrganizer(any<HttpRequest>()) }
                verify(exactly = 0) { organizer.sendToOrganizer(any<MontoyaHttpRequestResponse>()) }
            }
        }

        @Test
        fun `organizer item to intruder unknown ID and missing request do not open Intruder`() {
            val organizer = mockk<Organizer>()
            val item = mockk<OrganizerItem>()
            every { item.id() } returns 99
            every { item.request() } returns null
            every { api.organizer() } returns organizer
            every { organizer.items() } returns listOf(item)

            runBlocking {
                assertEquals("No Organizer item found with ID 100", client.callTool(
                    "send_organizer_item_to_intruder", mapOf("organizerId" to 100)
                ).expectTextContent())
                assertTrue(client.callTool("send_organizer_item_to_intruder", mapOf(
                    "organizerId" to 99
                )).expectTextContent().contains("has no request to open in Intruder"))
                verify(exactly = 0) { api.intruder() }
                verify(exactly = 0) { api.http() }
                verify(exactly = 0) { api.repeater() }
                verify(exactly = 0) { api.proxy() }
                verify(exactly = 0) { organizer.sendToOrganizer(any<HttpRequest>()) }
                verify(exactly = 0) { organizer.sendToOrganizer(any<MontoyaHttpRequestResponse>()) }
            }
        }

        @Test
        fun `organizer item to intruder access denial prevents lookup and opening`() {
            val previousHandler = DataAccessSecurity.approvalHandler
            val denyingHandler = mockk<DataAccessApprovalHandler>()
            DataAccessSecurity.approvalHandler = denyingHandler
            dataAccessApprovalRequired = true
            coEvery { denyingHandler.requestDataAccess(DataAccessType.ORGANIZER, config) } returns false
            try {
                runBlocking {
                    assertEquals("Organizer access denied by Burp Suite", client.callTool(
                        "send_organizer_item_to_intruder", mapOf("organizerId" to 123)
                    ).expectTextContent())
                    verify(exactly = 0) { api.organizer() }
                    verify(exactly = 0) { api.intruder() }
                    verify(exactly = 0) { api.http() }
                    verify(exactly = 0) { api.repeater() }
                    verify(exactly = 0) { api.proxy() }
                }
            } finally {
                dataAccessApprovalRequired = false
                DataAccessSecurity.approvalHandler = previousHandler
            }
        }

        @Test
        fun `set organizer item notes appends by official ID and keeps output and logs safe`() {
            val organizer = mockk<Organizer>()
            val logging = mockk<Logging>(relaxed = true)
            val logMessages = mutableListOf<String>()
            val selected = organizerItem(
                509,
                "https://shop.test/path?token=HTTP_SECRET",
                status = OrganizerItemStatus.DONE,
                notes = "Existing note",
                highlight = HighlightColor.CYAN,
                requestText = "GET /path HTTP/1.1\r\nCookie: HTTP_SECRET\r\n\r\nHTTP_BODY_SECRET",
                responseText = "HTTP/1.1 200 OK\r\nAuthorization: HTTP_SECRET\r\n\r\nHTTP_BODY_SECRET"
            )
            val other = organizerItem(510, "https://other.test/", notes = "OTHER_NOTE_SECRET")
            val annotations = selected.annotations()
            val otherAnnotations = other.annotations()
            var selectedNotes = "Existing note"
            every { annotations.notes() } answers { selectedNotes }
            every { annotations.setNotes(any()) } answers { selectedNotes = firstArg() }
            every { api.organizer() } returns organizer
            every { organizer.items() } returns listOf(other, selected)
            every { api.logging() } returns logging
            every { logging.logToOutput(capture(logMessages)) } just runs

            runBlocking {
                val result = client.callTool("set_organizer_item_notes", mapOf(
                    "organizerId" to 509, "notes" to "DISTINCTIVE_NOTE_SECRET"
                )).expectTextContent()

                assertEquals("Notes appended on Organizer item 509", result)
                assertEquals("Existing note\nDISTINCTIVE_NOTE_SECRET", selectedNotes)
                assertEquals("OTHER_NOTE_SECRET", otherAnnotations.notes())
                assertEquals(OrganizerItemStatus.DONE, selected.status())
                assertEquals(HighlightColor.CYAN, annotations.highlightColor())
                assertFalse(result.contains("DISTINCTIVE_NOTE_SECRET"))
                assertFalse(result.contains("HTTP_SECRET"))
                assertFalse(result.contains("HTTP_BODY_SECRET"))
                assertFalse(logMessages.joinToString(" ").contains("DISTINCTIVE_NOTE_SECRET"))
                assertFalse(logMessages.joinToString(" ").contains("HTTP_SECRET"))
                assertFalse(logMessages.joinToString(" ").contains("HTTP_BODY_SECRET"))
                verify(exactly = 1) { other.id() }
                verify(exactly = 1) { selected.id() }
                verify(exactly = 1) { annotations.setNotes("Existing note\nDISTINCTIVE_NOTE_SECRET") }
                verify(exactly = 0) { otherAnnotations.setNotes(any()) }
                verify(exactly = 0) { annotations.setHighlightColor(any()) }
                verify(exactly = 0) { api.http() }
                verify(exactly = 0) { api.repeater() }
                verify(exactly = 0) { api.intruder() }
                verify(exactly = 0) { api.proxy() }
                verify(exactly = 0) { organizer.sendToOrganizer(any<HttpRequest>()) }
                verify(exactly = 0) { organizer.sendToOrganizer(any<MontoyaHttpRequestResponse>()) }
            }
        }

        @Test
        fun `set organizer item notes appends without leading newline and replaces when requested`() {
            val organizer = mockk<Organizer>()
            val empty = organizerItem(611, "https://empty.test/", notes = "")
            val existing = organizerItem(612, "https://existing.test/", notes = "Original note")
            val emptyAnnotations = empty.annotations()
            val existingAnnotations = existing.annotations()
            var emptyNotes = ""
            var existingNotes = "Original note"
            every { emptyAnnotations.notes() } answers { emptyNotes }
            every { emptyAnnotations.setNotes(any()) } answers { emptyNotes = firstArg() }
            every { existingAnnotations.notes() } answers { existingNotes }
            every { existingAnnotations.setNotes(any()) } answers { existingNotes = firstArg() }
            every { api.organizer() } returns organizer
            every { organizer.items() } returns listOf(empty, existing)

            runBlocking {
                val defaultAppend = client.callTool("set_organizer_item_notes", mapOf(
                    "organizerId" to 611, "notes" to "First note"
                )).expectTextContent()
                val replace = client.callTool("set_organizer_item_notes", mapOf(
                    "organizerId" to 612, "notes" to "Replacement note", "append" to false
                )).expectTextContent()

                assertEquals("First note", emptyNotes)
                assertEquals("Replacement note", existingNotes)
                assertEquals("Notes appended on Organizer item 611", defaultAppend)
                assertEquals("Notes replaced on Organizer item 612", replace)
                verify(exactly = 1) { emptyAnnotations.setNotes("First note") }
                verify(exactly = 1) { existingAnnotations.setNotes("Replacement note") }
            }
        }

        @Test
        fun `set organizer item notes rejects blank values and MCP remains usable`() {
            val organizer = mockk<Organizer>()
            val item = organizerItem(701, "https://shop.test/", notes = "Preserve this note")
            val annotations = item.annotations()
            var currentNotes = "Preserve this note"
            every { annotations.notes() } answers { currentNotes }
            every { annotations.setNotes(any()) } answers { currentNotes = firstArg() }
            every { api.organizer() } returns organizer
            every { organizer.items() } returns listOf(item)

            runBlocking {
                for (invalidNotes in listOf("", " \t\r\n")) {
                    val result = client.callTool("set_organizer_item_notes", mapOf(
                        "organizerId" to 701, "notes" to invalidNotes
                    ))
                    assertTrue(result?.isError ?: false)
                    assertTrue(((result?.content?.firstOrNull() as? TextContent)?.text ?: "").contains("must not be blank"))
                }
                assertEquals("Preserve this note", currentNotes)
                verify(exactly = 0) { annotations.setNotes(any()) }

                assertEquals("Notes appended on Organizer item 701", client.callTool(
                    "set_organizer_item_notes", mapOf("organizerId" to 701, "notes" to "MCP still connected")
                ).expectTextContent())
                assertEquals("Preserve this note\nMCP still connected", currentNotes)
            }
        }

        @Test
        fun `set organizer item notes unknown ID leaves other item unchanged`() {
            val organizer = mockk<Organizer>()
            val first = organizerItem(801, "https://first.test/", notes = "First note")
            val second = organizerItem(802, "https://second.test/", notes = "Second note")
            every { api.organizer() } returns organizer
            every { organizer.items() } returns listOf(first, second)

            runBlocking {
                assertEquals("No Organizer item found with ID 2", client.callTool(
                    "set_organizer_item_notes", mapOf("organizerId" to 2, "notes" to "Do not change")
                ).expectTextContent())
                verify(exactly = 1) { first.id() }
                verify(exactly = 1) { second.id() }
                verify(exactly = 0) { first.annotations().setNotes(any()) }
                verify(exactly = 0) { second.annotations().setNotes(any()) }
            }
        }

        @Test
        fun `set organizer item notes access denial prevents lookup and modification`() {
            val previousHandler = DataAccessSecurity.approvalHandler
            val denyingHandler = mockk<DataAccessApprovalHandler>()
            DataAccessSecurity.approvalHandler = denyingHandler
            dataAccessApprovalRequired = true
            coEvery { denyingHandler.requestDataAccess(DataAccessType.ORGANIZER, config) } returns false
            try {
                runBlocking {
                    assertEquals("Organizer access denied by Burp Suite", client.callTool(
                        "set_organizer_item_notes", mapOf("organizerId" to 901, "notes" to "Denied note")
                    ).expectTextContent())
                    verify(exactly = 0) { api.organizer() }
                    verify(exactly = 0) { api.http() }
                    verify(exactly = 0) { api.repeater() }
                    verify(exactly = 0) { api.intruder() }
                    verify(exactly = 0) { api.proxy() }
                }
            } finally {
                dataAccessApprovalRequired = false
                DataAccessSecurity.approvalHandler = previousHandler
            }
        }

        @Test
        fun `get proxy history should paginate properly`() {
            val proxy = mockk<Proxy>()
            val proxyHistory = listOf(
                mockk<ProxyHttpRequestResponse>(),
                mockk<ProxyHttpRequestResponse>(),
                mockk<ProxyHttpRequestResponse>()
            )
            
            every { api.proxy() } returns proxy
            every { proxy.history() } returns proxyHistory
            
            mockkStatic("net.portswigger.mcp.schema.SerializationKt")
            
            every { proxyHistory[0].toSerializableForm() } returns HttpRequestResponse(
                request = "GET /item1 HTTP/1.1",
                response = "HTTP/1.1 200 OK",
                notes = "Item 1 notes"
            )
            every { proxyHistory[1].toSerializableForm() } returns HttpRequestResponse(
                request = "GET /item2 HTTP/1.1",
                response = "HTTP/1.1 200 OK",
                notes = "Item 2 notes"
            )
            every { proxyHistory[2].toSerializableForm() } returns HttpRequestResponse(
                request = "GET /item3 HTTP/1.1",
                response = "HTTP/1.1 200 OK",
                notes = "Item 3 notes"
            )
            
            runBlocking {
                val result1 = client.callTool(
                    "get_proxy_http_history", mapOf(
                        "count" to 2,
                        "offset" to 0
                    )
                )
                
                delay(100)
                val text1 = result1.expectTextContent()
                assertTrue(text1.contains("GET /item1"))
                assertTrue(text1.contains("GET /item2"))
                assertFalse(text1.contains("GET /item3"))
                
                val result2 = client.callTool(
                    "get_proxy_http_history", mapOf(
                        "count" to 2,
                        "offset" to 2
                    )
                )
                
                delay(100)
                val text2 = result2.expectTextContent()
                assertTrue(text2.contains("GET /item3"))
                
                val result3 = client.callTool(
                    "get_proxy_http_history", mapOf(
                        "count" to 2,
                        "offset" to 3
                    )
                )
                
                delay(100)
                assertEquals("Reached end of items", result3.expectTextContent())
            }
        }

        @Test
        fun `get proxy history should return valid size limited JSON`() {
            val proxy = mockk<Proxy>()
            val historyItem = mockk<ProxyHttpRequestResponse>()
            every { api.proxy() } returns proxy
            every { proxy.history() } returns listOf(historyItem)

            mockkStatic("net.portswigger.mcp.schema.SerializationKt")
            every { historyItem.toSerializableForm() } returns HttpRequestResponse(
                request = "GET / HTTP/1.1\r\nX-Long: ${"\\\"😀".repeat(2_000)}",
                response = "HTTP/1.1 200 OK\r\n\r\n${"😀".repeat(3_000)}",
                notes = "keep me"
            )

            runBlocking {
                val text = client.callTool(
                    "get_proxy_http_history", mapOf("count" to 1, "offset" to 0)
                ).expectTextContent()
                val item = Json.parseToJsonElement(text).jsonObject

                assertTrue(text.length <= 5_000)
                assertEquals(setOf("request", "response", "notes"), item.keys)
                assertTrue(item.getValue("request").jsonPrimitive.content.endsWith("... (truncated)"))
                assertTrue(item.getValue("response").jsonPrimitive.content.endsWith("... (truncated)"))
                assertEquals("keep me", item.getValue("notes").jsonPrimitive.content)
            }
        }
    }
    
    @Nested
    inner class CollaboratorToolsTests {
        private val collaborator = mockk<Collaborator>()
        private val collaboratorClient = mockk<CollaboratorClient>()
        private val collaboratorServer = mockk<CollaboratorServer>()

        @BeforeEach
        fun setupCollaborator() {
            mockkStatic(InteractionFilter::class)

            val burpSuite = mockk<burp.api.montoya.burpsuite.BurpSuite>()
            val version = mockk<burp.api.montoya.core.Version>()
            every { api.burpSuite() } returns burpSuite
            every { burpSuite.version() } returns version
            every { version.edition() } returns BurpSuiteEdition.PROFESSIONAL
            every { burpSuite.taskExecutionEngine() } returns mockk(relaxed = true)
            every { burpSuite.exportProjectOptionsAsJson() } returns "{}"
            every { burpSuite.exportUserOptionsAsJson() } returns "{}"
            every { burpSuite.importProjectOptionsFromJson(any()) } just runs
            every { burpSuite.importUserOptionsFromJson(any()) } just runs

            every { api.collaborator() } returns collaborator
            every { collaborator.createClient() } returns collaboratorClient
            every { collaboratorClient.server() } returns collaboratorServer
            every { collaboratorServer.address() } returns "burpcollaborator.net"

            serverManager.stop {}
            serverStarted = false
            serverManager.start(config) { state ->
                if (state is ServerState.Running) serverStarted = true
            }

            runBlocking {
                var attempts = 0
                while (!serverStarted && attempts < 30) {
                    delay(100)
                    attempts++
                }
                if (!serverStarted) throw IllegalStateException("Server failed to start after timeout")
                client.connectToServer("http://127.0.0.1:${testPort}")
            }
        }

        @AfterEach
        fun cleanupCollaborator() {
            unmockkStatic(InteractionFilter::class)
        }

        private fun mockInteraction(
            id: String,
            type: InteractionType,
            clientIp: String = "10.0.0.1",
            clientPort: Int = 54321,
            customData: String? = null,
            dnsDetails: DnsDetails? = null,
            httpDetails: HttpDetails? = null,
            smtpDetails: SmtpDetails? = null
        ): Interaction {
            val interactionId = mockk<InteractionId>()
            every { interactionId.toString() } returns id

            return mockk<Interaction>().also {
                every { it.id() } returns interactionId
                every { it.type() } returns type
                every { it.timeStamp() } returns ZonedDateTime.parse("2025-01-01T12:00:00Z")
                every { it.clientIp() } returns InetAddress.getByName(clientIp)
                every { it.clientPort() } returns clientPort
                every { it.customData() } returns Optional.ofNullable(customData)
                every { it.dnsDetails() } returns Optional.ofNullable(dnsDetails)
                every { it.httpDetails() } returns Optional.ofNullable(httpDetails)
                every { it.smtpDetails() } returns Optional.ofNullable(smtpDetails)
            }
        }

        @Test
        fun `generate payload should return payload and server info`() {
            val payload = mockk<CollaboratorPayload>()
            val payloadId = mockk<InteractionId>()
            every { payload.toString() } returns "abc123.burpcollaborator.net"
            every { payload.id() } returns payloadId
            every { payloadId.toString() } returns "abc123"
            every { collaboratorClient.generatePayload() } returns payload

            runBlocking {
                val result = client.callTool("generate_collaborator_payload", emptyMap())
                delay(100)
                result.expectTextContent(
                    "Payload: abc123.burpcollaborator.net\n" +
                    "Payload ID: abc123\n" +
                    "Collaborator server: burpcollaborator.net"
                )
            }

            verify(exactly = 1) { collaboratorClient.generatePayload() }
        }

        @Test
        fun `generate payload with custom data should pass custom data`() {
            val payload = mockk<CollaboratorPayload>()
            val payloadId = mockk<InteractionId>()
            every { payload.toString() } returns "custom123.burpcollaborator.net"
            every { payload.id() } returns payloadId
            every { payloadId.toString() } returns "custom123"
            every { collaboratorClient.generatePayload(any<String>()) } returns payload

            runBlocking {
                val result = client.callTool(
                    "generate_collaborator_payload", mapOf(
                        "customData" to "mydata"
                    )
                )
                delay(100)
                result.expectTextContent(
                    "Payload: custom123.burpcollaborator.net\n" +
                    "Payload ID: custom123\n" +
                    "Collaborator server: burpcollaborator.net"
                )
            }

            verify(exactly = 1) { collaboratorClient.generatePayload("mydata") }
        }

        @Test
        fun `get interactions should return dns interaction details`() {
            val dnsDetails = mockk<DnsDetails>().also {
                every { it.queryType() } returns DnsQueryType.A
            }
            val interaction = mockInteraction("int-001", InteractionType.DNS, dnsDetails = dnsDetails)
            every { collaboratorClient.getAllInteractions() } returns listOf(interaction)

            runBlocking {
                val result = client.callTool("get_collaborator_interactions", emptyMap())
                delay(100)
                val text = result.expectTextContent()
                assertTrue(text.contains("\"id\":\"int-001\""))
                assertTrue(text.contains("\"type\":\"DNS\""))
                assertTrue(text.contains("\"queryType\":\"A\""))
                assertTrue(text.contains("\"clientIp\":\"10.0.0.1\""))
            }

            verify(exactly = 1) { collaboratorClient.getAllInteractions() }
        }

        @Test
        fun `get interactions should return http interaction details`() {
            val mockRequest = mockk<burp.api.montoya.http.message.requests.HttpRequest>()
            every { mockRequest.toString() } returns "GET / HTTP/1.1"
            val mockResponse = mockk<burp.api.montoya.http.message.responses.HttpResponse>()
            every { mockResponse.toString() } returns "HTTP/1.1 200 OK"
            val mockRequestResponse = mockk<burp.api.montoya.http.message.HttpRequestResponse>()
            every { mockRequestResponse.request() } returns mockRequest
            every { mockRequestResponse.response() } returns mockResponse

            val httpDetails = mockk<HttpDetails>().also {
                every { it.protocol() } returns HttpProtocol.HTTP
                every { it.requestResponse() } returns mockRequestResponse
            }
            val interaction = mockInteraction("int-002", InteractionType.HTTP, httpDetails = httpDetails)
            every { collaboratorClient.getAllInteractions() } returns listOf(interaction)

            runBlocking {
                val result = client.callTool("get_collaborator_interactions", emptyMap())
                delay(100)
                val text = result.expectTextContent()
                assertTrue(text.contains("\"type\":\"HTTP\""))
                assertTrue(text.contains("\"protocol\":\"HTTP\""))
                assertTrue(text.contains("GET / HTTP/1.1"))
                assertTrue(text.contains("HTTP/1.1 200 OK"))
            }

            verify(exactly = 1) { collaboratorClient.getAllInteractions() }
        }

        @Test
        fun `get interactions should return smtp interaction details`() {
            val smtpDetails = mockk<SmtpDetails>().also {
                every { it.protocol() } returns SmtpProtocol.SMTP
                every { it.conversation() } returns "EHLO test\r\n250 OK"
            }
            val interaction = mockInteraction("int-003", InteractionType.SMTP, smtpDetails = smtpDetails)
            every { collaboratorClient.getAllInteractions() } returns listOf(interaction)

            runBlocking {
                val result = client.callTool("get_collaborator_interactions", emptyMap())
                delay(100)
                val text = result.expectTextContent()
                assertTrue(text.contains("\"type\":\"SMTP\""))
                assertTrue(text.contains("\"protocol\":\"SMTP\""))
                assertTrue(text.contains("EHLO test"))
            }

            verify(exactly = 1) { collaboratorClient.getAllInteractions() }
        }

        @Test
        fun `get interactions with payloadId should use filter`() {
            val mockFilter = mockk<InteractionFilter>()
            every { InteractionFilter.interactionIdFilter("abc123") } returns mockFilter
            every { collaboratorClient.getInteractions(mockFilter) } returns emptyList()

            runBlocking {
                val result = client.callTool(
                    "get_collaborator_interactions", mapOf(
                        "payloadId" to "abc123"
                    )
                )
                delay(100)
                result.expectTextContent("No interactions detected")
            }

            verify(exactly = 1) { collaboratorClient.getInteractions(mockFilter) }
        }

        @Test
        fun `get interactions should return no interactions message when empty`() {
            every { collaboratorClient.getAllInteractions() } returns emptyList()

            runBlocking {
                val result = client.callTool("get_collaborator_interactions", emptyMap())
                delay(100)
                result.expectTextContent("No interactions detected")
            }
        }
    }

    @Test
    fun `tool name conversion should work properly`() {
        assertEquals("send_http1_request", "SendHttp1Request".toLowerSnakeCase())
        assertEquals("test_case_conversion", "TestCaseConversion".toLowerSnakeCase())
        assertEquals("multiple_upper_case_letters", "MultipleUpperCaseLetters".toLowerSnakeCase())
    }
    
    @Test
    fun `edition specific tools should only register in professional edition`() {
        val burpSuite = mockk<burp.api.montoya.burpsuite.BurpSuite>()
        val version = mockk<burp.api.montoya.core.Version>()
        
        every { api.burpSuite() } returns burpSuite
        every { burpSuite.version() } returns version
        
        every { version.edition() } returns BurpSuiteEdition.COMMUNITY_EDITION
        runBlocking {
            val tools = client.listTools()
            assertFalse(tools.any { it.name == "get_scanner_issues" })
            assertFalse(tools.any { it.name == "generate_collaborator_payload" })
            assertFalse(tools.any { it.name == "get_collaborator_interactions" })
        }

        every { version.edition() } returns BurpSuiteEdition.PROFESSIONAL

        serverManager.stop {}
        serverStarted = false
        serverManager.start(config) { state ->
            if (state is ServerState.Running) serverStarted = true
        }

        runBlocking {
            var attempts = 0
            while (!serverStarted && attempts < 30) {
                delay(100)
                attempts++
            }
            if (!serverStarted) throw IllegalStateException("Server failed to start after timeout")

            client.connectToServer("http://127.0.0.1:${testPort}")

            val tools = client.listTools()
            assertTrue(tools.any { it.name == "get_scanner_issues" })
            assertTrue(tools.any { it.name == "generate_collaborator_payload" })
            assertTrue(tools.any { it.name == "get_collaborator_interactions" })
        }
    }
}
