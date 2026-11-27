import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class Api {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static class Result {
        final int status;
        final String body;

        Result(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    static Result handle(String method, String path, String bodyText) {
        try {
            Map<String, String> params = parseForm(bodyText);

            if (path.equals("/api/state") && method.equals("GET")) {
                return new Result(200, state());

            } else if (path.equals("/api/items") && method.equals("POST")) {
                return addItem(params);

            } else if (path.startsWith("/api/items/") && path.endsWith("/borrow")
                    && method.equals("POST")) {
                return borrow(path, params);

            } else if (path.startsWith("/api/items/") && path.endsWith("/return")
                    && method.equals("POST")) {
                return giveBack(path);

            } else if (path.startsWith("/api/items/") && method.equals("DELETE")) {
                return remove(path);

            } else {
                return new Result(404, "{\"error\":\"没有这个接口\"}");
            }
        } catch (Exception e) {
            return new Result(500, "{\"error\":" + quote(e.toString()) + "}");
        }
    }

    private static Result addItem(Map<String, String> p) throws Exception {
        String name = p.getOrDefault("name", "").trim();
        if (name.isEmpty()) {
            return new Result(400, "{\"error\":\"物品名字不能为空\"}");
        }
        try (Connection c = Db.connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO items(name) VALUES(?)")) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
        addRecord("新增了 " + name);
        return ok();
    }

    private static Result borrow(String path, Map<String, String> p) throws Exception {
        int id = idOf(path);
        String borrower = p.getOrDefault("borrower", "").trim();
        String dueDate = p.getOrDefault("dueDate", "").trim();

        if (borrower.isEmpty()) {
            return new Result(400, "{\"error\":\"借用人不能为空\"}");
        }

        try (Connection c = Db.connect()) {
            c.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE items SET borrower = ?, due_date = ? WHERE id = ? AND borrower IS NULL")) {
                    ps.setString(1, borrower);
                    ps.setString(2, dueDate.isEmpty() ? null : dueDate);
                    ps.setInt(3, id);
                    updated = ps.executeUpdate();
                }

                String name = null;
                try (PreparedStatement ps = c.prepareStatement("SELECT name FROM items WHERE id = ?")) {
                    ps.setInt(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) name = rs.getString("name");
                    }
                }
                if (updated == 0) {
                    c.rollback();
                    if (name == null) return new Result(404, "{\"error\":\"物品不存在\"}");
                    return new Result(409, "{\"error\":\"这个物品已经借出去了\"}");
                }

                addRecord(c, borrower + " 借走了 " + name);
                c.commit();
            } catch (Exception error) {
                c.rollback();
                throw error;
            }
        }
        return ok();
    }

    private static Result giveBack(String path) throws Exception {
        int id = idOf(path);

        String name;
        String borrower;
        try (Connection c = Db.connect()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT name, borrower FROM items WHERE id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return new Result(404, "{\"error\":\"物品不存在\"}");
                    }
                    borrower = rs.getString("borrower");
                    if (borrower == null) {
                        return new Result(409, "{\"error\":\"这个物品没有借出去\"}");
                    }
                    name = rs.getString("name");
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE items SET borrower = NULL, due_date = NULL WHERE id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
        }
        addRecord(borrower + " 归还了 " + name);
        return ok();
    }

    private static Result remove(String path) throws Exception {
        int id = idOf(path);

        String name;
        String borrower;
        try (Connection c = Db.connect()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT name, borrower FROM items WHERE id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return new Result(404, "{\"error\":\"物品不存在\"}");
                    }
                    name = rs.getString("name");
                    borrower = rs.getString("borrower");
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM items WHERE id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
        }
        addRecord("删除了 " + name
                + (borrower == null ? "" : "（借用人：" + borrower + "）"));
        return ok();
    }

    private static Result ok() {
        return new Result(200, "{\"ok\":true}");
    }

    private static String state() throws Exception {
        StringBuilder sb = new StringBuilder("{\"items\":[");
        try (Connection c = Db.connect();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, name, borrower, due_date FROM items ORDER BY id")) {
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"id\":").append(rs.getInt("id"))
                  .append(",\"name\":").append(quote(rs.getString("name")))
                  .append(",\"borrower\":").append(quote(rs.getString("borrower")))
                  .append(",\"dueDate\":").append(quote(rs.getString("due_date")))
                  .append('}');
            }
        }

        sb.append("],\"records\":[");
        try (Connection c = Db.connect();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT text, created_at FROM records ORDER BY id DESC")) {
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"text\":").append(quote(rs.getString("text")))
                  .append(",\"createdAt\":").append(quote(rs.getString("created_at")))
                  .append('}');
            }
        }
        return sb.append("]}").toString();
    }

    private static void addRecord(String text) throws Exception {
        try (Connection c = Db.connect()) {
            addRecord(c, text);
        }
    }

    private static void addRecord(Connection c, String text) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO records(text, created_at) VALUES(?, ?)")) {
            ps.setString(1, text);
            ps.setString(2, LocalDateTime.now().format(TIME));
            ps.executeUpdate();
        }
    }

    private static int idOf(String path) {
        String rest = path.substring("/api/items/".length());
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        return Integer.parseInt(rest);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;

        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            map.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return map;
    }

    private static String quote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (ch < 0x20) {
                        b.append(String.format("\\u%04x", (int) ch));
                    } else {
                        b.append(ch);
                    }
            }
        }
        return b.append('"').toString();
    }
}
