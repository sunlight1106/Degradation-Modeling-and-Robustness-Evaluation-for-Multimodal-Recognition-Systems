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
    @org.springframework.beans.factory.annotation.Autowired private com.robustvision.platform.service.BackupStatusService backupStatus;
    private final CurrentUserService current;private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final String statusFile;
    public RecoveryStatusController(CurrentUserService current,JdbcTemplate jdbc,ObjectMapper mapper,@Value("${app.backup.status-file:/app/backup-status/status.json}") String statusFile){this.current=current;this.jdbc=jdbc;this.mapper=mapper;this.statusFile=statusFile;}
    @GetMapping public Object status(){current.requireSuperAdmin();Map<String,Object> out=new LinkedHashMap<>();out.put("database",jdbc.queryForObject("SELECT 1",Integer.class)==1?"UP":"UNKNOWN");
        Path path=Path.of(statusFile);out.put("backup",null);
        var backup=backupStatus.read();out.put("backup",backup.isEmpty()?null:backup);
        return ApiResponse.ok(out);
    }
}
