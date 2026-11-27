import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class Db {

    private static final String URL = "jdbc:sqlite:data.db";

    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL);
    }

    public static void init() throws SQLException {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {

            st.execute(
                "CREATE TABLE IF NOT EXISTS items (" +
                "  id       INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  name     TEXT NOT NULL," +
                "  borrower TEXT," +
                "  due_date TEXT" +
                ")");

            st.execute(
                "CREATE TABLE IF NOT EXISTS records (" +
                "  id         INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  text       TEXT NOT NULL," +
                "  created_at TEXT NOT NULL" +
                ")");
        }
    }
}
