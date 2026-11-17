package com.java2nb.common.jsonserializer;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.SerializationContext;

import java.io.IOException;

public class LongToStringSerializer extends ValueSerializer<Long> {
    @Override
    public void serialize(Long value, JsonGenerator jsonGenerator, SerializationContext serializerProvider) {

        if(value != null){
            jsonGenerator.writeString(value+"");
        }else{
            jsonGenerator.writeNull();
        }

    }
}
