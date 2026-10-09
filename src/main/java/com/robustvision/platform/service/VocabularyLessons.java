package com.robustvision.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.domain.VocabularyWordEntity;
import com.robustvision.platform.dto.VocabularyDtos.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Offline dictionary/collocation cards; no model fees or network dependency during study. */
@Component
public class VocabularyLessons {
    private final ObjectMapper json;
    private final Map<String,Lesson> cards;
    public VocabularyLessons(ObjectMapper json) throws IOException {
        this.json=json;
        try(var raw=getClass().getResourceAsStream("/vocabulary/lessons-v1.json.gz")) {
            if(raw==null)throw new IOException("Missing offline vocabulary lessons");
            try(var input=new GZIPInputStream(raw)){cards=json.readValue(input,new TypeReference<Map<String,Lesson>>(){});}
        }
    }
    public Lesson forWord(VocabularyWordEntity word) {
        var card=cards.get(word.termKey);
        String ipa=word.ipa.isBlank()&&card!=null?card.ipa():word.ipa;
        String cue=card==null?"把「"+word.meaning+"」联想到一个你熟悉的真实场景，再遮住英文回忆。":card.memoryCue();
        String note=card==null?"按这条释义和词性使用；固定介词需要结合具体搭配。":card.usageNote();
        List<Collocation> patterns=card==null?List.of():card.collocations();
        String example=word.exampleText.isBlank()&&card!=null?card.example():word.exampleText;
        String translation=word.exampleTranslation.isBlank()&&card!=null?card.exampleTranslation():word.exampleTranslation;
        if(word.lessonJson!=null)try {
            var custom=json.readValue(word.lessonJson,ImportWord.class);
            if(custom.memoryCue()!=null&&!custom.memoryCue().isBlank())cue=custom.memoryCue();
            if(custom.usageNote()!=null&&!custom.usageNote().isBlank())note=custom.usageNote();
            if(custom.collocations()!=null)patterns=custom.collocations();
        } catch(IOException invalid){throw new IllegalStateException("Invalid stored word lesson",invalid);}
        return new Lesson(word.term,ipa,word.pos,word.meaning,cue,note,patterns,example,translation,
                word.ipa.isBlank()&&card!=null?card.pronunciationSource():"词书音标");
    }
    public ImportWord portableWord(VocabularyWordEntity word) {
        ImportWord custom=null;
        if(word.lessonJson!=null)try {custom=json.readValue(word.lessonJson,ImportWord.class);}catch(IOException invalid){throw new IllegalStateException(invalid);}
        try {
            return new ImportWord(word.term,word.ipa,word.pos,word.meaning,word.exampleText,word.exampleTranslation,
                    json.readValue(word.distractors,new TypeReference<List<String>>(){}),custom==null?null:custom.memoryCue(),custom==null?null:custom.usageNote(),custom==null?null:custom.collocations());
        } catch(IOException invalid){throw new IllegalStateException(invalid);}
    }
}
