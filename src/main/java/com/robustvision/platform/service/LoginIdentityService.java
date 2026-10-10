package com.robustvision.platform.service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Locale;
@Service
public class LoginIdentityService {
    private final JdbcTemplate db;
    public LoginIdentityService(JdbcTemplate db){this.db=db;}
    public String username(String identifier){
        String value=identifier.trim();
        if(value.matches("(?i)PKB-[A-F0-9]{32}"))return db.queryForList("SELECT username FROM app_user WHERE identity_code=?",String.class,value.toUpperCase(Locale.ROOT)).stream().findFirst().orElse(value);
        if(value.contains("@"))return db.queryForList("SELECT u.username FROM app_user u JOIN account_security s ON s.owner_id=u.id WHERE LOWER(u.email)=? AND s.verified_email=u.email",String.class,value.toLowerCase(Locale.ROOT)).stream().findFirst().orElse(value);
        return value;
    }
    public Long owner(String identifier){return db.queryForList("SELECT id FROM app_user WHERE username=?",Long.class,username(identifier)).stream().findFirst().orElse(null);}
}
