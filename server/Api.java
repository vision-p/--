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

/**
 * 接口。每个请求都从 handle() 进来，然后按路径分到对应的方法去干活。
 *
 * 这里只管「做什么」，不管「怎么收发网络」——发收网络的事情在 Server 里做完了。
 */
public class Api {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 一个接口的结果：状态码 + 一段 JSON */
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
                // 网页一打开就要全部数据
                return new Result(200, state());

            } else if (path.equals("/api/items") && method.equals("POST")) {
                // 新增物品
                return addItem(params);

            } else if (path.startsWith("/api/items/") && path.endsWith("/borrow")
                    && method.equals("POST")) {
                // 借出
                return borrow(path, params);

            } else if (path.startsWith("/api/items/") && path.endsWith("/return")
                    && method.equals("POST")) {
                // 归还
                return giveBack(path);

            } else if (path.startsWith("/api/items/") && method.equals("DELETE")) {
                // 删除
                return remove(path);

            } else {
                return new Result(404, "{\"error\":\"没有这个接口\"}");
            }
        } catch (Exception e) {
            // 出了任何错，都把错误信息回给网页，方便排查
            return new Result(500, "{\"error\":" + quote(e.toString()) + "}");
        }
    }

    // ---------- 每个接口的具体做法 ----------

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
                // 判断可借和登记借出由同一条 SQL 完成。
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

                // 状态和借出记录一起提交，写入出错时一起回滚。
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
            // 借用人清空，应还日期也清空
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

    // ---------- 小工具 ----------

    private static Result ok() {
        return new Result(200, "{\"ok\":true}");
    }

    /** 把两张表的所有数据拼成一段 JSON 返回给网页 */
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
        // 倒序：最新的记录排在最前面
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

    /** 往记录表里加一条 */
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

    /** 从 /api/items/12/borrow 里把 12 抠出来 */
    private static int idOf(String path) {
        String rest = path.substring("/api/items/".length());
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        return Integer.parseInt(rest);
    }

    /** 网页提交的是 name=雨伞&borrower=张三 这种格式，这里把它拆成键值对 */
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

    /** 把字符串包成 JSON 的字符串。null 要写成 JSON 的 null，不能写成 "null" */
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
