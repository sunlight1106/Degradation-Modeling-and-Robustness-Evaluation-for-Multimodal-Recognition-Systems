package com.robustvision.platform.service;
import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ModelTransportTest {
    @Test void legacyLiveAndCustomHttpModesCannotReachPooledKeysOrAnyUpstream() {
        for(String mode:new String[]{"live","http","unknown"}) {
            var service=new ModelInvocationService(mode);
            assertThatThrownBy(()->service.invoke(null,null,TaskType.RECEIPT,false,"synthetic"))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("共享密钥实验已停用");
            assertThat(service.runtimeInfo().credentialConfigured()).isFalse();
            assertThat(service.runtimeInfo().capabilities().get("inference")).isFalse();
        }
    }
    @Test void demoOutputRemainsExplicitlySyntheticAndCostFree() {
        var service=new ModelInvocationService("demo");
        var file=new FileAssetEntity("synthetic.png","unused","image/png",3,"0".repeat(64),"unused",null,FileSource.UPLOAD,FileScanStatus.SKIPPED,"test");
        var model=new ModelDefinitionEntity("demo","Synthetic","1",ModelProvider.DEEPSEEK,TaskType.RECEIPT,"fixture");
        var result=service.invoke(file,model,TaskType.RECEIPT,false,"synthetic");
        assertThat(result.payload().get("adapter")).isEqualTo("DEMO");
        assertThat(result.inputTokens()).isZero();assertThat(result.outputTokens()).isZero();
        assertThat(result.provider()).isEqualTo(ModelProvider.DEMO);
    }
}
