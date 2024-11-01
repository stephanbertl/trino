/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.server.protocol.spooling.encoding;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.CountingOutputStream;
import com.google.inject.Inject;
import io.airlift.slice.Slice;
import io.trino.Session;
import io.trino.client.spooling.DataAttributes;
import io.trino.server.protocol.OutputColumn;
import io.trino.server.protocol.spooling.QueryDataEncoder;
import io.trino.spi.Page;
import io.trino.spi.block.Block;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.type.CharType;
import io.trino.spi.type.SqlDate;
import io.trino.spi.type.SqlDecimal;
import io.trino.spi.type.SqlTime;
import io.trino.spi.type.SqlTimeWithTimeZone;
import io.trino.spi.type.SqlTimestamp;
import io.trino.spi.type.SqlTimestampWithTimeZone;
import io.trino.spi.type.SqlVarbinary;
import io.trino.spi.type.VarcharType;
import io.trino.type.SqlIntervalDayTime;
import io.trino.type.SqlIntervalYearMonth;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static io.trino.client.spooling.DataAttribute.SEGMENT_SIZE;
import static io.trino.plugin.base.util.JsonUtils.jsonFactory;
import static io.trino.spi.type.Chars.padSpaces;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

public class JsonQueryDataEncoder
        implements QueryDataEncoder
{
    private static final String ENCODING = "json";
    private final Session session;
    private final List<OutputColumn> columns;
    private final ObjectMapper mapper;

    public JsonQueryDataEncoder(ObjectMapper mapper, Session session, List<OutputColumn> columns)
    {
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.session = requireNonNull(session, "session is null");
        this.columns = requireNonNull(columns, "columns is null");
    }

    @Override
    public DataAttributes encodeTo(OutputStream output, List<Page> pages)
            throws IOException
    {
        JsonFactory jsonFactory = jsonFactory();
        ConnectorSession connectorSession = session.toConnectorSession();
        try (CountingOutputStream wrapper = new CountingOutputStream(output); JsonGenerator generator = jsonFactory.createGenerator(wrapper)) {
            generator.writeStartArray();
            for (Page page : pages) {
                for (int position = 0; position < page.getPositionCount(); position++) {
                    generator.writeStartArray();
                    for (OutputColumn column : columns) {
                        Block block = page.getBlock(column.sourcePageChannel());
                        if (block.isNull(position)) {
                            generator.writeNull();
                            continue;
                        }
                        switch (column.type()) {
                            case VarcharType varcharType -> writeSliceToRawUtf8(generator, varcharType.getSlice(block, position));
                            case CharType charType -> writeSliceToRawUtf8(generator, padSpaces(charType.getSlice(block, position), charType.getLength()));
                            default -> writeValue(mapper, generator, column.type().getObjectValue(connectorSession, block, position));
                        }
                    }
                    generator.writeEndArray();
                }
            }
            generator.writeEndArray();
            generator.flush(); // final flush to have the data written to the output stream

            return DataAttributes.builder()
                    .set(SEGMENT_SIZE, toIntExact(wrapper.getCount()))
                    .build();
        }
        catch (JsonProcessingException e) {
            throw new IOException("Could not serialize to JSON", e);
        }
    }

    private static void writeValue(ObjectMapper mapper, JsonGenerator generator, Object value)
            throws IOException
    {
        switch (value) {
            case null -> generator.writeNull();
            case Boolean booleanValue -> generator.writeBoolean(booleanValue);
            case Double doubleValue when doubleValue.isInfinite() -> generator.writeString(doubleValue.toString());
            case Double doubleValue when doubleValue.isNaN() -> generator.writeString("NaN");
            case Float floatValue when floatValue.isInfinite() -> generator.writeString(floatValue.toString());
            case Float floatValue when floatValue.isNaN() -> generator.writeString("NaN");
            case Float floatValue -> generator.writeNumber(floatValue);
            case Double doubleValue -> generator.writeNumber(doubleValue);
            case Integer integerValue -> generator.writeNumber(integerValue);
            case Long longValue -> generator.writeNumber(longValue);
            case BigInteger bigIntegerValue -> generator.writeNumber(bigIntegerValue);
            case Byte byteValue -> generator.writeNumber(byteValue);
            case BigDecimal bigDecimalValue -> generator.writeNumber(bigDecimalValue);
            case SqlDate dateValue -> generator.writeString(dateValue.toString());
            case SqlDecimal decimalValue -> generator.writeString(decimalValue.toString());
            case SqlIntervalDayTime intervalValue -> generator.writeString(intervalValue.toString());
            case SqlIntervalYearMonth intervalValue -> generator.writeString(intervalValue.toString());
            case SqlTime timeValue -> generator.writeString(timeValue.toString());
            case SqlTimeWithTimeZone timeWithTimeZone -> generator.writeString(timeWithTimeZone.toString());
            case SqlTimestamp timestamp -> generator.writeString(timestamp.toString());
            case SqlTimestampWithTimeZone timestampWithTimeZone -> generator.writeString(timestampWithTimeZone.toString());
            case SqlVarbinary varbinaryValue -> generator.writeBinary(varbinaryValue.getBytes());
            case String stringValue -> generator.writeString(stringValue);
            case byte[] binaryValue -> generator.writeBinary(binaryValue);
            case List<?> listValue -> {
                generator.writeStartArray();
                for (Object element : listValue) {
                    writeValue(mapper, generator, element);
                }
                generator.writeEndArray();
            }
            // Interleaved array of key-value pairs, compact and retains key structure
            case Map<?, ?> mapValue -> {
                generator.writeStartObject();
                for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                    // The original JSON encoding, always converts a key to a String to use it in the JSON object
                    generator.writeFieldName(entry.getKey().toString());
                    writeValue(mapper, generator, entry.getValue());
                }
                generator.writeEndObject();
            }
            default -> mapper.writeValue(generator, value);
        }
    }

    private static void writeSliceToRawUtf8(JsonGenerator generator, Slice slice)
            throws IOException
    {
        // Optimization: avoid conversion from Slice to String and String to bytes when writing UTF-8 strings
        generator.writeUTF8String(slice.byteArray(), slice.byteArrayOffset(), slice.length());
    }

    @Override
    public String encoding()
    {
        return ENCODING;
    }

    public static class Factory
            implements QueryDataEncoder.Factory
    {
        protected final JsonFactory factory;
        private final ObjectMapper mapper;

        @Inject
        public Factory(ObjectMapper mapper)
        {
            this.factory = jsonFactory();
            this.mapper = requireNonNull(mapper, "mapper is null");
        }

        @Override
        public QueryDataEncoder create(Session session, List<OutputColumn> columns)
        {
            return new JsonQueryDataEncoder(mapper, session, columns);
        }

        @Override
        public String encoding()
        {
            return ENCODING;
        }
    }

    public static class ZstdFactory
            extends Factory
    {
        @Inject
        public ZstdFactory(ObjectMapper mapper)
        {
            super(mapper);
        }

        @Override
        public QueryDataEncoder create(Session session, List<OutputColumn> columns)
        {
            return new ZstdQueryDataEncoder(super.create(session, columns));
        }

        @Override
        public String encoding()
        {
            return super.encoding() + "+zstd";
        }
    }

    public static class Lz4Factory
            extends Factory
    {
        @Inject
        public Lz4Factory(ObjectMapper mapper)
        {
            super(mapper);
        }

        @Override
        public QueryDataEncoder create(Session session, List<OutputColumn> columns)
        {
            return new Lz4QueryDataEncoder(super.create(session, columns));
        }

        @Override
        public String encoding()
        {
            return super.encoding() + "+lz4";
        }
    }
}
