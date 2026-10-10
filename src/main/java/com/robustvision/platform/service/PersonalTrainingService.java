package com.robustvision.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.io.IOException;
import java.util.UUID;

/** No user-supplied endpoint or owner is forwarded. The worker independently enforces ownership. */
@Service
public class PersonalTrainingService {
    private final CurrentUserService current;
    private final ObjectMapper mapper;
    private final String baseUrl, token;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    public PersonalTrainingService(CurrentUserService current, ObjectMapper mapper,
            @Value("${TRAINING_SERVICE_URL:http://training:8090}") String baseUrl,
            @Value("${TRAINING_SERVICE_TOKEN:}") String token) {
        this.current=current; this.mapper=mapper; this.baseUrl=baseUrl.replaceAll("/+$", ""); this.token=token;
    }
    public JsonNode environment(){ return json("GET", "/environment", null); }
    public JsonNode list(){ return json("GET", "/jobs", null); }
    public JsonNode create(JsonNode request){ return json("POST", "/jobs", request); }
    public JsonNode get(UUID id){ return json("GET", "/jobs/"+id, null); }
    public JsonNode cancel(UUID id){ return json("POST", "/jobs/"+id+"/cancel", null); }
    public JsonNode predict(UUID id,JsonNode request){ return json("POST", "/jobs/"+id+"/predict", request); }
    public void delete(UUID id){ send("DELETE", "/jobs/"+id, null); }
    public byte[] download(UUID id){ return send("GET", "/jobs/"+id+"/download", null); }
    private JsonNode json(String method,String path,JsonNode request) {
        try { return mapper.readTree(send(method,path,request)); }
        catch(IOException invalid){ throw unavailable(); }
    }
    private byte[] send(String method,String path,JsonNode request) {
        String owner=current.requireCurrent().getId().toString();
        return sendAs(owner,method,path,request);
    }
    public void purgeOwner(long owner){sendAs(Long.toString(owner),"DELETE","/owner",null);}
    private byte[] sendAs(String owner,String method,String path,JsonNode request) {
        if(token.length()<32)throw unavailable();
        try {
            var body=request==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(request));
            var call=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(15))
                    .header("Authorization","Bearer "+token).header("X-Training-Owner",owner)
                    .header("Content-Type","application/json").method(method,body).build();
            var response=client.send(call,HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try(var stream=response.body()){ bytes=stream.readNBytes(2_000_001); }
            if(bytes.length>2_000_000)throw unavailable();
            int status=response.statusCode();
            if(status>=400 && status<500 && status!=401 && status!=403) {
                String message="训练请求不符合要求，请检查数据和参数";
                if(status==404)message="任务不存在或无权访问";
                else { try { var detail=mapper.readTree(bytes).path("detail"); if(detail.isTextual())message=detail.asText(); }catch(IOException ignored){} }
                throw new BusinessException(HttpStatus.valueOf(status),"TRAINING_REQUEST_REJECTED",message);
            }
            if(status<200 || status>=300)throw unavailable();
            return bytes;
        }catch(InterruptedException interrupted){ Thread.currentThread().interrupt(); throw unavailable(); }
        catch(IOException failure){ throw unavailable(); }
    }
    private BusinessException unavailable(){return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"TRAINING_UNAVAILABLE","本地训练环境尚未就绪，请稍后重试；部署者可运行部署脚本安装训练环境");}
}
