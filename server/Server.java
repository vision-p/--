import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class Server {

    private static final int PORT = 8080;
    private static final File WEB = new File("小组借物登记");

    public static void main(String[] args) throws Exception {
        Db.init();

        ServerSocket server = new ServerSocket(PORT);
        System.out.println("服务已启动：http://localhost:" + PORT);
        System.out.println("按 Ctrl+C 停止");

        while (true) {
            Socket socket = server.accept();
            new Thread(() -> handle(socket)).start();
        }
    }

    private static void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(10000);

            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            ByteArrayOutputStream headBuffer = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                headBuffer.write(b);
                if (b == '\n' && endsWithBlankLine(headBuffer)) break;
                if (headBuffer.size() > 65536) break;
            }

            String head = headBuffer.toString(StandardCharsets.UTF_8);
            if (head.isEmpty()) return;

            String[] lines = head.split("\r\n");
            String[] requestLine = lines[0].split(" ");
            String method = requestLine[0];
            String path = requestLine[1];

            int contentLength = 0;
            for (String line : lines) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(
                            line.substring("content-length:".length()).trim());
                }
            }

            byte[] body = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(body, read, contentLength - read);
                if (n == -1) break;
                read += n;
            }
            String bodyText = new String(body, 0, read, StandardCharsets.UTF_8);

            if (path.startsWith("/api/")) {
                Api.Result result = Api.handle(method, path, bodyText);
                send(out, result.status, "application/json; charset=utf-8",
                        result.body.getBytes(StandardCharsets.UTF_8));
            } else {
                sendFile(out, path);
            }
        } catch (Exception e) {
        }
    }

    private static boolean endsWithBlankLine(ByteArrayOutputStream buffer) {
        byte[] data = buffer.toByteArray();
        int n = data.length;
        return n >= 4
                && data[n - 4] == '\r' && data[n - 3] == '\n'
                && data[n - 2] == '\r' && data[n - 1] == '\n';
    }

    private static void sendFile(OutputStream out, String path) throws IOException {
        if (path.equals("/")) path = "/index.html";

        File file = new File(WEB, path);

        String root = WEB.getCanonicalPath();
        if (!file.isFile() || !file.getCanonicalPath().startsWith(root)) {
            send(out, 404, "text/plain; charset=utf-8",
                    "页面不存在".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(out, 200, contentType(file.getName()), Files.readAllBytes(file.toPath()));
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css"))  return "text/css; charset=utf-8";
        if (name.endsWith(".js"))   return "application/javascript; charset=utf-8";
        return "application/octet-stream";
    }

    private static void send(OutputStream out, int status, String contentType, byte[] body)
            throws IOException {
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 409: return "Conflict";
            default:  return "Error";
        }
    }
}
