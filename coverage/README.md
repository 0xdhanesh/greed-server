# Changes

## 29.10.2026

**feat(tools): add proxy HTTP history summary and lookup**

- Requests can be searched up with request id from proxy history
- A selected Proxy history item can be retrieved by its Montoya history ID.

| Claude Console output | Burpsuite | 
| --------------------- | --------- | 
| ![claude-console-output](./assets/29_10_26_http_hisotry_lookup.png) | ![burpsuite-repeater](./assets/29_10_26_http_hisotry_lookup1.png) | 

**feat(tools): open proxy history requests in Repeater**

- A Proxy history request can be opened in Repeater using its official Montoya history ID.
- The final processed request is preferred, with the original request used as a fallback.
- Opening the Repeater tab does not transmit the HTTP request.
- Explicit and automatically generated Repeater tab names are supported.
- Unknown history IDs return a clear error without creating a Repeater tab.
- This only creates the repeater tab, does not send the request via repeater tab

| Claude Console output | Burpsuite | 
| --------------------- | --------- | 
| ![claude-console-output](./assets/29_10_26_proxy_history_to_repeater.png) | ![burpsuite-repeater](./assets/29_10_26_proxy_history_to_repeater1.png) | 

**feat(tools): add proxy history notes** 

- Notes can be added to a Proxy history item using its official Montoya history ID.
- New notes are appended by default, preserving existing notes on separate lines.
- Existing notes can be replaced only when append is explicitly set to false.
- Blank notes are rejected without modifying the history item.
- Unknown history IDs return a clear error without modifying another item.
- Tool results and logs do not expose note contents or HTTP data.
- Updating notes does not transmit an HTTP request or open Repeater or Intruder.
- Notes can be added to a Proxy history item using its official Montoya history ID.
- New notes are appended by default, preserving existing notes on separate lines.
- Existing notes can be replaced only when append is explicitly set to false.
- Blank notes are rejected without modifying the history item.
- Unknown history IDs return a clear error without modifying another item.
- Tool results and logs do not expose note contents or HTTP data.
- Updating notes does not transmit an HTTP request or open Repeater or Intruder.

| Claude Console output | Burpsuite | 
| --------------------- | --------- | 
| ![claude-console-output](./assets/29_10_26_proxy_history_notes.png) | ![burpsuite-repeater](./assets/29_10_26_proxy_history_notes1.png) | 

**feat(tools): add proxy history highlighting**

- A Proxy history item can be highlighted using its official Montoya history ID.
- Highlight colors are validated against the official Montoya `HighlightColor` enum.
- Color names are accepted case-insensitively.
- `NONE` clears the existing highlight.
- Invalid colors are rejected without changing the current highlight.
- Unknown history IDs return a clear error without modifying another item.
- Proxy history summaries report the current highlight color.
- Tool results and logs do not expose HTTP contents, notes, cookies or authorization data.
- Updating a highlight does not transmit an HTTP request or open Repeater or Intruder.

| Claude Console output | Burp Suite Proxy history |
| --------------------- | ------------------------ |
| ![Claude highlight validation](./assets/29_10_26_set_proxy_history_highlight.png) | ![Highlighted Proxy history item](./assets/29_10_26_set_proxy_history_highlight1.png) |

**feat(tools): save proxy history items to Organizer**

- A Proxy history item can be saved to Burp Organizer using its official Montoya history ID.
- HTTP-history and Organizer approval are both required before an item is created.
- The final processed request is preferred, with the original request used as a fallback.
- Recorded responses and annotations are preserved when available.
- Request-only items are supported without fabricating a response.
- Unknown history IDs return a clear error without creating an Organizer item.
- Permission denial prevents Organizer insertion.
- Tool results and logs do not expose HTTP contents, notes, cookies, tokens or authorization data.
- Saving an item to Organizer does not transmit an HTTP request or open Repeater or Intruder.
- Exactly one Organizer item is created for each successful invocation.

| Claude Console output | Burp Suite Organizer |
| --------------------- | -------------------- |
| ![Claude Organizer validation](./assets/29_10_26_send_proxy_history_to_organizer.png) | ![Proxy history item saved in Organizer](./assets/29_10_26_send_proxy_history_to_organizer1.png) |

**feat(tools): add Organizer summary and item lookup**

- Compact Organizer item summaries can be retrieved without exposing complete HTTP requests or responses.
- Summaries include the official Montoya Organizer ID, status, method, URL, response status, notes, highlight color and response availability.
- Organizer summaries support optional Java regular-expression filtering.
- Regex filtering is applied before pagination.
- Organizer items can be retrieved individually using their official Montoya Organizer ID.
- Unknown Organizer IDs return a clear error without returning a different item.
- Invalid regular expressions return a controlled error without disconnecting the MCP client.
- Large request or response fields may be truncated by the existing shared serializer.
- Both tools require Organizer access approval.
- Both tools are read-only and do not modify Organizer items, annotations or statuses.
- Retrieving Organizer data does not transmit an HTTP request or open Repeater or Intruder.

| Claude Console output | Burp Suite Organizer |
| --------------------- | -------------------- |
| ![Claude Organizer lookup validation](./assets/29_10_26_get_organizer_items_summary.png) | ![Organizer item verified in Burp](./assets/29_10_26_get_organizer_items_summary1.png) |