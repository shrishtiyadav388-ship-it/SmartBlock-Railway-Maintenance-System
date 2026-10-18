package com.smartblock.repo;

import com.smartblock.security.PasswordUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class UserRepo {
    private final JdbcTemplate jdbc;

    public UserRepo(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, String> login(String username, String password) {
        if (username == null || password == null || username.isBlank() || password.isEmpty()) return null;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.username, u.password_hash, u.role, d.dept_name
                FROM app_user u LEFT JOIN department d ON d.dept_id = u.dept_id
                WHERE u.username = ?
                """, username.trim());
        if (rows.isEmpty()) return null;

        Map<String, Object> row = rows.get(0);
        if (!PasswordUtil.matches(password, String.valueOf(row.get("password_hash")))) return null;

        String role = String.valueOf(row.get("role"));
        String department = row.get("dept_name") == null ? "" : String.valueOf(row.get("dept_name"));
        if (!List.of("CONTROL", "DEPARTMENT").contains(role)
                || ("DEPARTMENT".equals(role) && department.isBlank())
                || ("CONTROL".equals(role) && !department.isBlank())) return null;

        Map<String, String> user = new HashMap<>();
        user.put("username", String.valueOf(row.get("username")));
        user.put("role", role);
        user.put("dept", department);
        return user;
    }
}
