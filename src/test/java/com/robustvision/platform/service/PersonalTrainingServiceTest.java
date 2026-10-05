package com.robustvision.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.domain.UserEntity;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonalTrainingServiceTest {
    @Test void ownerIsTakenOnlyFromSessionAndDownloadsAlsoRequireIt() throws Exception {
        var received=new AtomicReference<String>();var auth=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            received.set(exchange.getRequestHeaders().getFirst("X-Training-Owner"));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body="[]".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var current=mock(CurrentUserService.class);var user=mock(UserEntity.class);
            when(current.requireCurrent()).thenReturn(user);when(user.getId()).thenReturn(72L);
            var service=new PersonalTrainingService(current,new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),"synthetic-test-training-token-123456");
            service.list();assertThat(received.get()).isEqualTo("72");assertThat(auth.get()).startsWith("Bearer ");
            when(user.getId()).thenReturn(99L);service.download(UUID.randomUUID());assertThat(received.get()).isEqualTo("99");
            service.cancel(UUID.randomUUID());assertThat(received.get()).isEqualTo("99");
        } finally {server.stop(0);}
    }
}
