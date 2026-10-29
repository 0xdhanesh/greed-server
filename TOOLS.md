# MCP tool coverage

| Tool Name | Feature | Portswigger mcp-server (✅/❌) | greed-server (✅/❌) | How we achieved | Locally checked (✅/❌) |
|---|---|:---:|:---:|---|:---:|
| `send_http1_request` | Send an HTTP/1.1 request | ✅ | ✅ | Existing Montoya HTTP request tool | ✅ |
| `send_http2_request` | Send an HTTP/2 request | ✅ | ✅ | Existing Montoya HTTP/2 request tool | ✅ |
| `create_repeater_tab` | Create an HTTP/1.1 Repeater tab | ✅ | ✅ | Existing Repeater integration | ✅ |
| `create_repeater_tab_http2` | Create an HTTP/2 Repeater tab | ✅ | ✅ | Existing Repeater integration | ✅ |
| `send_to_intruder` | Send a request to Intruder | ✅ | ✅ | Existing Intruder integration | ✅ |
| `url_encode` | URL encode text | ✅ | ✅ | Existing Burp URL utility | ✅ |
| `url_decode` | URL decode text | ✅ | ✅ | Existing Burp URL utility | ✅ |
| `base64_encode` | Base64 encode text | ✅ | ✅ | Existing Burp Base64 utility | ✅ |
| `base64_decode` | Base64 decode text | ✅ | ✅ | Existing Burp Base64 utility | ✅ |
| `generate_random_string` | Generate a random string | ✅ | ✅ | Existing Burp random utility | ✅ |
| `output_project_options` | Read project configuration JSON | ✅ | ✅ | Existing Burp configuration export | ✅ |
| `output_user_options` | Read user configuration JSON | ✅ | ✅ | Existing Burp configuration export | ✅ |
| `set_project_options` | Update project configuration JSON | ✅ | ✅ | Existing Burp configuration import | ✅ |
| `set_user_options` | Update user configuration JSON | ✅ | ✅ | Existing Burp configuration import | ✅ |
| `get_scanner_issues` | List scanner issues | ✅ | ✅ | Existing Scanner issue API with pagination | ✅ |
| `generate_collaborator_payload` | Generate a Collaborator payload | ✅ | ✅ | Existing Collaborator API | ✅ |
| `get_collaborator_interactions` | Read Collaborator interactions | ✅ | ✅ | Existing Collaborator API | ✅ |
| `get_proxy_http_history` | Read full Proxy HTTP history items | ✅ | ✅ | Existing Proxy history API and serializer | ✅ |
| `get_proxy_http_history_regex` | Filter full Proxy HTTP history items | ✅ | ✅ | Existing Proxy history filter and serializer | ✅ |
| `get_organizer_items` | Read Organizer items | ✅ | ✅ | Existing Organizer API with pagination | ✅ |
| `get_organizer_items_regex` | Filter Organizer items | ✅ | ✅ | Existing Organizer API filter and pagination | ✅ |
| `get_proxy_websocket_history` | Read Proxy WebSocket history | ✅ | ✅ | Existing Proxy WebSocket history API | ✅ |
| `get_proxy_websocket_history_regex` | Filter Proxy WebSocket history | ✅ | ✅ | Existing Proxy WebSocket history filter | ✅ |
| `set_task_execution_engine_state` | Pause or resume task execution | ✅ | ✅ | Existing Burp task execution API | ✅ |
| `set_proxy_intercept_state` | Enable or disable Proxy Intercept | ✅ | ✅ | Existing Proxy API | ✅ |
| `get_active_editor_contents` | Read active message editor text | ✅ | ✅ | Existing editor integration | ✅ |
| `set_active_editor_contents` | Update active message editor text | ✅ | ✅ | Existing editor integration | ✅ |
| `get_proxy_http_history_summary` | Paginated metadata-only Proxy HTTP history | ❌ | ✅ | Montoya history metadata; optional regex filtering before pagination | ✅ |
| `get_proxy_http_history_item` | Read one history item by Montoya history ID | ❌ | ✅ | Lookup by `ProxyHttpRequestResponse.id()` and existing serializer | ✅ |
| `send_proxy_history_to_repeater` | Open a Proxy history request in Repeater without sending it | ❌ | ✅ | Lookup by Montoya history ID; prefer `finalRequest()`, fall back to `request()`, then call Montoya Repeater API | ✅ |
| `set_proxy_history_notes` | Append or replace notes on one Proxy history item | ❌ | ✅ | Lookup by Montoya history ID and update Montoya annotations; append defaults to true | ✅ |
| `set_proxy_history_highlight` | Set or clear one Proxy history item’s highlight using `historyId` and `color` [ `NONE`, `RED`, `ORANGE`, `YELLOW`, `GREEN`, `CYAN`, `BLUE`, `PINK`, `MAGENTA`, and `GRAY`] | ❌ | ✅ | Lookup by Montoya history ID and update annotations using the Montoya `HighlightColor` enum | ✅ |
| `send_proxy_history_to_organizer` | Save one Proxy history item to Organizer without transmitting it | ❌ | ✅ | Find by Montoya history ID, prefer `finalRequest()`, and preserve response and annotations when available | ✅ |
| `get_organizer_items_summary` | Paginated compact Organizer metadata with optional regex filtering | ❌ | ✅ | Uses Montoya Organizer items and official ID/status/annotation/request/response metadata; regex filters before pagination | ✅ |
| `get_organizer_item` | Read one size-limited Organizer item by official Montoya ID | ❌ | ✅ | Finds `OrganizerItem.id()` and uses existing bounded history-item serialization | ✅ |
| `send_organizer_item_to_repeater` | Open one Organizer request in Repeater without transmitting it | ❌ | ✅ | Find the item by `OrganizerItem.id()` and pass its original `request()` directly to Montoya Repeater | ✅ |
| `send_proxy_history_to_intruder` | Open a Proxy history request in Intruder without starting an attack | ❌ | ✅ | Find by `ProxyHttpRequestResponse.id()`, prefer `finalRequest()`, fall back to `request()`, and pass the original request to Montoya Intruder | ✅ |
| `send_organizer_item_to_intruder` | Open an Organizer request in Intruder without sending it or starting an attack | ❌ | ✅ | Requires Organizer approval; finds `OrganizerItem.id()` and transfers `OrganizerItem.request()` directly to Montoya Intruder. A nonblank `tabName` is passed unchanged; omitted or blank names default to `Organizer <organizerId>`. The item remains unchanged. Inputs: `organizerId`, optional `tabName`. | ✅ |
