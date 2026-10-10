package com.robustvision.platform.service;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import com.robustvision.platform.common.BusinessException;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.*;
@Service
public class AccountActivityService {
    private final JdbcTemplate db; private final LoginIdentityService identities;private final CurrentUserService current;
    public AccountActivityService(JdbcTemplate db,LoginIdentityService identities,CurrentUserService current){this.db=db;this.identities=identities;this.current=current;}
    public void record(String identifier,String action,String outcome,HttpServletRequest request){
        try {
        Long owner=identities.owner(identifier);String ip=Objects.toString(request.getRemoteAddr(),"未知网络"),agent=Objects.toString(request.getHeader("User-Agent"),"未知设备");
        String network=ip.contains(":")?ip.substring(0,Math.min(ip.length(),12))+"…":ip.replaceFirst("\\.[0-9]+$",".*");
        db.update("INSERT INTO account_activity(id,owner_id,action,outcome,network,device,created_at) VALUES (?,?,?,?,?,?,?)",UUID.randomUUID().toString(),owner,action,outcome,network.substring(0,Math.min(80,network.length())),agent.substring(0,Math.min(180,agent.length())),java.sql.Timestamp.from(Instant.now()));}
        catch(org.springframework.dao.DataAccessException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Account activity persistence unavailable");}
    }
    public List<Map<String,Object>> own(int page){return list(current.requireCurrent().getId(),page);}
    public List<Map<String,Object>> list(long owner,int page){if(page<0||page>1000)throw new BusinessException(HttpStatus.BAD_REQUEST,"PAGE_INVALID","页码无效");return db.query("SELECT action,outcome,network,device,created_at FROM account_activity WHERE owner_id=? ORDER BY created_at DESC,id DESC LIMIT 30 OFFSET ?",(r,i)->Map.<String,Object>of("action",r.getString(1),"outcome",r.getString(2),"network",r.getString(3),"device",r.getString(4),"createdAt",r.getTimestamp(5).toInstant()),owner,page*30);}
    @Scheduled(fixedDelay=86400000,initialDelay=600000) public void retain(){db.update("DELETE FROM account_activity WHERE created_at<?",java.sql.Timestamp.from(Instant.now().minusSeconds(90*86400L)));}
}
