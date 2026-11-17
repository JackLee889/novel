package com.java2nb.novel.core.serialize;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.SerializationContext;
import com.java2nb.novel.core.utils.DateUtil;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.Date;

public class TimeAgoFormatSerialize extends ValueSerializer<Date> {

    @Override
    public void serialize(Date s, JsonGenerator jsonGenerator, SerializationContext serializerProvider)
        {

        if (s != null) {
            jsonGenerator.writeString(DateUtil.formatTimeAgo(s));
        }

    }
}