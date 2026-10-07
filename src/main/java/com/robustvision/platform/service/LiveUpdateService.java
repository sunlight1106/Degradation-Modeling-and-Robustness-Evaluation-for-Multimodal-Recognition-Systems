package com.robustvision.platform.service;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.transaction.support.*;
import com.robustvision.platform.common.BusinessException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.util.concurrent.*;

/** Events contain no private content; clients re-fetch through normal authorization. */
@Service
public class LiveUpdateService {
    private final Map<Long,Set<SseEmitter>> clients=new ConcurrentHashMap<>();
    public synchronized SseEmitter connect(long user){
        Set<SseEmitter> list=clients.computeIfAbsent(user,k->ConcurrentHashMap.newKeySet());
        if(list.size()>=6)throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,"LIVE_LIMIT","实时连接过多，请关闭多余页面");
        SseEmitter emitter=new SseEmitter(25000L);list.add(emitter);
        Runnable remove=()->{synchronized(this){list.remove(emitter);if(list.isEmpty())clients.remove(user,list);}};
        emitter.onCompletion(remove);emitter.onTimeout(()->{remove.run();emitter.complete();});emitter.onError(e->remove.run());
        try{emitter.send(SseEmitter.event().name("ready").data("connected"));}catch(Exception e){remove.run();}
        return emitter;
    }
    public void changed(Collection<Long> users){
        Set<Long> ids=new HashSet<>(users);
        Runnable publish=()->{for(long id:ids)for(SseEmitter e:clients.getOrDefault(id,Set.of()))try{e.send(SseEmitter.event().name("refresh").data("changed"));}catch(Exception ex){e.complete();}};
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){publish.run();}});else publish.run();
    }
}
