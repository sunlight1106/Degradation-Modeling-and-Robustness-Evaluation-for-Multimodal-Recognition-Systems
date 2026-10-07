package com.robustvision.platform.service;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.repository.*;
import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.dto.ApiDtos;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import java.time.*;
import java.util.*;

@Service
public class LearningWorkspaceService {
    private final LearningRecordRepository records;private final CurrentUserService current;
    private final ObjectMapper mapper;private final KnowledgeSearchService sources;private final NoteService notes;
    public LearningWorkspaceService(LearningRecordRepository records,CurrentUserService current,ObjectMapper mapper,KnowledgeSearchService sources,NoteService notes){this.records=records;this.current=current;this.mapper=mapper;this.sources=sources;this.notes=notes;}
    public record RecordView(String id,String kind,String title,JsonNode data,long revision,Instant updatedAt) {}
    public record Save(@NotBlank @Size(max=180) String title,@NotNull JsonNode data,@Min(0) long revision) {}
    public record Grade(@Min(0) @Max(3) int grade,@Min(0) long revision) {}
    public record Capture(@NotBlank String kind,@NotBlank @Size(max=36) String id) {}
    @Transactional(readOnly=true)
    public List<RecordView> list(String kind,int page){validKind(kind);return records.findByOwnerIdAndKindOrderByUpdatedAtDesc(current.requireCurrent().getId(),kind,PageRequest.of(Math.max(0,Math.min(page,10000)),50)).stream().map(this::view).toList();}
    @Transactional(readOnly=true)
    public RecordView get(String id){return view(owned(id));}
    @Transactional
    public RecordView save(String kind,String id,Save input){
        validKind(kind);long owner=current.requireCurrent().getId();LearningRecordEntity record;
        if(id==null){if(records.countByOwnerIdAndKind(owner,kind)>=2000)throw bad("当前类型最多保存 2000 项");record=new LearningRecordEntity(owner,kind,input.title().trim(),"{}");}
        else {record=owned(id);if(!record.kind.equals(kind))throw missing();if(record.revision!=input.revision())throw conflict();}
        ObjectNode data=validate(kind,input.data());
        if(kind.equals("CARD")) {
            JsonNode previous=id==null?mapper.createObjectNode():parse(record.payload);
            data.put("due",previous.path("due").asText(Instant.now().toString()));data.put("stage",previous.path("stage").asInt(0));data.put("reviews",previous.path("reviews").asInt(0));data.put("mistakes",previous.path("mistakes").asInt(0));
        }
        record.title=input.title().trim();record.payload=data.toString();record.updatedAt=Instant.now();return view(records.saveAndFlush(record));
    }
    @Transactional
    public RecordView grade(String id,Grade input){
        LearningRecordEntity r=owned(id);if(!r.kind.equals("CARD"))throw missing();if(r.revision!=input.revision())throw conflict();
        ObjectNode data=(ObjectNode)parse(r.payload);int stage=input.grade()==0?0:Math.min(9,data.path("stage").asInt()+1);
        long seconds=input.grade()==0?600:input.grade()==1?86400:Math.min(180,1L<<stage)*86400*(input.grade()==3?2:1);
        data.put("stage",stage);data.put("due",Instant.now().plusSeconds(seconds).toString());data.put("reviews",data.path("reviews").asInt()+1);data.put("mistakes",data.path("mistakes").asInt()+(input.grade()==0?1:0));
        r.payload=data.toString();r.updatedAt=Instant.now();return view(records.saveAndFlush(r));
    }
    @Transactional
    public void delete(String id,long revision){LearningRecordEntity r=owned(id);if(r.revision!=revision)throw conflict();records.delete(r);}
    @Transactional
    public ApiDtos.NoteView capture(Capture ref){var s=sources.source(ref.kind(),ref.id());return notes.create(new ApiDtos.CreateNoteRequest(s.title(),"# "+s.title()+"\n\n"+s.body()+"\n\n来源：[查看原始资料]("+s.url()+")",null,"DRAFT","综合学习","MARKDOWN",null));}
    private ObjectNode validate(String kind,JsonNode n){
        if(!n.isObject()||n.toString().length()>180000)throw bad("内容格式不正确或超过长度限制");
        ObjectNode d=n.deepCopy();
        if(kind.equals("CARD")){
            text(d,"question",2000,true);text(d,"answer",10000,true);text(d,"subject",80,false);text(d,"sourceKind",20,false);text(d,"sourceId",36,false);
            if(!d.path("sourceId").asText().isBlank())sources.source(d.path("sourceKind").asText(),d.path("sourceId").asText());
        } else if(kind.equals("COLLECTION")){
            if(!d.path("fields").isArray()||d.path("fields").size()>16||!d.path("rows").isArray()||d.path("rows").size()>500)throw bad("最多 16 个字段、500 行资料");
            Set<String> names=new HashSet<>();for(JsonNode f:d.path("fields")){text(f,"name",40,true);if(!names.add(f.path("name").asText())||!Set.of("text","date","select","number").contains(f.path("type").asText()))throw bad("字段名不能重复，类型需为文字、日期、选项或数字");}
            Set<String> ids=new HashSet<>();for(JsonNode row:d.path("rows")){text(row,"id",36,true);if(!ids.add(row.path("id").asText()))throw bad("资料行标识不能重复");text(row,"title",180,true);if(!row.path("values").isObject())throw bad("请填写资料属性");for(JsonNode f:d.path("fields")){JsonNode value=row.path("values").path(f.path("name").asText());if(value.isMissingNode()||value.asText().isBlank())continue;if(!value.isValueNode()||value.asText().length()>2000)throw bad("字段内容过长");try{if(f.path("type").asText().equals("date"))LocalDate.parse(value.asText());if(f.path("type").asText().equals("number"))new java.math.BigDecimal(value.asText());}catch(Exception e){throw bad("日期或数字格式不正确");}}}
        }
        return d;
    }
    void text(JsonNode n,String key,int max,boolean required){JsonNode v=n.path(key);if((required&&(!v.isTextual()||v.asText().isBlank()))||(!v.isMissingNode()&&!v.isTextual())||v.asText().length()>max)throw bad("字段 "+key+" 格式或长度不正确");}
    LearningRecordEntity owned(String id){return records.findByIdAndOwnerId(id,current.requireCurrent().getId()).orElseThrow(this::missing);}
    RecordView view(LearningRecordEntity r){return new RecordView(r.id,r.kind,r.title,parse(r.payload),r.revision,r.updatedAt);}
    JsonNode parse(String s){try{return mapper.readTree(s);}catch(Exception e){throw new IllegalStateException("Stored learning record is invalid");}}
    private void validKind(String kind){if(!Set.of("CARD","COLLECTION").contains(kind))throw bad("不支持的资料类型");}
    private BusinessException missing(){return new BusinessException(HttpStatus.NOT_FOUND,"RECORD_NOT_FOUND","资料不存在或无权访问");}
    private BusinessException conflict(){return new BusinessException(HttpStatus.CONFLICT,"RECORD_CONFLICT","资料已在其他页面更新，请保留当前内容并重新加载");}
    private BusinessException bad(String message){return new BusinessException(HttpStatus.BAD_REQUEST,"RECORD_INVALID",message);}
}
