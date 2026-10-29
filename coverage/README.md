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