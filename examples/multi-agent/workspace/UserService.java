package demo;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/** Looks up users. It has deliberate problems for the reviewer to find. */
public class UserService {

    private static final String ADMIN_PASSWORD = "admin123";

    private final Connection connection;

    public UserService(Connection connection) {
        this.connection = connection;
    }

    public String findEmail(String username) throws Exception {
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(
                "SELECT email FROM users WHERE name = '" + username + "'");
        rows.next();
        return rows.getString("email");
    }

    public boolean isAdmin(String password) {
        return password == ADMIN_PASSWORD;
    }
}
