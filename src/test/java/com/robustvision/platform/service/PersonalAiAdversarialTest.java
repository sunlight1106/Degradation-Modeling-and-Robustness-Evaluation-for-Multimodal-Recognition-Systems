package com.robustvision.platform.service;
import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.dto.PersonalAiDtos.*;
import com.robustvision.platform.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class PersonalAiAdversarialTest {
 @Test void simultaneousReplayAdmitsOneOutboundCall() throws Exception {
  var current=mock(CurrentUserService.class);var user=mock(UserEntity.class);when(user.getId()).thenReturn(11L);when(current.requireCurrent()).thenReturn(user);
  when(current.hasPermission(eq(user),anyString())).thenReturn(true);
  var settings=mock(PersonalAiSettingRepository.class);var usage=mock(PersonalAiUsageRepository.class);var sources=mock(NoteExperimentSourceService.class);when(sources.buildContext(anyList())).thenReturn("");
  var crypto=new SecretEncryptionService("synthetic-review-only-master-key-32-bytes");
  var setting=new PersonalAiSettingEntity(11L,AiProvider.OPENAI);setting.update("model","https://api.openai.com/v1",crypto.encrypt("synthetic-review-api-key"),true);when(settings.findByOwnerIdAndProvider(11L,AiProvider.OPENAI)).thenReturn(Optional.of(setting));
  var policy=new PersonalAiEndpointPolicy("");var transport=spy(new PersonalAiTransport(new ObjectMapper(),policy));var sent=new AtomicInteger();
  doAnswer(call->{sent.incrementAndGet();return new PersonalAiTransport.Completion("fixture",3,2);}).when(transport).execute(any(),any(),any());
  var service=new PersonalAiService(current,settings,new PersonalAiPersistenceService(usage,mock(PersonalRecognitionResultRepository.class),mock(org.springframework.transaction.PlatformTransactionManager.class),PersonalAiTestOwners.active()),crypto,policy,transport,new PersonalAiRateLimiter(),sources, mock(PersonalAiMemoryService.class), true);
  var preview=service.preview(new PreviewRequest(AiProvider.OPENAI,"summarize","title","approved",List.of()));
  var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(4);List<Future<Boolean>> outcomes=new ArrayList<>();
  try {
   for(int i=0;i<4;i++) outcomes.add(pool.submit(()->{start.await();try{service.execute(new ExecuteRequest(preview.previewToken(),true));return true;}catch(BusinessException e){return false;}}));
   start.countDown();int success=0;for(var f:outcomes)if(f.get(5,TimeUnit.SECONDS))success++;
   assertThat(success).isEqualTo(1);assertThat(sent.get()).isEqualTo(1);verify(usage,times(1)).save(any());
  }finally{start.countDown();pool.shutdownNow();}
 }
 @Test void everyNonceCiphertextOrAuthenticationTagTamperFailsClosed() {
  var crypto=new SecretEncryptionService("synthetic-review-only-master-key-32-bytes");var packed=Base64.getDecoder().decode(crypto.encrypt("synthetic-review-api-key"));
  for(int i=0;i<packed.length;i++){byte[] changed=packed.clone();changed[i]^=1;String altered=Base64.getEncoder().encodeToString(changed);assertThatThrownBy(()->crypto.decrypt(altered)).isInstanceOf(BusinessException.class).hasMessageNotContaining("synthetic-review-api-key");}
  for(String malformed:List.of("not base64!!!","",Base64.getEncoder().encodeToString(new byte[28])))assertThatThrownBy(()->crypto.decrypt(malformed)).isInstanceOf(BusinessException.class);
 }
 @Test void globalConcurrencyCapIsSharedAcrossUsersAndReleased() {
  var limiter=new PersonalAiRateLimiter();List<PersonalAiRateLimiter.Permit> permits=new ArrayList<>();
  try{for(long i=0;i<16;i++)permits.add(limiter.acquire(i));assertThatThrownBy(()->limiter.acquire(99L)).isInstanceOf(BusinessException.class);permits.remove(0).close();try(var permitted=limiter.acquire(99L)){assertThat(permitted).isNotNull();}}finally{permits.forEach(PersonalAiRateLimiter.Permit::close);}
 }
}
