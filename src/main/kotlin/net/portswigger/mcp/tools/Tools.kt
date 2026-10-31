package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.burpsuite.TaskExecutionEngine.TaskExecutionEngineState.PAUSED
import burp.api.montoya.burpsuite.TaskExecutionEngine.TaskExecutionEngineState.RUNNING
import burp.api.montoya.collaborator.InteractionFilter
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.http.HttpMode
import burp.api.montoya.http.HttpService
import burp.api.montoya.core.HighlightColor
import burp.api.montoya.http.message.HttpHeader
import burp.api.montoya.http.message.HttpRequestResponse as MontoyaHttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.organizer.OrganizerItem
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.schema.encodeHistoryItem
import net.portswigger.mcp.schema.toSerializableForm
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import net.portswigger.mcp.security.HttpRequestSecurity
import net.portswigger.mcp.security.filterConfigCredentials
import java.awt.KeyboardFocusManager
import java.util.regex.Pattern
import javax.swing.JTextArea

private suspend fun checkDataAccessOrDeny(
    accessType: DataAccessType, config: McpConfig, api: MontoyaApi, logMessage: String
): Boolean {
    val allowed = DataAccessSecurity.checkDataAccessPermission(accessType, config)
    if (!allowed) {
        api.logging().logToOutput("MCP $logMessage access denied")
        return false
    }
    api.logging().logToOutput("MCP $logMessage access granted")
    return true
}

private fun findProxyHistoryItem(api: MontoyaApi, historyId: Int): ProxyHttpRequestResponse? =
    api.proxy().history().firstOrNull { it.id() == historyId }

private fun findOrganizerItem(api: MontoyaApi, organizerId: Int): OrganizerItem? =
    api.organizer().items().firstOrNull { it.id() == organizerId }

private fun buildHttp2HeaderList(
    pseudoHeaders: Map<String, String>, headers: Map<String, String>
): List<HttpHeader> {
    val orderedPseudoHeaderNames = listOf(":scheme", ":method", ":path", ":authority")

    val fixedPseudoHeaders = LinkedHashMap<String, String>().apply {
        orderedPseudoHeaderNames.forEach { name ->
            val value = pseudoHeaders[name.removePrefix(":")] ?: pseudoHeaders[name]
            if (value != null) {
                put(name, value)
            }
        }

        pseudoHeaders.forEach { (key, value) ->
            val properKey = if (key.startsWith(":")) key else ":$key"
            if (!containsKey(properKey)) {
                put(properKey, value)
            }
        }
    }

    return (fixedPseudoHeaders + headers).map { HttpHeader.httpHeader(it.key.lowercase(), it.value) }
}

/**
 * Normalizes HTTP request line endings from MCP clients.
 *
 * MCP clients (e.g. Claude Code) often emit `\r\n` as the 4-character literal
 * sequence backslash-r-backslash-n in JSON tool parameters rather than actual
 * CR (0x0D) + LF (0x0A) bytes. The resulting text parses as a single line,
 * which strict servers (e.g. Apache-Coyote) reject with 400 Bad Request and
 * which Burp/Montoya may "repair" by injecting headers after the body
 * separator.
 *
 * Normalization is applied only to the request prelude (request line and
 * headers, up to and including the first blank line). The body is preserved
 * verbatim so that legitimate escape sequences in bodies — e.g. `\n` inside a
 * JSON string literal — and binary payloads remain byte-exact. If no blank
 * line is present, the entire content is treated as prelude.
 */
internal fun normalizeHttpContent(content: String): String {
    val preludeEnd = findPreludeEnd(content) ?: return normalizePrelude(content)
    return normalizePrelude(content.substring(0, preludeEnd)) + content.substring(preludeEnd)
}

private val BLANK_LINE_MARKERS = listOf(
    "\r\n\r\n",         // actual CRLF blank line
    "\n\n",              // actual LF blank line
    "\\r\\n\\r\\n",     // literal CRLF blank line
    "\\n\\n",            // literal LF blank line
)

private fun findPreludeEnd(content: String): Int? {
    var bestStart = -1
    var bestLen = 0
    for (marker in BLANK_LINE_MARKERS) {
        val idx = content.indexOf(marker)
        if (idx >= 0 && (bestStart < 0 || idx < bestStart)) {
            bestStart = idx
            bestLen = marker.length
        }
    }
    return if (bestStart < 0) null else bestStart + bestLen
}

