package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.UserEntity;
import com.robustvision.platform.repository.UserRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class AccountSecurityService {
    public record State(boolean mailAvailable, boolean emailVerified, boolean mfaEnabled, int recoveryCodesLeft, String delivery) {}
    public record Setup(String secret, String uri) {}
    private final JdbcTemplate db; private final UserRepository users; private final CurrentUserService current;
    private final PasswordEncoder passwords; private final UserSessionService sessions; private final SecretEncryptionService encryption;
    private final ObjectProvider<JavaMailSender> mail; private final String sender, publicUrl, mailHost;
    private final ThreadPoolExecutor mailQueue = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100), r -> { Thread t = new Thread(r, "security-mail"); t.setDaemon(true); return t; });
    public AccountSecurityService(JdbcTemplate db, UserRepository users, CurrentUserService current, PasswordEncoder passwords,
            UserSessionService sessions, SecretEncryptionService encryption, ObjectProvider<JavaMailSender> mail,
            @Value("${app.mail.from:}") String sender, @Value("${app.payment.public-web-url:http://localhost:4173}") String publicUrl, @Value("${spring.mail.host:}") String mailHost) {
        this.db=db; this.users=users; this.current=current; this.passwords=passwords; this.sessions=sessions; this.encryption=encryption; this.mail=mail;
        this.sender=sender; this.mailHost=mailHost; this.publicUrl=publicUrl.replaceAll("/+$", "");
    }
    @PreDestroy void stop() { mailQueue.shutdownNow(); }
    private UserEntity owner() { return users.findLockedById(current.requireCurrent().getId()).orElseThrow(); }
    private Map<String,Object> row(UserEntity user) {
        var rows=db.queryForList("SELECT * FROM account_security WHERE owner_id=?", user.getId());
        if (rows.isEmpty()) { db.update("INSERT INTO account_security(owner_id,recovery_hashes,last_counter,failed_attempts) VALUES (?,?,-1,0)", user.getId(), ""); return row(user); }
        return rows.get(0);
    }
    private static Instant instant(Object o) { return JdbcTime.instant(o); }
    private static String str(Map<String,Object> row, String key) { return Objects.toString(row.get(key), ""); }
    private static BusinessException bad(String code, String message) { return new BusinessException(HttpStatus.BAD_REQUEST,code,message); }
    private void password(UserEntity user, String password) { if(password==null || !passwords.matches(password,user.getPasswordHash())) throw bad("PASSWORD_INVALID","当前密码不正确"); }
    static String hash(String s) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    private static String random() { byte[] b=new byte[24]; new SecureRandom().nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    @Transactional
    public State state() {
        var u=owner(); var r=row(u);
        var deliveries=db.queryForList("SELECT delivery FROM account_challenge WHERE owner_id=? ORDER BY created_at DESC LIMIT 1",u.getId());
        String codes=str(r,"recovery_hashes");
        return new State(mail.getIfAvailable()!=null && !sender.isBlank() && !mailHost.isBlank(), u.getEmail().equals(r.get("verified_email")),r.get("mfa_secret")!=null,codes.isEmpty()?0:codes.split(",").length,deliveries.isEmpty()?"NONE":str(deliveries.get(0),"delivery"));
    }
    @Transactional public Setup setup(String password) {
        var u=owner(); password(u,password); var r=row(u);
        if(r.get("mfa_secret")!=null) throw bad("MFA_ENABLED","请先停用当前双重验证");
        String secret=Totp.secret();
        db.update("UPDATE account_security SET pending_secret=?,pending_until=? WHERE owner_id=?",encryption.encrypt(secret),Timestamp.from(Instant.now().plusSeconds(600)),u.getId());
        return new Setup(secret,"otpauth://totp/PersonalKnowledge:"+java.net.URLEncoder.encode(u.getUsername(),StandardCharsets.UTF_8)+"?secret="+secret+"&issuer=PersonalKnowledge&digits=6&period=30");
    }
    @Transactional public List<String> enable(String password,String code) {
        var u=owner(); password(u,password); var r=row(u);
        if(r.get("pending_secret")==null || !instant(r.get("pending_until")).isAfter(Instant.now())) throw bad("MFA_SETUP_EXPIRED","请重新生成验证器密钥");
        long counter=Totp.match(encryption.decrypt(str(r,"pending_secret")),code,Instant.now().getEpochSecond()/30,-1);
        if(counter<0) throw bad("MFA_INVALID","验证码不正确或已经过期");
        List<String> codes=new ArrayList<>(); for(int i=0;i<8;i++) codes.add(random().substring(0,16));
        db.update("UPDATE account_security SET mfa_secret=pending_secret,pending_secret=NULL,pending_until=NULL,last_counter=?,recovery_hashes=?,failed_attempts=0,locked_until=NULL WHERE owner_id=?",counter,String.join(",",codes.stream().map(AccountSecurityService::hash).toList()),u.getId());
        sessions.revokeAll(u.getId()); return codes;
    }
    @Transactional(noRollbackFor=BusinessException.class) public void disable(String password,String code) {
        var u=owner(); password(u,password); checkLogin(u,code);
        db.update("UPDATE account_security SET mfa_secret=NULL,pending_secret=NULL,pending_until=NULL,recovery_hashes='',last_counter=-1 WHERE owner_id=?",u.getId()); sessions.revokeAll(u.getId());
    }
    public void checkLogin(UserEntity u,String code) {
        var r=row(u); if(r.get("mfa_secret")==null) return;
        if(code==null || code.isBlank()) throw bad("MFA_REQUIRED","请输入验证器验证码或一次性恢复码");
        Instant until=instant(r.get("locked_until")); if(until!=null && until.isAfter(Instant.now())) throw bad("MFA_LOCKED","验证码尝试过多，请五分钟后再试");
        var hashes=new ArrayList<>(Arrays.asList(str(r,"recovery_hashes").split(","))); String candidate=hash(code);
        boolean recovery=hashes.removeIf(h -> !h.isEmpty() && MessageDigest.isEqual(h.getBytes(StandardCharsets.US_ASCII),candidate.getBytes(StandardCharsets.US_ASCII)));
        long counter=recovery?-1:Totp.match(encryption.decrypt(str(r,"mfa_secret")),code,Instant.now().getEpochSecond()/30,((Number)r.get("last_counter")).longValue());
        if(counter<0 && !recovery) { int n=((Number)r.get("failed_attempts")).intValue()+1;
            db.update("UPDATE account_security SET failed_attempts=?,locked_until=? WHERE owner_id=?",n,n>=5?Timestamp.from(Instant.now().plusSeconds(300)):null,u.getId());
            throw bad("MFA_INVALID","验证码错误、已使用或已过期");
        }
        db.update("UPDATE account_security SET last_counter=?,recovery_hashes=?,failed_attempts=0,locked_until=NULL WHERE owner_id=?",counter<0?r.get("last_counter"):counter,String.join(",",hashes),u.getId());
    }
    private void available() { if(mail.getIfAvailable()==null || sender.isBlank() || mailHost.isBlank()) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"MAIL_UNAVAILABLE","部署者尚未配置邮件服务，请联系管理员"); }
    @Transactional public void requestVerification(String password) { available(); var u=owner(); password(u,password); issue(u,"VERIFY"); }
    @Transactional public void forgot(String email) {
        available(); users.findByEmail(email.trim()).ifPresent(found -> {
            var u=users.findLockedById(found.getId()).orElseThrow(); var r=row(u);
            if(u.hasActiveAccess(Instant.now()) && u.getEmail().equals(r.get("verified_email"))) issue(u,"RESET");
        });
    }
    private void issue(UserEntity u,String purpose) {
        var recent=db.queryForList("SELECT id FROM account_challenge WHERE owner_id=? AND created_at>?",u.getId(),Timestamp.from(Instant.now().minusSeconds(60)));
        if(!recent.isEmpty()) return;
        db.update("DELETE FROM account_challenge WHERE owner_id=? AND expires_at<?",u.getId(),Timestamp.from(Instant.now().minusSeconds(86400)));
        String id=UUID.randomUUID().toString(), token=random(); Instant now=Instant.now();
        db.update("INSERT INTO account_challenge(id,owner_id,purpose,email,token_hash,expires_at,created_at,consumed,delivery) VALUES (?,?,?,?,?,?,?,?,?)",id,u.getId(),purpose,u.getEmail(),hash(token),Timestamp.from(now.plusSeconds(1800)),Timestamp.from(now),false,"QUEUED");
        String link=publicUrl+(purpose.equals("VERIFY")?"/verify-email":"/reset-password")+"#token="+id+"."+token;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() {
            try { mailQueue.execute(() -> {
                try { SimpleMailMessage msg=new SimpleMailMessage(); msg.setFrom(sender); msg.setTo(u.getEmail()); msg.setSubject(purpose.equals("VERIFY")?"验证你的邮箱":"重设密码"); msg.setText("打开以下链接完成操作（30 分钟内有效）：\n"+link+"\n\n如果不是你发起的请求，请忽略这封邮件。"); mail.getObject().send(msg); db.update("UPDATE account_challenge SET delivery='SENT' WHERE id=?",id); }
                catch(Exception ignored) { db.update("UPDATE account_challenge SET delivery='FAILED' WHERE id=?",id); }
            }); } catch(RejectedExecutionException ignored) { db.update("UPDATE account_challenge SET delivery='FAILED' WHERE id=?",id); }
        }});
    }
    @Transactional public void consume(String packed, String purpose,String newPassword) {
        if(packed==null || !packed.matches("[a-f0-9-]{36}\\.[A-Za-z0-9_-]{32}")) throw bad("LINK_INVALID","链接无效或已经过期");
        String[] parts=packed.split("\\."); var rows=db.queryForList("SELECT * FROM account_challenge WHERE id=?",parts[0]);
        if(rows.isEmpty()) throw bad("LINK_INVALID","链接无效或已经过期");
        var u=users.findLockedById(((Number)rows.get(0).get("owner_id")).longValue()).orElseThrow();
        var r=db.queryForMap("SELECT * FROM account_challenge WHERE id=? FOR UPDATE",parts[0]);
        if(!purpose.equals(r.get("purpose")) || Boolean.TRUE.equals(r.get("consumed")) || !instant(r.get("expires_at")).isAfter(Instant.now()) || !u.getEmail().equals(r.get("email")) || !u.hasActiveAccess(Instant.now()) || !MessageDigest.isEqual(hash(parts[1]).getBytes(StandardCharsets.US_ASCII),str(r,"token_hash").getBytes(StandardCharsets.US_ASCII))) throw bad("LINK_INVALID","链接无效或已经过期");
        row(u);
        if(purpose.equals("VERIFY")) db.update("UPDATE account_security SET verified_email=? WHERE owner_id=?",u.getEmail(),u.getId());
        else { if(newPassword==null || newPassword.length()<8 || newPassword.getBytes(StandardCharsets.UTF_8).length>72) throw bad("PASSWORD_WEAK","密码至少 8 个字符，且不超过 72 字节"); u.setPasswordHash(passwords.encode(newPassword)); users.save(u); sessions.revokeAll(u.getId()); }
        db.update("UPDATE account_challenge SET consumed=TRUE WHERE owner_id=? AND purpose=?",u.getId(),purpose);
    }
}
