package com.java2nb.novel.core.serialize;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.SerializationContext;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;

public class CommentUserNameSerialize extends ValueSerializer<String> {

    @Override
    public void serialize(String s, JsonGenerator jsonGenerator, SerializationContext serializerProvider) {

        if(StringUtils.isNotBlank(s)){
            jsonGenerator.writeString(s.substring(0, 4) + "****" + s.substring(s.length() - 3));
        }

    }
}