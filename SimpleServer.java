import java.io.*;
import java.net.*;

public class SimpleServer {
    public static void main(String[] args) throws IOException {
        int port = 8080;
        ServerSocket serverSocket = new ServerSocket(port);

        System.out.println("Server started on port " + port);

        while (true) {
            Socket clienSocket = serverSocket.accept();
            System.out.println("Client connected!");

            InputStream input = clienSocket.getInputStream();
            OutputStream output = clienSocket.getOutputStream();

            BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output));

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                clienSocket.close();
                continue;
            }

            System.out.println("Request: " + requestLine);
            
                        // --- Step 2: Parse request line ---
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) {
                clienSocket.close();
                continue;
            }
            String method = parts[0];
            String path = parts[1];
            String version = parts[2];

            System.out.println("Method: " + method);
            System.out.println("Path: " + path);
            System.out.println("Version: " + version);

            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                System.out.println(line);
            }

            String body;
            if ("/hello".equals(path)) {
                body = "<h1>Hello Ezzzyyy 🔥</h1>";
            } else {
                body = "<h1>Welcome to my Server!</h1>";
            }

            writer.write("HTTP/1.1 200 OK\r\n");
            writer.write("Content-Type: text/html\r\n");
            writer.write("Content-Length: " + body.getBytes().length + "\r\n");
            writer.write("Connection: close\r\n");
            writer.write("\r\n");
            writer.write(body);
            writer.flush();
            
            clienSocket.close();
            System.out.println("Connection closed.");
            
        }
    }
}
