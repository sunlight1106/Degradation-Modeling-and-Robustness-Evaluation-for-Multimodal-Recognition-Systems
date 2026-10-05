package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.AiProvider;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class PersonalAiEndpointPolicyTest {
    @Test void strictHttpsExactAllowlistAndCanonicalUrls() {
        var policy = new PersonalAiEndpointPolicy("https://private-ai.example/v1");
        assertThat(policy.validateBase(AiProvider.CUSTOM, "https://private-ai.example:443/v1/")).isEqualTo("https://private-ai.example/v1");
        assertThat(policy.validateBase(AiProvider.OPENAI, null)).isEqualTo("https://api.openai.com/v1");
        for (String bad : List.of("http://api.openai.com/v1", "https://api.openai.com.evil.test/v1", "https://api.openai.com@evil.test/v1",
                "https://api.openai.com:8443/v1", "https://api.openai.com/v1?x=y", "https://api.openai.com/v1#fragment",
                "https://api.openai.com./v1", "https://127.0.0.1/v1", "https://[::ffff:127.0.0.1]/v1", "https://api.openai.com/%2e%2e/", "https://api.openai.com/v1/../admin"))
            assertThatThrownBy(() -> policy.validateBase(AiProvider.OPENAI, bad)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new PersonalAiEndpointPolicy("").validateBase(AiProvider.CUSTOM, "https://private-ai.example/v1")).isInstanceOf(BusinessException.class);
    }
    @Test void deniesPrivateReservedMappedIpv6AndMixedDnsAnswers() throws Exception {
        for (String ip : List.of("0.0.0.0", "10.1.1.1", "127.0.0.1", "169.254.169.254", "172.16.0.1", "192.168.0.1",
                "100.64.0.1", "192.0.0.8", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255",
                "::", "::1", "::ffff:127.0.0.1", "::ffff:192.168.1.1", "fc00::1", "fe80::1", "ff02::1", "64:ff9b::a00:1", "2002:a00:1::", "2001:db8::1", "2001::1", "3fff::1")) {
            InetAddress address = InetAddress.getByName(ip);
            assertThat(PersonalAiEndpointPolicy.isPublic(address)).as(ip).isFalse();
        }
        assertThat(PersonalAiEndpointPolicy.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(PersonalAiEndpointPolicy.isPublic(InetAddress.getByName("2606:4700:4700::1111"))).isTrue();
        assertThatThrownBy(() -> PersonalAiEndpointPolicy.validateAddresses(List.of(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1"))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PersonalAiEndpointPolicy.validateAddresses(List.of())).isInstanceOf(BusinessException.class);
    }
}
