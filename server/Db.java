import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库。整个程序里所有跟 SQLite 打交道的代码都在这里。
 */
public class Db {

    // SQLite 就是硬盘上的一个文件，名字叫 data.db
    private static final String URL = "jdbc:sqlite:data.db";

    /** 打开数据库连接 */
    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL);
    }

    /** 第一次启动时建表。表已经存在就跳过，不会重复建。 */
    public static void init() throws SQLException {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {

            // 物品表：每借出去一次，就是改这一行
            st.execute(
                "CREATE TABLE IF NOT EXISTS items (" +
                "  id       INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  name     TEXT NOT NULL," +
                "  borrower TEXT," +
                "  due_date TEXT" +
                ")");

            // 记录表：借、还、新增、删除，每发生一件事就加一行
            st.execute(
                "CREATE TABLE IF NOT EXISTS records (" +
                "  id         INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  text       TEXT NOT NULL," +
                "  created_at TEXT NOT NULL" +
                ")");
        }
    }
}
