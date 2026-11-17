package com.java2nb.common.xss;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.SerializationContext;
import org.apache.commons.text.StringEscapeUtils;

import java.io.IOException;

public class XssStringJsonSerializer extends ValueSerializer<String> {

    @Override
    public Class<String> handledType() {
        return String.class;
    }

    /**
     * 假如有html代码是自己传来的,需要设定对应的name,不走StringEscapeUtils.escapeHtml4(value)过滤
     */
    @Override
    public void serialize(String value, JsonGenerator jsonGenerator, SerializationContext serializerProvider)
            {
        if (value != null) {
            String encodedValue = StringEscapeUtils.escapeHtml4(value);
            jsonGenerator.writeString(encodedValue);
        }
    }
}
