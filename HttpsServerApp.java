import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

public class HttpsServerApp {
    private static final int PORT = 8443;
    private static final int THREAD_POOL_SIZE = 16;
    private static final String KEYSTORE_PATH = "keystore.p12";
    private static final char[] KEYSTORE_PASSWORD = "changeit".toCharArray();

    public static void main(String[] args) throws Exception {
        SSLServerSocketFactory factory = createSslServerSocketFactory(KEYSTORE_PATH, KEYSTORE_PASSWORD);
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

        try (SSLServerSocket serverSocket = (SSLServerSocket) factory.createServerSocket(PORT)) {
            serverSocket.setReuseAddress(true);
            serverSocket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            System.out.println("Multithreaded HTTPS server listening on HTTPS://localhost:" + PORT);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down HHTPS server...");
                shutdownPool(pool);
            }));

            while (!serverSocket.isClosed()) {
                Socket clientSocket = serverSocket.accept();
                pool.submit(() -> handleClient(clientSocket));
            }
        } catch (IOException e) {
            if (!pool.isShutdown()) {
                System.err.println("HTTPS server socket error: " + e.getMessage());
            }
        } finally {
            shutdownPool(pool);
        }
    }

    private static SSLServerSocketFactory createSslServerSocketFactory(String path, char[] password)
            throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream kStream = new FileInputStream(path)) {
            keyStore.load(kStream, password);
        }
        
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        return sslContext.getServerSocketFactory();
    }
    
    private static void handleClient(Socket socket) {
        try (socket) {
            socket.setSoTimeout(5000);

            if (socket instanceof SSLSocket sslSocket) {
                sslSocket.startHandshake();
            }

            try (BufferedReader in = new  BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                OutputStream rawOut = socket.getOutputStream();
                BufferedOutputStream out = new BufferedOutputStream(rawOut)) {
            
                String requestLine = in.readLine();
                if (requestLine == null || requestLine.isBlank()) {
                    return;
                }

                String[] parts = requestLine.split("\\s+");
                if (parts.length < 2) {
                    sendResponse(out, 400, "Bad Request", "text/plain; charset=utf-8", "Malformed request line");
                    return ;
                }

                String method = parts[0];
                String target = parts[1];

                Map<String, String> headers = new HashMap<>();
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    int colonIdx = line.indexOf(':');
                    if (colonIdx > 0) {
                        String name = line.substring(0, colonIdx).trim().toLowerCase();
                        String value = line.substring(colonIdx + 1).trim();
                        headers.put(name, value);
                    }
                }

                int contentLength = 0;
                if (headers.containsKey("content-length")) {
                    try {
                        contentLength = Math.max(0, Integer.parseInt(headers.get("content-length")));
                    } catch (NumberFormatException ignored) {
                        sendResponse(out,400, "Bad Request", "text/plain; charset=utf-8", "Invalid Content-Length");
                        return;
                    }
                }

                String body = "";
                if (contentLength > 0) {
                    char[] buf = new char[Math.min(contentLength, 1_048_576)];
                    int totalRead = 0;
                    StringBuilder bodyBuilder = new StringBuilder(Math.min(contentLength, 4096));
                    while (totalRead < contentLength) {
                        int toRead = Math.min(buf.length, contentLength - totalRead);
                        int read = in.read(buf, 0, toRead);
                        if (read == -1) break;
                        bodyBuilder.append(buf, 0, read);
                        totalRead += read;
                    }
                    body = bodyBuilder.toString();                    
                }

                routeRequest(method,target, body, out);
            }
        } catch (IOException e) {
            System.err.print("Client TLS/HTTP error (" + socket.getRemoteSocketAddress() + "): " + e.getMessage());
        }
    }

    private static void routeRequest(String method, String target, String body, OutputStream out)
            throws IOException {
        String path = target.split("\\?", 2)[0];

        if ("GET".equalsIgnoreCase(method) && "/".equals(path)) {
            String html = "<h1>Java Multithreaded HHTPS Server</h1><p>Thread: "
                    + Thread.currentThread().getName() + "</p>";
            sendResponse(out, 200, "OK", "text/html; charset=utf-8", html);
        } else if ("GET".equalsIgnoreCase(method) && "/echo".equals(path)) {
            sendResponse(out, 200, "OK", "application/json; charset=utf-8", "{\"status\":\"up\"}");
        } else if ("POST".equalsIgnoreCase(method) && "/echo".equals(path)) {
            sendResponse(out, 200, "OK", "text/plain; charset=utf-8", body);
        } else {
            sendResponse(out, 404, "Not Found", "text/plain; charset=utf-8", "Route not found: " + path);
        }
    }

    private static void sendResponse(OutputStream out, int statusCode, String reason,
                    String contentType, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String responseHeaders = "HTTP/1.1 " + statusCode + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";

        out.write(responseHeaders.getBytes(StandardCharsets.US_ASCII));
        out.write(bodyBytes);
        out.flush();
    }

    private static void shutdownPool(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdown();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}