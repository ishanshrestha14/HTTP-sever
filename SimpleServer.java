import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.concurrent.*;

public class SimpleServer {
    public static void main(String[] args) throws IOException {
        
        int port = 8080;
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("Server started on port " + port);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        
        while (true) {
            Socket clientSocket = serverSocket.accept();
            System.out.println("Client connected!");

            pool.submit(() -> handleClient(clientSocket));
        }
    }

    private static void handleClient(Socket clientSocket) {
        try {
            Socket socket = clientSocket;
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            
            // --- Step 2: Parse request line ---
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) return;

            String method = parts[0];
            String path = parts[1];
            String version = parts[2];

            System.out.println("Method: " + method + ", Path: " + path);

            // Read headers 
            String line;
            int contentLength = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                System.out.println(line);
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.split(":")[1].trim());
                }
            }

            // Read body if POST
            String bodyContent = "";
            if ("POST".equalsIgnoreCase(method) && contentLength > 0) {
                char[] bodyChars = new char[contentLength];
                reader.read(bodyChars, 0, contentLength);
                bodyContent = new String(bodyChars);
                System.out.println("Body: " + bodyContent);
            }

            // Simple routing
            String responseBody;

            if (path.startsWith("/static/")) {
                File file = new File("public", path.substring("/static/".length()));
                if (file.exists() && file.isFile()) {
                    byte[] fileBytes = Files.readAllBytes(file.toPath());
                    String mime = guessContentType(file.getName());
                    writer.write("HTTP/1.1 200 OK\r\n");
                    writer.write("Content-Type: " + mime + "\r\n");
                    writer.write("Content-Length: " + fileBytes.length + "\r\n");
                    writer.write("Connection: close\r\n");
                    writer.write("\r\n");
                    writer.flush();
                    clientSocket.getOutputStream().write(fileBytes);
                    clientSocket.getOutputStream().flush();
                    return;
                } else {
                    responseBody = "<h1>404 Not Found</h1>";
                }
            }
            else if ("/hello".equals(path)) {
                responseBody = "<h1>Hello Ezzzyyy 🔥</h1>";
            } else if ("/submit".equals(path) && "POST".equalsIgnoreCase(method)) {
                responseBody = "<h1>Form submitted!</h1><p>Data: " + bodyContent + "</p>";
            } else {
                responseBody = "<h1>Welcome to my Server!</h1>";
            }

            writer.write("HTTP/1.1 200 OK\r\n");
            writer.write("Content-Type: text/html\r\n");
            writer.write("Content-Length: " + responseBody.getBytes().length + "\r\n");
            writer.write("Connection: close\r\n");
            writer.write("\r\n");
            writer.write(responseBody);
            writer.flush();
            
            System.out.println("Resonse sent, connection closed.\n");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

        // --- Helper: guess MIME type ---
    private static String guessContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }
}
