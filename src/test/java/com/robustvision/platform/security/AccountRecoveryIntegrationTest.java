package com.robustvision.platform.security;
import com.fasterxml.jackson.databind.*;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.repository.*;
import com.robustvision.platform.service.Totp;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:account_recovery;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","app.bootstrap.enabled=false","app.worker.enabled=false","app.rate-limit.enabled=false","app.mail.from=synthetic@example.invalid","spring.mail.host=synthetic.example.invalid"})
@AutoConfigureMockMvc @Import(GroupCommunicationIntegrationTest.FastPasswords.class)
class AccountRecoveryIntegrationTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired UserRepository users;@Autowired RoleRepository roles;@Autowired PasswordEncoder passwords;@Autowired JdbcTemplate db;
 @MockBean JavaMailSender sender;UserEntity user;String token;static final String PASSWORD="SyntheticAccount123!";
 @BeforeEach void setup() throws Exception {String name="secure"+UUID.randomUUID().toString().substring(0,8);var role=roles.save(new RoleEntity("SEC_"+name,"Synthetic","Synthetic",Set.of("dashboard:read")));user=users.save(new UserEntity(name,passwords.encode(PASSWORD),name,name+"@example.invalid",role));token=login(Map.of("username",name,"password",PASSWORD)).path("token").asText();}
 JsonNode data(MvcResult r)throws Exception{return mapper.readTree(r.getResponse().getContentAsByteArray()).path("data");}
 ResultActions call(String path,Object body,String auth)throws Exception{var r=post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));if(auth!=null)r.header("Authorization","Bearer "+auth);return mvc.perform(r);}
 JsonNode login(Object body)throws Exception{return data(call("/api/v1/auth/login",body,null).andExpect(status().isOk()).andReturn());}
 String mailedToken() {var captor=ArgumentCaptor.forClass(SimpleMailMessage.class);verify(sender,timeout(3000)).send(captor.capture());return captor.getValue().getText().split("#token=")[1].split("\\s")[0];}
 @Test void verifiedEmailRecoveryConsumesTokenAndRevokesAllSessions()throws Exception {
  call("/api/v1/auth/forgot-password",Map.of("email",user.getEmail()),null).andExpect(status().isOk());verifyNoInteractions(sender);
  call("/api/v1/account/security/email",Map.of("password",PASSWORD),token).andExpect(status().isOk());String verifyToken=mailedToken();
  call("/api/v1/auth/verify-email",Map.of("token",verifyToken),null).andExpect(status().isOk());call("/api/v1/auth/verify-email",Map.of("token",verifyToken),null).andExpect(status().isBadRequest());
  reset(sender);db.update("UPDATE account_challenge SET created_at=? WHERE owner_id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(65)),user.getId());
  call("/api/v1/auth/forgot-password",Map.of("email",user.getEmail()),null).andExpect(status().isOk());String resetToken=mailedToken();
  call("/api/v1/auth/reset-password",Map.of("token",resetToken,"password","SyntheticNew456!"),null).andExpect(status().isOk());
  mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
  call("/api/v1/auth/reset-password",Map.of("token",resetToken,"password","SyntheticNew789!"),null).andExpect(status().isBadRequest());
  login(Map.of("username",user.getUsername(),"password","SyntheticNew456!"));
 }
 @Test void mfaCannotBeBypassedAndRecoveryCodesAreOneUse()throws Exception {
  var setup=data(call("/api/v1/account/security/mfa/setup",Map.of("password",PASSWORD),token).andExpect(status().isOk()).andReturn());String secret=setup.path("secret").asText();
  var codes=data(call("/api/v1/account/security/mfa/enable",Map.of("password",PASSWORD,"code",Totp.code(secret,java.time.Instant.now().getEpochSecond()/30)),token).andExpect(status().isOk()).andReturn());
  assertThat(codes.size()).isEqualTo(8);assertThat(db.queryForObject("SELECT mfa_secret FROM account_security WHERE owner_id=?",String.class,user.getId())).doesNotContain(secret);
  call("/api/v1/auth/login",Map.of("username",user.getUsername(),"password",PASSWORD),null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("MFA_REQUIRED"));
  String code=codes.get(0).asText();login(Map.of("username",user.getUsername(),"password",PASSWORD,"otp",code));
  call("/api/v1/auth/login",Map.of("username",user.getUsername(),"password",PASSWORD,"otp",code),null).andExpect(status().isBadRequest());
  assertThat(db.queryForObject("SELECT failed_attempts FROM account_security WHERE owner_id=?",Integer.class,user.getId())).isEqualTo(1);
  mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
 }
 @Test void changedEmailInvalidatesPreviouslyIssuedVerification()throws Exception {
  call("/api/v1/account/security/email",Map.of("password",PASSWORD),token).andExpect(status().isOk());String issued=mailedToken();db.update("UPDATE app_user SET email=? WHERE id=?","changed-"+user.getUsername()+"@example.invalid",user.getId());
  call("/api/v1/auth/verify-email",Map.of("token",issued),null).andExpect(status().isBadRequest());
 }
}
