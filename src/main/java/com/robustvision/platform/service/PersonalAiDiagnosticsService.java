package com.robustvision.platform.service;
import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.repository.PersonalAiSettingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
@Service
public class PersonalAiDiagnosticsService {
 private final CurrentUserService current;private final PersonalAiSettingRepository settings;private final PersonalAiTransport transport;private final SecretEncryptionService encryption;private final PersonalAiEndpointPolicy endpoints;private final PersonalAiRateLimiter limits;private final PersonalAiPersistenceService persistence;private final boolean remote;
 private final Map<String,Pending> pending=new HashMap<>();
 private record Pending(long owner,String setting,long revision,String mode,Instant expires){}
 public record Preview(String token,String mode,String model,String endpoint,String request,Instant expiresAt){}
 public record Result(boolean success,String code,String message,long milliseconds,List<String> models,Long inputTokens,Long outputTokens,String usageStatus){}
 public PersonalAiDiagnosticsService(CurrentUserService current,PersonalAiSettingRepository settings,PersonalAiTransport transport,SecretEncryptionService encryption,PersonalAiEndpointPolicy endpoints,PersonalAiRateLimiter limits,PersonalAiPersistenceService persistence,@Value("${app.personal-ai.remote-enabled:false}") boolean remote){this.current=current;this.settings=settings;this.transport=transport;this.encryption=encryption;this.endpoints=endpoints;this.limits=limits;this.persistence=persistence;this.remote=remote;}
 private BusinessException bad(String code,String message){return new BusinessException(HttpStatus.BAD_REQUEST,code,message);}
 public Preview preview(AiProvider provider,String mode){
  Long owner=current.requireCurrent().getId();if(!Set.of("MODELS","CONNECTION").contains(mode))throw bad("DIAGNOSTIC_INVALID","检查类型无效");
  var setting=settings.findByOwnerIdAndProvider(owner,provider).filter(s->s.isEnabled()&&s.getEncryptedKey()!=null&&!s.getEncryptedKey().isBlank()).orElseThrow(()->bad("PERSONAL_AI_CONFIG_REQUIRED","先保存并启用自己的供应商配置"));
  if(!remote)throw bad("PERSONAL_AI_REMOTE_DISABLED","部署者尚未开启外部模型调用。请先启用 PERSONAL_AI_REMOTE_ENABLED，再进行接入检查");
  String base=endpoints.validateBase(provider,setting.getBaseUrl());String token=UUID.randomUUID().toString()+UUID.randomUUID();Instant expires=Instant.now().plusSeconds(300);limits.preview(owner);
  synchronized(pending){pending.entrySet().removeIf(e->e.getValue().expires().isBefore(Instant.now())||e.getValue().owner()==owner);if(pending.size()>=256)throw bad("DIAGNOSTIC_BUSY","检查服务繁忙，请稍后再试");pending.put(token,new Pending(owner,setting.getId(),setting.getRevision(),mode,expires));}
  return new Preview(token,mode,setting.getModel(),base,mode.equals("MODELS")?"GET /models，仅查询模型目录":"仅发送固定短句 Reply with OK only.，输出上限 128 tokens。不发送笔记、记忆或文件，供应商可能计费。",expires);
 }
 public Result execute(String token,boolean confirmed){
  Long owner=current.requireCurrent().getId();if(!confirmed)throw bad("DIAGNOSTIC_CONFIRM_REQUIRED","请确认发送预览中的检查请求");Pending approved;
  synchronized(pending){approved=pending.get(token);if(approved==null||approved.owner()!=owner)throw bad("DIAGNOSTIC_EXPIRED","预览无效，请重新检查");pending.remove(token);}
  if(!approved.expires().isAfter(Instant.now()))throw bad("DIAGNOSTIC_EXPIRED","预览已经过期");var setting=settings.findById(approved.setting()).filter(s->s.getOwnerId().equals(owner)&&s.getRevision()==approved.revision()&&s.isEnabled()).orElseThrow(()->bad("DIAGNOSTIC_CHANGED","配置已更改，请重新预览"));
  long start=System.nanoTime();try(var permit=limits.acquire(owner)){
   persistence.requireActiveOwner(owner);
   String key=encryption.decrypt(setting.getEncryptedKey());
   try {
    if(approved.mode().equals("MODELS")){var models=transport.models(setting.getProvider(),setting.getBaseUrl(),key);return new Result(true,"MODELS_RECEIVED",models.contains(setting.getModel())?"模型目录包含当前模型。实际调用能力请再做连接测试。":"已获取首批模型；未找到当前模型不代表一定不可用，可继续连接测试。",(System.nanoTime()-start)/1000000,models,null,null,"NOT_APPLICABLE");}
    var result=transport.execute(setting.getProvider(),transport.prepareDiagnostic(setting.getProvider(),setting.getBaseUrl(),setting.getModel()),key);String status="SAVED";
    try{persistence.recordUsage(new PersonalAiUsageEntity(owner,setting.getProvider(),setting.getModel(),"diagnostic","SUCCEEDED",result.inputTokens(),result.outputTokens(),null));}catch(RuntimeException ignored){status="UNCONFIRMED";}
    return new Result(true,"CONNECTED","当前模型已返回文本，可用于文字调用。图片能力需单独验证。",(System.nanoTime()-start)/1000000,List.of(),result.inputTokens(),result.outputTokens(),status);
   }catch(BusinessException e){if(approved.mode().equals("CONNECTION"))try{persistence.recordUsage(new PersonalAiUsageEntity(owner,setting.getProvider(),setting.getModel(),"diagnostic","FAILED",null,null,e.getCode()));}catch(RuntimeException ignored){}return new Result(false,e.getCode(),e.getMessage(),(System.nanoTime()-start)/1000000,List.of(),null,null,"UNCONFIRMED");}
  }
 }
}