private fun normalizePrelude(prelude: String): String = prelude
    .replace("\\r\\n", "\n")   // Literal \r\n escape sequences → LF
    .replace("\\n", "\n")      // Remaining literal \n → LF
    .replace("\\r", "")        // Remaining literal \r → remove
    .replace("\r", "")          // Actual CR → remove
    .replace("\n", "\r\n")      // All LF → proper CRLF

fun Server.registerTools(api: MontoyaApi, config: McpConfig) {

    mcpTool<SendHttp1Request>("Issues an HTTP/1.1 request and returns the response.") {
        val allowed = HttpRequestSecurity.checkHttpRequestPermission(targetHostname, targetPort, config, content, api)
        if (!allowed) {
            api.logging().logToOutput("MCP HTTP request denied: $targetHostname:$targetPort")
            return@mcpTool "Send HTTP request denied by Burp Suite"
        }

        api.logging().logToOutput("MCP HTTP/1.1 request: $targetHostname:$targetPort")

        val fixedContent = normalizeHttpContent(content)

        val request = HttpRequest.httpRequest(toMontoyaService(), fixedContent)
        currentCoroutineContext().ensureActive()
        val response = api.http().sendRequest(request)

        response?.toString() ?: "<no response>"
    }

    mcpTool<SendHttp2Request>("Issues an HTTP/2 request and returns the response. Do NOT pass headers to the body parameter.") {
        val http2RequestDisplay = buildString {
            pseudoHeaders.forEach { (key, value) ->
                val headerName = if (key.startsWith(":")) key else ":$key"
                appendLine("$headerName: $value")
            }
            headers.forEach { (key, value) ->
                appendLine("$key: $value")
            }
            if (requestBody.isNotBlank()) {
                appendLine()
                append(requestBody)
            }
        }

        val allowed = HttpRequestSecurity.checkHttpRequestPermission(targetHostname, targetPort, config, http2RequestDisplay, api)
        if (!allowed) {
            api.logging().logToOutput("MCP HTTP request denied: $targetHostname:$targetPort")
            return@mcpTool "Send HTTP request denied by Burp Suite"
        }

        api.logging().logToOutput("MCP HTTP/2 request: $targetHostname:$targetPort")

        val headerList = buildHttp2HeaderList(pseudoHeaders, headers)

        val request = HttpRequest.http2Request(toMontoyaService(), headerList, requestBody)
        currentCoroutineContext().ensureActive()
        val response = api.http().sendRequest(request, HttpMode.HTTP_2)

        response?.toString() ?: "<no response>"
    }

    mcpUnitTool<CreateRepeaterTab>("Creates an HTTP/1.1 Repeater tab with the specified raw HTTP request and optional tab name. Make sure to use carriage returns appropriately. Prefer create_repeater_tab_http2 for modern web targets that speak HTTP/2.") {
        val fixedContent = normalizeHttpContent(content)
        val request = HttpRequest.httpRequest(toMontoyaService(), fixedContent)
        api.repeater().sendToRepeater(request, tabName)
    }

    mcpUnitTool<CreateRepeaterTabHttp2>("Creates an HTTP/2 Repeater tab with the specified HTTP/2 request and optional tab name. Use this by default for modern web targets. Do NOT pass headers to the body parameter.") {
        val headerList = buildHttp2HeaderList(pseudoHeaders, headers)
        val request = HttpRequest.http2Request(toMontoyaService(), headerList, requestBody)
        api.repeater().sendToRepeater(request, tabName)
    }

    mcpUnitTool<SendToIntruder>("Sends an HTTP request to Intruder with the specified HTTP request and optional tab name. Make sure to use carriage returns appropriately.") {
        val fixedContent = normalizeHttpContent(content)
        val request = HttpRequest.httpRequest(toMontoyaService(), fixedContent)
        api.intruder().sendToIntruder(request, tabName)
    }

    mcpTool<UrlEncode>("URL encodes the input string") {
        api.utilities().urlUtils().encode(content)
    }

    mcpTool<UrlDecode>("URL decodes the input string") {
        api.utilities().urlUtils().decode(content)
    }

    mcpTool<Base64Encode>("Base64 encodes the input string") {
        api.utilities().base64Utils().encodeToString(content)
    }

    mcpTool<Base64Decode>("Base64 decodes the input string") {
        api.utilities().base64Utils().decode(content).toString()
    }

    mcpTool<GenerateRandomString>("Generates a random string of specified length and character set") {
        api.utilities().randomUtils().randomString(length, characterSet)
    }

    mcpTool(
        "output_project_options",
        "Outputs current project-level configuration in JSON format. You can use this to determine the schema for available config options."
    ) {
        val json = api.burpSuite().exportProjectOptionsAsJson()
        if (config.filterConfigCredentials) {
            filterConfigCredentials(json)
        } else {
            json
        }
    }

    mcpTool(
        "output_user_options",
        "Outputs current user-level configuration in JSON format. You can use this to determine the schema for available config options."
    ) {
        val json = api.burpSuite().exportUserOptionsAsJson()
        if (config.filterConfigCredentials) {
            filterConfigCredentials(json)
        } else {
            json
        }
    }

    val toolingDisabledMessage =
        "User has disabled configuration editing. They can enable it in the MCP tab in Burp by selecting 'Enable tools that can edit your config'"

    mcpTool<SetProjectOptions>("Sets project-level configuration in JSON format. This will be merged with existing configuration. Make sure to export before doing this, so you know what the schema is. Make sure the JSON has a top level 'user_options' object!") {
        if (config.configEditingTooling) {
            api.logging().logToOutput("Setting project-level configuration: $json")
            api.burpSuite().importProjectOptionsFromJson(json)

            "Project configuration has been applied"
        } else {
            toolingDisabledMessage
        }
    }


    mcpTool<SetUserOptions>("Sets user-level configuration in JSON format. This will be merged with existing configuration. Make sure to export before doing this, so you know what the schema is. Make sure the JSON has a top level 'project_options' object!") {
        if (config.configEditingTooling) {
            api.logging().logToOutput("Setting user-level configuration: $json")
            api.burpSuite().importUserOptionsFromJson(json)

            "User configuration has been applied"
        } else {
            toolingDisabledMessage
        }
    }

    if (api.burpSuite().version().edition() == BurpSuiteEdition.PROFESSIONAL) {
        mcpPaginatedTool<GetScannerIssues>("Displays information about issues identified by the scanner") {
            api.siteMap().issues().asSequence().map { Json.encodeToString(it.toSerializableForm()) }
        }

        val collaboratorClient by lazy { api.collaborator().createClient() }

        mcpTool<GenerateCollaboratorPayload>(
            "Generates a Burp Collaborator payload URL for out-of-band (OOB) testing. " +
            "Inject this payload into requests to detect server-side interactions (DNS lookups, HTTP requests, SMTP). " +
            "Use get_collaborator_interactions with the returned payloadId to check for interactions."
        ) {
            api.logging().logToOutput("MCP generating Collaborator payload${customData?.let { " with custom data" } ?: ""}")

            val payload = if (customData != null) {
                collaboratorClient.generatePayload(customData)
            } else {
                collaboratorClient.generatePayload()
            }

            val server = collaboratorClient.server()
            "Payload: $payload\nPayload ID: ${payload.id()}\nCollaborator server: ${server.address()}"
        }

        mcpTool<GetCollaboratorInteractions>(
            "Polls Burp Collaborator for out-of-band interactions (DNS, HTTP, SMTP). " +
            "Optionally filter by payloadId from generate_collaborator_payload. " +
            "Returns interaction details including type, timestamp, client IP, and protocol-specific data."
        ) {
            api.logging().logToOutput("MCP polling Collaborator interactions${payloadId?.let { " for payload: $it" } ?: ""}")

            val interactions = if (payloadId != null) {
                collaboratorClient.getInteractions(InteractionFilter.interactionIdFilter(payloadId))
            } else {
                collaboratorClient.getAllInteractions()
            }

            if (interactions.isEmpty()) {
                "No interactions detected"
            } else {
                interactions.joinToString("\n\n") {
                    Json.encodeToString(it.toSerializableForm())
                }
            }
        }
    }

    mcpPaginatedTool<GetProxyHttpHistory>("Displays items within the proxy HTTP history") {
        val allowed = checkDataAccessOrDeny(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("HTTP history access denied by Burp Suite")
        }

        api.proxy().history().asSequence().map { encodeHistoryItem(it.toSerializableForm()) }
    }

    mcpPaginatedTool<GetProxyHttpHistoryRegex>("Displays items matching a specified regex within the proxy HTTP history") {
        val allowed = checkDataAccessOrDeny(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("HTTP history access denied by Burp Suite")
        }

        val compiledRegex = Pattern.compile(regex)
        api.proxy().history { it.contains(compiledRegex) }.asSequence()
            .map { encodeHistoryItem(it.toSerializableForm()) }
    }

    mcpPaginatedTool<GetProxyHttpHistorySummary>("Returns compact summaries of Proxy HTTP history items without request or response bodies") {
        val allowed = checkDataAccessOrDeny(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("HTTP history access denied by Burp Suite")
        }

        val compiledRegex = regex?.let(Pattern::compile)
        api.proxy().history().asSequence()
            .filter { compiledRegex == null || it.contains(compiledRegex) }
            .map { item ->
                val request = item.request()
                val annotations = item.annotations()
                Json.encodeToString(
                    ProxyHttpHistorySummary(
                        historyId = item.id(),
                        timestamp = item.time().toString(),
                        method = request?.method(),
                        url = request?.url(),
                        responseStatusCode = item.response()?.statusCode()?.toInt(),
                        notes = annotations.notes(),
                        edited = item.edited(),
                        highlightColor = if (annotations.hasHighlightColor()) annotations.highlightColor().name else null
                    )
                )
            }
    }

    mcpTool<GetProxyHttpHistoryItem>("Returns a size-limited Proxy HTTP history item by its Montoya history ID. Large request or response fields may be truncated.") {
        val allowed = checkDataAccessOrDeny(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")
        if (!allowed) {
            return@mcpTool "HTTP history access denied by Burp Suite"
        }

        val item = findProxyHistoryItem(api, historyId)
            ?: return@mcpTool "No Proxy HTTP history item found with ID $historyId"
        encodeHistoryItem(item.toSerializableForm())
    }

    mcpTool<SendProxyHistoryToRepeater>(
        "Opens an existing Proxy HTTP history request in Burp Repeater using its Montoya history ID. The request is not transmitted until the user or another explicitly authorized tool sends it."
    ) {
        val allowed = checkDataAccessOrDeny(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")
        if (!allowed) {
            return@mcpTool "HTTP history access denied by Burp Suite"
        }

        val item = findProxyHistoryItem(api, historyId)
            ?: return@mcpTool "No Proxy HTTP history item found with ID $historyId"
        val request = item.finalRequest() ?: item.request()
            ?: return@mcpTool "Proxy HTTP history item $historyId has no request to open in Repeater"
        val resolvedTabName = tabName?.takeIf { it.isNotBlank() } ?: "History $historyId"

        api.repeater().sendToRepeater(request, resolvedTabName)
        api.logging().logToOutput("Opened Proxy history item $historyId in Repeater; request was not sent")

        "Opened Proxy history item $historyId (${request.method()} ${request.httpService().host()}${request.path()}) in Repeater tab '$resolvedTabName'. Request was not sent."
    }

    mcpTool<SendProxyHistoryToIntruder>(
        "Opens an existing Proxy HTTP history request in Burp Intruder using its official Montoya history ID. The final processed request is preferred. The tool does not start an Intruder attack or transmit the request."
    ) {
        val allowed = checkDataAccessOrDeny(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")
        if (!allowed) {
            return@mcpTool "HTTP history access denied by Burp Suite"
        }

        val item = findProxyHistoryItem(api, historyId)
            ?: return@mcpTool "No Proxy HTTP history item found with ID $historyId"
        val request = item.finalRequest() ?: item.request()
            ?: return@mcpTool "Proxy HTTP history item $historyId has no request to open in Intruder"
        val resolvedTabName = tabName?.takeIf { it.isNotBlank() } ?: "History $historyId"

        api.intruder().sendToIntruder(request, resolvedTabName)
        api.logging().logToOutput("Opened Proxy history item $historyId in Intruder; attack not started and request not sent")

        "Opened Proxy history item $historyId in Intruder tab '$resolvedTabName'. No Intruder attack was started and no HTTP request was transmitted."
    }

    mcpTool<SetProxyHistoryNotes>(
        "Appends or replaces notes on one Proxy HTTP history item selected by its Montoya history ID. Appending is the default to preserve existing notes."
    ) {
        if (notes.isBlank()) {
            throw IllegalArgumentException("Notes must not be blank")
        }

        val allowed = checkDataAccessOrDeny(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")
        if (!allowed) {
            return@mcpTool "HTTP history access denied by Burp Suite"
        }

        val item = findProxyHistoryItem(api, historyId)
            ?: return@mcpTool "No Proxy HTTP history item found with ID $historyId"
        val annotations = item.annotations()
        val operation = if (append) {
            val existingNotes = annotations.notes()
            annotations.setNotes(if (existingNotes.isNotBlank()) "$existingNotes\n$notes" else notes)
            "appended"
        } else {
            annotations.setNotes(notes)
            "replaced"
        }

        api.logging().logToOutput("$operation notes on Proxy history item $historyId")
        "Notes $operation on Proxy history item $historyId"
    }

    mcpTool<SetProxyHistoryHighlight>(
        "Sets or clears the highlight color of one Proxy HTTP history item selected by its Montoya history ID. Use an official Montoya HighlightColor value; NONE clears the highlight."
    ) {
        val allowed = checkDataAccessOrDeny(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")
        if (!allowed) {
            return@mcpTool "HTTP history access denied by Burp Suite"
        }

        val selectedColor = HighlightColor.values().firstOrNull {
            it.name.equals(color.trim(), ignoreCase = true)
        } ?: throw IllegalArgumentException("Unsupported highlight color. Use an official Montoya HighlightColor value.")

        val item = findProxyHistoryItem(api, historyId)
            ?: return@mcpTool "No Proxy HTTP history item found with ID $historyId"
        item.annotations().setHighlightColor(selectedColor)

        val result = if (selectedColor == HighlightColor.NONE) "cleared" else "set to ${selectedColor.name}"
        api.logging().logToOutput("Proxy history item $historyId highlight color ${selectedColor.name}")
        "Highlight $result on Proxy history item $historyId"
    }

    mcpTool<SendProxyHistoryToOrganizer>(
        "Saves one Proxy HTTP history item to Burp Organizer using its Montoya history ID. The final processed request is preferred, and the recorded response and annotations are preserved when available. No HTTP request is transmitted."
    ) {
        val historyAllowed = checkDataAccessOrDeny(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")
        if (!historyAllowed) {
            return@mcpTool "HTTP history access denied by Burp Suite"
        }

        val organizerAllowed = checkDataAccessOrDeny(DataAccessType.ORGANIZER, config, api, "Organizer")
        if (!organizerAllowed) {
            return@mcpTool "Organizer access denied by Burp Suite"
        }

        val item = findProxyHistoryItem(api, historyId)
            ?: return@mcpTool "No Proxy HTTP history item found with ID $historyId"
        val request = item.finalRequest() ?: item.request()
            ?: return@mcpTool "Proxy HTTP history item $historyId has no request to add to Organizer"
        val response = item.response()
        val annotationsPreserved = response != null

        if (response != null) {
            val requestResponse = MontoyaHttpRequestResponse.httpRequestResponse(request, response, item.annotations())
            api.organizer().sendToOrganizer(requestResponse)
        } else {
            api.organizer().sendToOrganizer(request)
        }

        api.logging().logToOutput(
            "Added Proxy history item $historyId to Organizer (response included: ${response != null}, annotations preserved: $annotationsPreserved)"
        )
        "Organizer item created from Proxy history ID $historyId (response included: ${response != null}, annotations preserved: $annotationsPreserved). No HTTP request was transmitted."
    }

    mcpPaginatedTool<GetOrganizerItems>("Displays items within the Organizer tab") {
        val allowed = checkDataAccessOrDeny(DataAccessType.ORGANIZER, config, api, "Organizer")
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("Organizer access denied by Burp Suite")
        }

        api.organizer().items().asSequence().map { encodeHistoryItem(it.toSerializableForm()) }
    }

    mcpPaginatedTool<GetOrganizerItemsRegex>("Displays items matching a specified regex within the Organizer tab") {
        val allowed = checkDataAccessOrDeny(DataAccessType.ORGANIZER, config, api, "Organizer")
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("Organizer access denied by Burp Suite")
        }

        val compiledRegex = Pattern.compile(regex)
        api.organizer().items { it.contains(compiledRegex) }.asSequence()
            .map { encodeHistoryItem(it.toSerializableForm()) }
    }

    mcpPaginatedTool<GetOrganizerItemsSummary>(
        "Returns compact summaries of Burp Organizer items, optionally filtered by regex. Results include official Organizer IDs and metadata but exclude complete requests and responses."
    ) {
        val allowed = checkDataAccessOrDeny(DataAccessType.ORGANIZER, config, api, "Organizer")
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("Organizer access denied by Burp Suite")
        }

        val compiledRegex = regex?.takeUnless { it.isBlank() }?.let(Pattern::compile)
        val items = if (compiledRegex == null) {
            api.organizer().items()
        } else {
            api.organizer().items { it.contains(compiledRegex) }
        }

        items.asSequence().map { item ->
            val request = item.request()
            val hasResponse = item.hasResponse()
            val responseStatusCode = if (hasResponse) item.response()?.statusCode()?.toInt() else null
            val annotations = item.annotations()
            Json.encodeToString(
                OrganizerItemSummary(
                    organizerId = item.id(),
                    organizerStatus = item.status().name,
                    method = request?.method(),
                    url = request?.url(),
                    responseStatusCode = responseStatusCode,
                    notes = annotations.notes(),
                    highlightColor = if (annotations.hasHighlightColor()) annotations.highlightColor().name else null,
                    hasResponse = hasResponse
                )
            )
        }
    }

    mcpTool<GetOrganizerItem>(
        "Returns one size-limited Burp Organizer item by its official Montoya Organizer ID. Large request or response fields may be truncated."
    ) {
        val allowed = checkDataAccessOrDeny(DataAccessType.ORGANIZER, config, api, "Organizer")
        if (!allowed) {
            return@mcpTool "Organizer access denied by Burp Suite"
        }

        val item = findOrganizerItem(api, organizerId)
            ?: return@mcpTool "No Organizer item found with ID $organizerId"
        encodeHistoryItem(item.toSerializableForm())
    }

    mcpTool<SetOrganizerItemNotes>(
        "Appends or replaces notes on one Burp Organizer item selected by its official Montoya Organizer ID. Appending is the default."
    ) {
        if (notes.isBlank()) {
            throw IllegalArgumentException("Notes must not be blank")
        }

        val allowed = checkDataAccessOrDeny(DataAccessType.ORGANIZER, config, api, "Organizer")
        if (!allowed) {
            return@mcpTool "Organizer access denied by Burp Suite"
        }

        val item = findOrganizerItem(api, organizerId)
            ?: return@mcpTool "No Organizer item found with ID $organizerId"
        val annotations = item.annotations()
        val operation = if (append) {
            val existingNotes = annotations.notes()
            annotations.setNotes(if (existingNotes.isNotBlank()) "$existingNotes\n$notes" else notes)
            "appended"
        } else {
            annotations.setNotes(notes)
            "replaced"
        }

        api.logging().logToOutput("$operation notes on Organizer item $organizerId")
        "Notes $operation on Organizer item $organizerId"
    }

    mcpTool<SendOrganizerItemToRepeater>(
        "Opens the request from one Burp Organizer item in Repeater using its official Montoya Organizer ID. The request is opened for inspection and is not transmitted."
    ) {
        val allowed = checkDataAccessOrDeny(DataAccessType.ORGANIZER, config, api, "Organizer")
        if (!allowed) {
            return@mcpTool "Organizer access denied by Burp Suite"
        }

        val item = findOrganizerItem(api, organizerId)
            ?: return@mcpTool "No Organizer item found with ID $organizerId"
        val request = item.request()
            ?: return@mcpTool "Organizer item $organizerId has no request to open in Repeater"
        val resolvedTabName = tabName?.takeIf { it.isNotBlank() } ?: "Organizer $organizerId"

        api.repeater().sendToRepeater(request, resolvedTabName)
        api.logging().logToOutput("Opened Organizer item $organizerId in Repeater; request was not transmitted")

        "Opened Organizer item $organizerId in Repeater tab '$resolvedTabName'. Request was not transmitted."
    }

    mcpTool<SendOrganizerItemToIntruder>(
        "Opens the request from one Burp Organizer item in Intruder using its official Montoya Organizer ID. The tool does not start an Intruder attack, transmit the request, or modify the Organizer item."
    ) {
        val allowed = checkDataAccessOrDeny(DataAccessType.ORGANIZER, config, api, "Organizer")
        if (!allowed) {
            return@mcpTool "Organizer access denied by Burp Suite"
        }

        val item = findOrganizerItem(api, organizerId)
            ?: return@mcpTool "No Organizer item found with ID $organizerId"
        val request = item.request()
            ?: return@mcpTool "Organizer item $organizerId has no request to open in Intruder"
        val resolvedTabName = tabName?.takeIf { it.isNotBlank() } ?: "Organizer $organizerId"

        api.intruder().sendToIntruder(request, resolvedTabName)
        api.logging().logToOutput("Opened Organizer item $organizerId in Intruder; attack not started and request not transmitted")

        "Opened Organizer item $organizerId in Intruder tab '$resolvedTabName'. No Intruder attack was started and no HTTP request was transmitted."
    }

    mcpPaginatedTool<GetProxyWebsocketHistory>("Displays items within the proxy WebSocket history") {
        val allowed = checkDataAccessOrDeny(DataAccessType.WEBSOCKET_HISTORY, config, api, "WebSocket history")
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("WebSocket history access denied by Burp Suite")
        }

        api.proxy().webSocketHistory().asSequence()
            .map { encodeHistoryItem(it.toSerializableForm()) }
    }

    mcpPaginatedTool<GetProxyWebsocketHistoryRegex>("Displays items matching a specified regex within the proxy WebSocket history") {
        val allowed = checkDataAccessOrDeny(DataAccessType.WEBSOCKET_HISTORY, config, api, "WebSocket history")
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("WebSocket history access denied by Burp Suite")
        }

        val compiledRegex = Pattern.compile(regex)
        api.proxy().webSocketHistory { it.contains(compiledRegex) }.asSequence()
            .map { encodeHistoryItem(it.toSerializableForm()) }
    }

    mcpTool<SetTaskExecutionEngineState>("Sets the state of Burp's task execution engine (paused or unpaused)") {
        api.burpSuite().taskExecutionEngine().state = if (running) RUNNING else PAUSED

        "Task execution engine is now ${if (running) "running" else "paused"}"
    }

    mcpTool<SetProxyInterceptState>("Enables or disables Burp Proxy Intercept") {
        if (intercepting) {
            api.proxy().enableIntercept()
        } else {
            api.proxy().disableIntercept()
        }

        "Intercept has been ${if (intercepting) "enabled" else "disabled"}"
    }

    mcpTool("get_active_editor_contents", "Outputs the contents of the user's active message editor") {
        getActiveEditor(api)?.text ?: "<No active editor>"
    }

    mcpTool<SetActiveEditorContents>("Sets the content of the user's active message editor") {
        val editor = getActiveEditor(api) ?: return@mcpTool "<No active editor>"

        if (!editor.isEditable) {
            return@mcpTool "<Current editor is not editable>"
        }

        editor.text = text

        "Editor text has been set"
    }
}

fun getActiveEditor(api: MontoyaApi): JTextArea? {
    val frame = api.userInterface().swingUtils().suiteFrame()

    val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    val permanentFocusOwner = focusManager.permanentFocusOwner

    val isInBurpWindow = generateSequence(permanentFocusOwner) { it.parent }.any { it == frame }

    return if (isInBurpWindow && permanentFocusOwner is JTextArea) {
        permanentFocusOwner
    } else {
        null
    }
}

interface HttpServiceParams {
    val targetHostname: String
    val targetPort: Int
    val usesHttps: Boolean

    fun toMontoyaService(): HttpService = HttpService.httpService(targetHostname, targetPort, usesHttps)
}

@Serializable
data class SendHttp1Request(
    val content: String,
    override val targetHostname: String,
    override val targetPort: Int,
    override val usesHttps: Boolean
) : HttpServiceParams

@Serializable
data class SendHttp2Request(
    val pseudoHeaders: Map<String, String>,
    val headers: Map<String, String>,
    val requestBody: String,
    override val targetHostname: String,
    override val targetPort: Int,
    override val usesHttps: Boolean
) : HttpServiceParams

@Serializable
data class CreateRepeaterTab(
    val tabName: String?,
    val content: String,
    override val targetHostname: String,
    override val targetPort: Int,
    override val usesHttps: Boolean
) : HttpServiceParams

@Serializable
data class CreateRepeaterTabHttp2(
    val tabName: String?,
    val pseudoHeaders: Map<String, String>,
    val headers: Map<String, String>,
    val requestBody: String,
    override val targetHostname: String,
    override val targetPort: Int,
    override val usesHttps: Boolean
) : HttpServiceParams

@Serializable
data class SendToIntruder(
    val tabName: String?,
    val content: String,
    override val targetHostname: String,
    override val targetPort: Int,
    override val usesHttps: Boolean
) : HttpServiceParams

@Serializable
data class UrlEncode(val content: String)

@Serializable
data class UrlDecode(val content: String)

@Serializable
data class Base64Encode(val content: String)

@Serializable
data class Base64Decode(val content: String)

@Serializable
data class GenerateRandomString(val length: Int, val characterSet: String)

@Serializable
data class SetProjectOptions(val json: String)

@Serializable
data class SetUserOptions(val json: String)

@Serializable
data class SetTaskExecutionEngineState(val running: Boolean)

@Serializable
data class SetProxyInterceptState(val intercepting: Boolean)

@Serializable
data class SetActiveEditorContents(val text: String)

@Serializable
data class GetScannerIssues(override val count: Int, override val offset: Int) : Paginated

@Serializable
data class GetProxyHttpHistory(override val count: Int, override val offset: Int) : Paginated

@Serializable
data class GetProxyHttpHistoryRegex(val regex: String, override val count: Int, override val offset: Int) : Paginated

@Serializable
data class GetProxyHttpHistorySummary(
    override val count: Int,
    override val offset: Int,
    val regex: String? = null
) : Paginated

@Serializable
data class GetProxyHttpHistoryItem(val historyId: Int)

@Serializable
data class SendProxyHistoryToRepeater(val historyId: Int, val tabName: String? = null)

@Serializable
data class SendProxyHistoryToIntruder(val historyId: Int, val tabName: String? = null)

@Serializable
data class SetProxyHistoryNotes(val historyId: Int, val notes: String, val append: Boolean = true)

@Serializable
data class SetProxyHistoryHighlight(val historyId: Int, val color: String)

@Serializable
data class SendProxyHistoryToOrganizer(val historyId: Int)

@Serializable
data class ProxyHttpHistorySummary(
    val historyId: Int,
    val timestamp: String,
    val method: String?,
    val url: String?,
    val responseStatusCode: Int?,
    val notes: String,
    val edited: Boolean,
    val highlightColor: String?
)

@Serializable
data class GetOrganizerItems(override val count: Int, override val offset: Int) : Paginated

@Serializable
data class GetOrganizerItemsRegex(val regex: String, override val count: Int, override val offset: Int) : Paginated

@Serializable
data class GetOrganizerItemsSummary(
    override val count: Int,
    override val offset: Int,
    val regex: String? = null
) : Paginated

@Serializable
data class GetOrganizerItem(val organizerId: Int)

@Serializable
data class SetOrganizerItemNotes(val organizerId: Int, val notes: String, val append: Boolean = true)

@Serializable
data class SendOrganizerItemToRepeater(val organizerId: Int, val tabName: String? = null)

@Serializable
data class SendOrganizerItemToIntruder(val organizerId: Int, val tabName: String? = null)

@Serializable
data class OrganizerItemSummary(
    val organizerId: Int,
    val organizerStatus: String,
    val method: String?,
    val url: String?,
    val responseStatusCode: Int?,
    val notes: String,
    val highlightColor: String?,
    val hasResponse: Boolean
)

@Serializable
data class GetProxyWebsocketHistory(override val count: Int, override val offset: Int) : Paginated

@Serializable
data class GetProxyWebsocketHistoryRegex(val regex: String, override val count: Int, override val offset: Int) :
    Paginated

@Serializable
data class GenerateCollaboratorPayload(
    val customData: String? = null
)

@Serializable
data class GetCollaboratorInteractions(
    val payloadId: String? = null
)
