package com.robustvision.platform.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.domain.AiProvider;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PersonalAiUsageAccuracyTest {
    private final PersonalAiTransport transport=new PersonalAiTransport(new ObjectMapper(),new PersonalAiEndpointPolicy(""));
    @Test void incompleteOrRefusedResponsesRetainReportedUsageAcrossProtocols() {
        check(AiProvider.OPENAI,"{\"choices\":[{\"finish_reason\":\"length\"}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":1600}}",100,1600);
        check(AiProvider.ANTHROPIC,"{\"stop_reason\":\"refusal\",\"usage\":{\"input_tokens\":20,\"output_tokens\":8}}",20,8);
        check(AiProvider.GEMINI,"{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}],\"usageMetadata\":{\"promptTokenCount\":9,\"candidatesTokenCount\":16}}",9,16);
    }
    @Test void providerReasoningAndCacheCountersAreNotLost() {
        var gemini=transport.parse(AiProvider.GEMINI,"{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"answer\"}]}}],\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":4,\"thoughtsTokenCount\":8}}");
        assertThat(gemini.outputTokens()).isEqualTo(12);
        var claude=transport.parse(AiProvider.ANTHROPIC,"{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"answer\"}],\"usage\":{\"input_tokens\":10,\"cache_read_input_tokens\":5,\"cache_creation_input_tokens\":6,\"output_tokens\":3}}");
        assertThat(claude.inputTokens()).isEqualTo(21);
    }
    @Test void absentOrInvalidUsageIsUnknownNotZero() {
        var result=transport.parse(AiProvider.OPENAI,"{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"answer\"}}]}");
        assertThat(result.inputTokens()).isNull();assertThat(result.outputTokens()).isNull();
        assertThatThrownBy(()->transport.parse(AiProvider.OPENAI,"{}"))
                .isInstanceOfSatisfying(PersonalAiUpstreamFailure.class,e->{assertThat(PersonalAiUpstreamFailure.input(e)).isNull();assertThat(PersonalAiUpstreamFailure.output(e)).isNull();});
    }
    private void check(AiProvider provider,String json,long input,long output) {
        assertThatThrownBy(()->transport.parse(provider,json)).isInstanceOfSatisfying(PersonalAiUpstreamFailure.class,e->{
            assertThat(PersonalAiUpstreamFailure.input(e)).isEqualTo(input);assertThat(PersonalAiUpstreamFailure.output(e)).isEqualTo(output);
            assertThat(e.getMessage()).contains("仍由供应商计费");
        });
    }
}
