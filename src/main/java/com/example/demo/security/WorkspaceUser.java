package com.example.demo.security;

import java.io.Serializable;
import java.util.Map;

/** Authority comes only from a successful server-side login. */
public record WorkspaceUser(String userId, String userType, String accountId) implements Serializable {
    public static final String SESSION_KEY = WorkspaceUser.class.getName();
    public boolean headquarters() { return "2".equals(userType); }
    public static WorkspaceUser from(Map<String,Object> login) {
        return new WorkspaceUser(value(login.get("user_id")),value(login.get("user_type")),value(login.get("account_id")));
    }
    private static String value(Object value) { return value==null ? "" : value.toString(); }
}
