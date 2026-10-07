package com.robustvision.platform.service;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.robustvision.platform.domain.LearningRecordEntity;
import com.robustvision.platform.repository.LearningRecordRepository;
import com.robustvision.platform.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Evaluations freeze labels, predictions and model metadata. Missing outputs count as failures. */
@Service
public class BenchmarkService {
    private final LearningRecordRepository records;private final CurrentUserService current;private final ObjectMapper mapper;private final LearningWorkspaceService learning;
    public BenchmarkService(LearningRecordRepository records,CurrentUserService current,ObjectMapper mapper,LearningWorkspaceService learning){this.records=records;this.current=current;this.mapper=mapper;this.learning=learning;}
    public record Row(@NotBlank @Size(max=80) String sample,@NotBlank @Size(max=500) String expected,
        @NotBlank @Size(max=120) String model,@NotBlank @Size(max=80) String version,
        @Size(max=500) String output,@NotNull @Pattern(regexp="OK|FAILED") String status,
        @Min(0) @Max(3600000) Long latencyMs,@DecimalMin("0") @DecimalMax("100000") BigDecimal costCny) {}
    public record Evaluation(@NotBlank @Size(max=180) String title,@NotBlank @Size(max=2000) String parameters,
        @NotBlank @Pattern(regexp="DEMO|IMPORTED") String provenance,boolean ignoreCase,
        @NotEmpty @Size(max=500) List<@Valid Row> rows) {}
    @Transactional(readOnly=true)
    public List<LearningWorkspaceService.RecordView> list(){return records.findByOwnerIdAndKindOrderByUpdatedAtDesc(current.requireCurrent().getId(),"EVALUATION",PageRequest.of(0,100)).stream().map(learning::view).toList();}
    @Transactional
    public LearningWorkspaceService.RecordView evaluate(Evaluation request){
        long owner=current.requireCurrent().getId();if(records.countByOwnerIdAndKind(owner,"EVALUATION")>=2000)throw bad("评测报告已达上限");
        SortedMap<String,String> labels=new TreeMap<>();Map<List<String>,Map<String,Row>> models=new LinkedHashMap<>();
        for(Row row:request.rows()){
            String expected=labels.putIfAbsent(row.sample(),row.expected());if(expected!=null&&!expected.equals(row.expected()))throw bad("同一样本的标准答案不一致："+row.sample());
            List<String> key=List.of(row.model(),row.version());if(models.computeIfAbsent(key,k->new HashMap<>()).putIfAbsent(row.sample(),row)!=null)throw bad("同一模型版本的样本重复："+row.sample());
            if(row.status().equals("OK")&&row.output()==null)throw bad("成功的样本必须提供输出");
        }
        ObjectNode payload=mapper.valueToTree(request);payload.put("datasetHash",hash(labels));payload.put("sampleCount",labels.size());
        ArrayNode metrics=payload.putArray("metrics");
        for(var model:models.entrySet()){
            int exact=0,failed=0,edits=0,chars=0,latencies=0,costs=0;long latency=0;BigDecimal cost=BigDecimal.ZERO;
            for(var label:labels.entrySet()){
                Row row=model.getValue().get(label.getKey());String expected=normalize(label.getValue(),request.ignoreCase());chars+=expected.codePointCount(0,expected.length());
                if(row==null||!row.status().equals("OK")){failed++;edits+=expected.codePointCount(0,expected.length());continue;}
                String output=normalize(row.output(),request.ignoreCase());if(output.equals(expected))exact++;edits+=distance(expected,output);
                if(row.latencyMs()!=null){latency+=row.latencyMs();latencies++;}if(row.costCny()!=null){cost=cost.add(row.costCny());costs++;}
            }
            ObjectNode m=metrics.addObject();m.put("model",String.join(" / ",model.getKey()));m.put("modelId",model.getKey().get(0));m.put("version",model.getKey().get(1));m.put("samples",labels.size());m.put("exactMatches",exact);m.put("accuracy",(double)exact/labels.size());m.put("failed",failed);m.put("characterErrorRate",chars==0?0:(double)edits/chars);
            if(latencies==0)m.putNull("averageLatencyMs");else m.put("averageLatencyMs",(double)latency/latencies);m.put("latencySamples",latencies);
            if(costs==0)m.putNull("knownCostCny");else m.put("knownCostCny",cost);m.put("costSamples",costs);
        }
        return learning.view(records.saveAndFlush(new LearningRecordEntity(owner,"EVALUATION",request.title(),payload.toString())));
    }
    static String normalize(String value,boolean lower){String s=java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).strip();return lower?s.toLowerCase(Locale.ROOT):s;}
    static int distance(String first,String second){int[] a=first.codePoints().toArray(),b=second.codePoints().toArray(),prev=new int[b.length+1];for(int j=0;j<=b.length;j++)prev[j]=j;for(int i=1;i<=a.length;i++){int[] next=new int[b.length+1];next[0]=i;for(int j=1;j<=b.length;j++)next[j]=Math.min(Math.min(prev[j]+1,next[j-1]+1),prev[j-1]+(a[i-1]==b[j-1]?0:1));prev=next;}return prev[b.length];}
    private String hash(SortedMap<String,String> labels){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(labels).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private BusinessException bad(String message){return new BusinessException(HttpStatus.BAD_REQUEST,"EVALUATION_INVALID",message);}
}
