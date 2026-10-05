package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(showSql=false)
@Import({PersonalAiMemoryService.class, CurrentUserService.class})
class PersonalAiMemoryIntegrationTest {
    @Autowired TestEntityManager em;
    @Autowired PersonalAiMemoryService service;
    @AfterEach void logout(){SecurityContextHolder.clearContext();}
    private UserEntity user(String username,String roleCode){
        var role=em.persist(new RoleEntity(roleCode,roleCode,"test",Set.of()));
        return em.persist(new UserEntity(username,"test",username,username+"@example.test",role));
    }
    private void login(UserEntity user){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getUsername(),"",List.of()));}
    @Test void ownersIncludingAdminCannotReadChangeOrDeleteOthersAndStalePreviewsExpire(){
        var alice=user("memory_alice","MEMORY_USER");var admin=user("memory_admin","ADMIN");em.flush();
        login(alice);
        var created=service.save(null,new PersonalAiMemoryService.Request("学习偏好","用 Java 举例",true,null));
        var snapshot=service.snapshot(alice.getId());
        assertThat(snapshot.context()).contains("用 Java 举例");
        service.verify(alice.getId(),snapshot.digest());
        login(admin);
        assertThat(service.list()).isEmpty();
        assertThat(service.snapshot(admin.getId()).context()).isEmpty();
        assertThatThrownBy(()->service.save(created.id(),new PersonalAiMemoryService.Request("stolen","stolen",true,created.revision()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.delete(created.id(),created.revision())).isInstanceOf(BusinessException.class);
        login(alice);
        var updated=service.save(created.id(),new PersonalAiMemoryService.Request("学习偏好","先解释原理",true,created.revision()));
        assertThat(updated.revision()).isGreaterThan(created.revision());
        assertThatThrownBy(()->service.verify(alice.getId(),snapshot.digest())).hasMessageContaining("个人记忆已修改");
        assertThatThrownBy(()->service.delete(created.id(),created.revision())).hasMessageContaining("已更改");
        var disabled=service.save(created.id(),new PersonalAiMemoryService.Request(updated.title(),updated.body(),false,updated.revision()));
        assertThat(service.snapshot(alice.getId()).context()).isEmpty();
        service.delete(disabled.id(),disabled.revision());
        assertThat(service.list()).isEmpty();
    }
    @Test void enabledContentHasATotalLimitAndDisabledContentIsExcluded(){
        var alice=user("memory_quota","MEMORY_QUOTA");em.flush();login(alice);
        for(int i=0;i<5;i++)service.save(null,new PersonalAiMemoryService.Request("记忆"+i,"a".repeat(1000),true,null));
        assertThatThrownBy(()->service.save(null,new PersonalAiMemoryService.Request("超过总量","a".repeat(1000),true,null))).hasMessageContaining("6000");
        service.save(null,new PersonalAiMemoryService.Request("停用记忆","NEVER_SEND",false,null));
        assertThat(service.snapshot(alice.getId()).context()).doesNotContain("NEVER_SEND");
    }
}
