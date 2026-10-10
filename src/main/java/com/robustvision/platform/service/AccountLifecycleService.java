package com.robustvision.platform.service;
import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.repository.UserRepository;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
@Service
public class AccountLifecycleService {
    public record Prepare(@NotBlank @Size(max=20) String mode,@NotBlank @Size(max=100) String currentPassword) {}
    public record Confirm(@NotBlank @Size(max=100) String token,@NotBlank @Size(max=20) String confirmation,@NotBlank @Size(max=100) String currentPassword,@Size(max=32) String otp) {}
    private record Pending(long owner,String mode,String credential,Instant ready,Instant expires) {}
    private final Map<String,Pending> pending=new ConcurrentHashMap<>();
    private final JdbcTemplate db;private final UserRepository users;private final CurrentUserService current;private final PasswordEncoder passwords;
    private final AccountSecurityService security;private final UserSessionService sessions;private final AccountPurgeService purge;
    private Clock clock=Clock.systemUTC();
    @org.springframework.beans.factory.annotation.Autowired private PersonalAiRateLimiter aiLimits;
    public AccountLifecycleService(JdbcTemplate db,UserRepository users,CurrentUserService current,PasswordEncoder passwords,AccountSecurityService security,UserSessionService sessions,AccountPurgeService purge){this.db=db;this.users=users;this.current=current;this.passwords=passwords;this.security=security;this.sessions=sessions;this.purge=purge;}
    @Transactional public Map<String,Object> prepare(Prepare request){
        if(!Set.of("SUSPEND","PURGE").contains(request.mode()))throw bad("CLOSURE_MODE_INVALID","请选择停用或永久注销");
        var user=lockedOwner();password(user,request.currentPassword());guard(user);
        Instant now=clock.instant();pending.entrySet().removeIf(e->e.getValue().expires().isBefore(now));
        if(pending.size()>512)throw bad("CLOSURE_BUSY","请稍后重试");pending.entrySet().removeIf(e->e.getValue().owner()==user.getId());
        String token=UUID.randomUUID()+"."+UUID.randomUUID();var p=new Pending(user.getId(),request.mode(),user.getPasswordHash(),now.plusSeconds(5),now.plusSeconds(300));pending.put(token,p);
        return Map.of("token",token,"mode",request.mode(),"waitSeconds",5,"expiresAt",p.expires(),"notes",db.queryForObject("SELECT COUNT(*) FROM note WHERE owner_id=?",Long.class,user.getId()));
    }
    @Transactional(noRollbackFor=BusinessException.class) public void confirm(Confirm request){
        var user=lockedOwner();var p=pending.get(request.token());Instant now=clock.instant();
        if(p==null||p.owner()!=user.getId()||p.expires().isBefore(now)||!p.credential().equals(user.getPasswordHash()))throw bad("CLOSURE_EXPIRED","确认已过期或账号安全信息有变化，请重新开始");
        if(now.isBefore(p.ready()))throw bad("CLOSURE_WAIT","请完整等待五秒后再确认");
        if(!request.confirmation().equals(p.mode().equals("PURGE")?"永久注销":"停用账号"))throw bad("CLOSURE_TEXT_INVALID","请输入页面要求的确认文字");
        password(user,request.currentPassword());guard(user);security.checkLogin(user,request.otp());
        if(!pending.remove(request.token(),p))throw bad("CLOSURE_EXPIRED","这次确认已经使用");
        db.update("DELETE FROM account_closure WHERE owner_id=?",user.getId());
        db.update("INSERT INTO account_closure(owner_id,mode,discoverable,closed_at) VALUES (?,?,?,?)",user.getId(),p.mode(),user.isDiscoverable(),java.sql.Timestamp.from(now));
        user.setStatus(UserStatus.DISABLED);user.setDiscoverable(false);sessions.revokeAll(user.getId());
        if(p.mode().equals("PURGE")){
            purge.erasePrivate(user.getId());user.setDisplayName("已注销用户");user.setEmail("closed-"+UUID.randomUUID()+"@example.invalid");user.setPasswordHash(passwords.encode(UUID.randomUUID().toString()));
            user.setUsername("closed-"+UUID.randomUUID());
        }
        users.saveAndFlush(user);
    }
    private UserEntity lockedOwner(){db.queryForList("SELECT id FROM app_role WHERE code='ADMIN' FOR UPDATE",Long.class);return users.findLockedById(current.requireCurrent().getId()).orElseThrow();}
    private void guard(UserEntity user){
        if(aiLimits.hasActive(user.getId()))throw bad("AI_TASK_ACTIVE","请等待正在处理的个人 AI 请求结束后再操作");
        if("ADMIN".equals(user.getRole().getCode())){var admins=users.findLockedActiveAdminIds(user.getRole().getId());if(admins.size()<2)throw bad("LAST_ADMIN","这是平台仅剩的管理员，请先交接管理权限");}
        if(db.queryForObject("SELECT COUNT(*) FROM workspace WHERE owner_id=? AND dissolved=FALSE",Long.class,user.getId())>0)throw bad("GROUP_OWNER_ACTIVE","请先转让或解散自己拥有的群组");
        if(db.queryForObject("SELECT COUNT(*) FROM inference_task WHERE requested_by=? AND status IN ('PENDING','RUNNING')",Long.class,user.getId())>0)throw bad("TASKS_ACTIVE","请等待正在运行的实验结束后再操作");
    }
    public void resume(UserEntity user,String password,String otp){
        if(password==null||password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72||!passwords.matches(password,user.getPasswordHash()))throw new BusinessException(HttpStatus.UNAUTHORIZED,"LOGIN_FAILED","身份信息或密码不正确");
        var rows=db.queryForList("SELECT discoverable FROM account_closure WHERE owner_id=? AND mode='SUSPEND'",Boolean.class,user.getId());
        if(user.getStatus()!=UserStatus.DISABLED||rows.isEmpty())throw bad("REACTIVATE_UNAVAILABLE","此账号不能通过自助恢复，请核对身份信息或联系管理员");
        user.setStatus(UserStatus.ACTIVE);boolean allowed=user.hasActiveAccess(clock.instant());user.setStatus(UserStatus.DISABLED);
        if(!allowed)throw bad("ACCOUNT_ACCESS_EXPIRED","账号使用期限已结束，请联系管理员");security.checkLogin(user,otp);
        user.setStatus(UserStatus.ACTIVE);user.setDiscoverable(rows.get(0));users.saveAndFlush(user);db.update("DELETE FROM account_closure WHERE owner_id=?",user.getId());
    }
    private void password(UserEntity user,String value){if(value==null||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72||!passwords.matches(value,user.getPasswordHash()))throw bad("PASSWORD_INVALID","当前密码不正确");}
    private static BusinessException bad(String code,String message){return new BusinessException(HttpStatus.BAD_REQUEST,code,message);}
}
