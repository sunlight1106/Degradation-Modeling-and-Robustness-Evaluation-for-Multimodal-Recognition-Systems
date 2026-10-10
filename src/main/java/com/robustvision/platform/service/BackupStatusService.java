package com.robustvision.platform.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
@Service
public class BackupStatusService {
 private final Path path;private final ObjectMapper mapper;
 public BackupStatusService(@Value("${app.backup.status-file:/app/backup-status/status.json}") String path,ObjectMapper mapper){this.path=Path.of(path);this.mapper=mapper;}
 public Map<String,Object> read(){Map<String,Object> result=new LinkedHashMap<>();try{if(!Files.isRegularFile(path)||Files.size(path)>20000)return result;var data=mapper.readTree(Files.readString(path));for(String key:List.of("lastSuccess","bytes","sha256","verified","scheduled","lastAttempt","retained","lastError","warning")){var value=data.get(key);if(value!=null&&!value.isNull())result.put(key,value.isNumber()?value.numberValue():value.isBoolean()?value.booleanValue():value.asText());}if(data.path("scheduled").asBoolean()&&data.hasNonNull("lastSuccess")){Instant success=Instant.parse(data.get("lastSuccess").asText());if(success.isBefore(Instant.now().minusSeconds(172800)))result.put("warning","自动备份已超过两天没有成功，请检查计划任务。");}}catch(Exception ignored){result.put("warning","无法读取备份状态，请检查本机备份任务。");}return result;}
}
