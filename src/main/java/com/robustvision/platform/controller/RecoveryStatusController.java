package com.robustvision.platform.controller;
import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.service.CurrentUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;

@RestController @RequestMapping("/api/v1/research/recovery-status")
public class RecoveryStatusController {
    private final CurrentUserService current;private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final String statusFile;
    public RecoveryStatusController(CurrentUserService current,JdbcTemplate jdbc,ObjectMapper mapper,@Value("${app.backup.status-file:/app/backup-status/status.json}") String statusFile){this.current=current;this.jdbc=jdbc;this.mapper=mapper;this.statusFile=statusFile;}
    @GetMapping public Object status(){current.requireSuperAdmin();Map<String,Object> out=new LinkedHashMap<>();out.put("database",jdbc.queryForObject("SELECT 1",Integer.class)==1?"UP":"UNKNOWN");
        Path path=Path.of(statusFile);out.put("backup",null);
        try{if(Files.isRegularFile(path)&&Files.size(path)<20000){var data=mapper.readTree(Files.readString(path));out.put("backup",Map.of("lastSuccess",data.path("lastSuccess").asText(),"bytes",data.path("bytes").asLong(),"sha256",data.path("sha256").asText()));}}catch(Exception e){out.put("backupStatusError","无法读取备份状态文件");}
        return ApiResponse.ok(out);
    }
}
