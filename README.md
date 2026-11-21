# Multithreaded HTTPS Server (Java)

Built from scratch multithreaded HTTPS web server that was implemented with Java, using `SSLServerSocket`, `ExecutorService` thread pooling and `javax.net.ssl` (`TLSv1.3` / `TLSv1.2`) backed with a local PKCS#12 keystore (`keystore.p12`).

## Environment & Prerequisites

- **OS:** Windows (tested in PowerShell)
- **JDK:** JDK 25 (LTS) (`java`, `javac`, `keytool`)
- **Working Directory:** `Project root (\)`

## 1. Generate Local Keystore (`keystore.p12`)

To create a self signed PKCS#12 keystore for `localhost` / `127.0.0.1`:

```powershell
keytool -genkeypair `
  -alias localhost `
  -keyalg RSA `
  -keysize 2048 `
  -storetype PKCS12 `
  -keystore keystore.p12 `
  -storepass changeit `
  -keypass changeit `
  -validity 365 `
  -dname "CN=localhost" `
  -ext "SAN=dns:localhost,ip:127.0.0.1"
```

## 2. Compile and Run

```powershell
javac HttpsServerApp.java
java HttpsServerApp
```

By default the server will listen at `https://localhost:8443`.

## 3. Endpoints & Status Codes

- `GET /` — Returns `200 OK` HTML including the worker thread name handling the request.
- `GET /health` — Returns `200 OK` JSON (`{"status":"up"}`).
- `POST /echo` — Returns `200 OK` echoing the request body.
- Mismatched HTTP methods on known routes — Returns `405 Method Not Allowed` with allowed method details.
- Unknown routes — Returns `404 Not Found`.

### Example `curl` Checks

```powershell
curl.exe -k -i https://localhost:8443/
curl.exe -k -i https://localhost:8443/health
curl.exe -k -i -X POST -d "hello tls server" https://localhost:8443/echo
curl.exe -k -i -X POST https://localhost:8443/health
curl.exe -k -i https://localhost:8443/unknown
```

### Concurrency Check (PowerShell 5.1)

```powershell
1..6 | ForEach-Object {
    Start-Job { curl.exe -k -s "https://localhost:8443/?req=$using:_" }
} | Receive-Job -Wait -AutoRemoveJob
```

## Errors Resolved During Build & Testing

- **Method & Variable Typos / Mismatches:** Fixed the `createSslServerSocketFactory` naming, `sslSocket` some pattern/handshake handling, a `HashMaps` typo, the request body loop variables (`totalRead` / `toRead`), `sendResponse` argument mismatching and `repsonseHeaders` typo.
- **PKCS#12 Keystore Load (`toDerInputStream rejects tag type 107`):** Removed stale/malformed `keystore.p12` bytes and regenerated a valid DER-encoded PKCS#12 keystore with `keytool`.
- **`/health` Route Matching:** needed to fixe `routeRequest` to match `/health` instead of jsut showing the `/echo` on `GET` then recompiled and restarted the server.
- **HTTP Status Line (`Unsupported HTTP/1 subversion in response`):** Added missing (white)space after `"HTTP/1.1 "` in the `sendResponse` so status lines format as `HTTP/1.1 <statusCode> <reason>` to align woth JAVA requirements.

## Reference Material in Repository

- *The Java™ Programming Language, Fourth Edition* by Ken Arnold, James Gosling, and David Holmes (`THE Java™ Programming Language, Fourth Edition By Ken Arnold, James Gosling, David Holmes.pdf`) — is included for historical, language reference context.