package com.coreintra.core.json;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.businesstime.BusinessInstantParseException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;

/**
 * Wire mapping for {@link BusinessInstant}: a JSON string in the canonical form
 * {@code YYYY-MM-DDT[-]HH:MM:SS.mmm}.
 *
 * <p>Registered as a Spring bean, so it applies to every controller and to the
 * MCP server without either having to remember.
 *
 * <p>Deserialisation refuses anything that is not a JSON string. A numeric
 * epoch would be an absolute instant, which is the confusion this type exists
 * to prevent, and an object form would let a caller supply {@code absoluteTs}
 * as if it were input when it is derived.
 */
public class BusinessTimeJacksonModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    public BusinessTimeJacksonModule() {
        super("coreintra-business-time");
        addSerializer(BusinessInstant.class, new Serializer());
        addDeserializer(BusinessInstant.class, new Deserializer());
    }

    static final class Serializer extends JsonSerializer<BusinessInstant> {
        @Override
        public void serialize(BusinessInstant value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(value.toWireString());
        }
    }

    static final class Deserializer extends JsonDeserializer<BusinessInstant> {
        @Override
        public BusinessInstant deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                throw new BusinessInstantParseException(
                        String.valueOf(parser.getText()),
                        "expected a JSON string in the form YYYY-MM-DDT[-]HH:MM:SS.mmm. A number "
                                + "would be an absolute instant, and business time is not an "
                                + "absolute instant");
            }
            return BusinessInstant.parse(parser.getText());
        }
    }
}
