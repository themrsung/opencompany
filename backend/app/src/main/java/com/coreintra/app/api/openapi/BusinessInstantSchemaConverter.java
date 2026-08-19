package com.coreintra.app.api.openapi;

import com.coreintra.businesstime.BusinessInstant;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.util.Iterator;
import org.springframework.stereotype.Component;

/**
 * Types {@link BusinessInstant} as a string in the OpenAPI document, which is
 * what it actually is on the wire.
 *
 * <h2>What it was doing instead</h2>
 *
 * <p>{@code BusinessTimeJacksonModule} serialises a business instant as
 * {@code "2026-08-30T27:00:00.000"}. springdoc, introspecting the Java type,
 * found one public no-argument boolean getter and emitted
 * {@code { outsideCalendarDay?: boolean }}. Every generated client therefore
 * typed every business instant as an object carrying a stray flag, and the
 * accounting screens hit it on every {@code postedAt} field.
 *
 * <h2>Why a converter bean</h2>
 *
 * <p>{@code SpringDocUtils.getConfig().replaceWithSchema(...)} in a static
 * initialiser is the documented one-liner and it silently did nothing here: the
 * configuration class loads during context refresh, by which point the
 * converter chain has already been assembled. A {@code ModelConverter} bean is
 * collected by springdoc explicitly, so it cannot lose that race.
 *
 * <p>The description travels with the type rather than being repeated at every
 * field. Someone generating a client against this document should not have to
 * find the prose in the API description to learn that {@code 27:00} is a
 * legitimate value.
 */
@Component
public class BusinessInstantSchemaConverter implements ModelConverter {

    private static final String DESCRIPTION =
            "A business instant: YYYY-MM-DDT[-]HH:MM:SS.mmm, with no timezone and no trailing Z "
                    + "— a Z is rejected. The clock face runs from -24:00:00 to +48:00:00, so a "
                    + "shift ending at three in the morning is 27:00 on the business day it "
                    + "began. Ordering is by business date first and offset second; never sort "
                    + "these strings lexically, because '-' sorts below every digit.";

    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context,
            Iterator<ModelConverter> chain) {
        if (type != null && type.getType() != null) {
            // getType() may be a JavaType or a Class depending on where the
            // resolution started, so compare on the constructed Java type
            // rather than on identity with Class.
            com.fasterxml.jackson.databind.JavaType javaType =
                    Json.mapper().constructType(type.getType());
            if (javaType != null && BusinessInstant.class.equals(javaType.getRawClass())) {
                return new StringSchema()
                        .example("2026-08-30T27:00:00.000")
                        .description(DESCRIPTION);
            }
        }
        return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
    }
}
