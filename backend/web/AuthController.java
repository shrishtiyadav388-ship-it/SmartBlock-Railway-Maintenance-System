package com.smartblock.web;

import com.smartblock.repo.UserRepo;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final UserRepo users;

    public AuthController(UserRepo users) {
        this.users = users;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Map<String, String> user = users.login(body.get("username"), body.get("password"));

        if (user == null) {
            return Map.of("ok", false, "message", "Wrong username or password");
        }

        HttpSession oldSession = request.getSession(false);
        if (oldSession != null) oldSession.invalidate();
        HttpSession session = request.getSession(true);
        session.setAttribute("username", user.get("username"));
        session.setAttribute("role", user.get("role"));
        session.setAttribute("dept", user.get("dept"));

        return Map.of("ok", true, "username", user.get("username"),
                      "role", user.get("role"), "dept", user.get("dept"));
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpSession session) {
        Object username = session.getAttribute("username");
        if (username == null) {
            return Map.of("loggedIn", false);
        }
        return Map.of("loggedIn", true, "username", username,
                      "role", session.getAttribute("role"),
                      "dept", session.getAttribute("dept"));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        session.invalidate();
        return Map.of("ok", true);
    }
}
