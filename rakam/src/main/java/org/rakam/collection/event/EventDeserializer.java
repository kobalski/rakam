package org.rakam.collection.event;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.rakam.analysis.NotExistsException;
import org.rakam.collection.Event;
import org.rakam.collection.FieldType;
import org.rakam.collection.SchemaField;
import org.rakam.collection.event.metastore.Metastore;
import org.rakam.util.AvroUtil;
import org.rakam.util.ProjectCollection;
import org.rakam.util.RakamException;

import javax.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static java.lang.String.format;
import static java.util.stream.Collectors.toList;

public class EventDeserializer extends JsonDeserializer<Event> {
    private final Map<String, List<SchemaField>> conditionalMagicFields;

    private final Metastore metastore;
    private final Cache<ProjectCollection, Schema> schemaCache  = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS).build();

    @Inject
    public EventDeserializer(Metastore metastore, FieldDependencyBuilder.FieldDependency fieldDependency) {
        this.metastore = metastore;
        this.conditionalMagicFields = fieldDependency.dependentFields;
    }

    @Override
    public Event deserialize(JsonParser jp, DeserializationContext ctx) throws IOException {
        return deserializeWithProject(jp, null);
    }

    public Event deserializeWithProject(JsonParser jp, String project) throws IOException, RakamException {
        GenericData.Record properties = null;

        String collection = null;
        Event.EventContext context = null;

        JsonToken t = jp.getCurrentToken();
        if (t == JsonToken.START_OBJECT) {
            t = jp.nextToken();
        }
        for (; t == JsonToken.FIELD_NAME; t = jp.nextToken()) {
            String fieldName = jp.getCurrentName();

            jp.nextToken();

            switch (fieldName) {
                case "project":
                    if(project != null) {
                        if(jp.getValueAsString() != null) {
                            throw new RakamException("project is already set", BAD_REQUEST);
                        }
                    } else {
                        project = jp.getValueAsString();
                    }
                    break;
                case "collection":
                    collection = jp.getValueAsString().toLowerCase();
                    break;
                case "api":
                    context = jp.readValueAs(Event.EventContext.class);
                    break;
                case "properties":
                    if (project == null || collection == null) {
                        throw new JsonMappingException("'project' and 'collection' fields must be located before 'properties' field.");
                    } else {
                        try {
                            properties = parseProperties(project, collection, jp);
                        } catch (NotExistsException e) {
                            throw Throwables.propagate(e);
                        }
                        t = jp.getCurrentToken();

                        if(t != JsonToken.END_OBJECT) {
                            if(t == JsonToken.START_OBJECT) {
                                throw new RakamException("Nested properties are not supported", BAD_REQUEST);
                            } else {
                                throw new RakamException("Error while deserializing event", INTERNAL_SERVER_ERROR);
                            }
                        }
                    }
                    break;
            }
        }
        if (properties == null) {
            throw new JsonMappingException("properties is null");
        }
        return new Event(project, collection, context, properties);
    }

    public Schema convertAvroSchema(List<SchemaField> fields) {
        List<Schema.Field> avroFields = fields.stream()
                .map(AvroUtil::generateAvroSchema).collect(Collectors.toList());

        Schema schema = Schema.createRecord("collection", null, null, false);

        conditionalMagicFields.keySet().stream()
                .filter(s -> !avroFields.stream().anyMatch(af -> af.name().equals(s)))
                .map(n -> new Schema.Field(n, Schema.create(Schema.Type.NULL), "", null))
                .forEach(x -> avroFields.add(x));

        schema.setFields(avroFields);
        return schema;
    }

    private GenericData.Record parseProperties(String project, String collection, JsonParser jp) throws IOException, NotExistsException {
        ProjectCollection key = new ProjectCollection(project, collection);
        Schema avroSchema = schemaCache.getIfPresent(key);
        List<SchemaField> schema;
        if (avroSchema == null) {
            schema = metastore.getCollection(project, collection);

            avroSchema = convertAvroSchema(schema);
            schemaCache.put(key, avroSchema);
        }

        GenericData.Record record = new GenericData.Record(avroSchema);
        Set<SchemaField> newFields = null;

        JsonToken t = jp.nextToken();
        for (; t == JsonToken.FIELD_NAME; t = jp.nextToken()) {
            String fieldName = jp.getCurrentName();

            Schema.Field field = avroSchema.getField(fieldName);

            jp.nextToken();

            if (field == null) {
                fieldName = fieldName.toLowerCase(Locale.ENGLISH);
                field = avroSchema.getField(fieldName);
                if(field == null) {
                    FieldType type = getType(jp);
                    if (type != null) {
                        if (newFields == null)
                            newFields = new HashSet<>();

                        if(fieldName.equals("_user")) {
                            // the type of magic _user field must always be a string
                            // for consistency.
                            type = FieldType.STRING;
                        }
                        SchemaField newField = new SchemaField(fieldName, type);
                        newFields.add(newField);

                        avroSchema = createNewSchema(avroSchema, newField);
                        field = avroSchema.getField(newField.getName());

                        GenericData.Record newRecord = new GenericData.Record(avroSchema);
                        for (Schema.Field f : record.getSchema().getFields()) {
                            newRecord.put(f.name(), record.get(f.name()));
                        }
                        record = newRecord;

                        if(type.isArray() || type.isMap()) {
                            // if the type of new field is ARRAY, we already switched to next token
                            // so current token is not START_ARRAY.
                            record.put(field.pos(), getValue(jp, field.schema(), true));
                            continue;
                        }
                    } else {
                        // the type is null or an empty array
                        continue;
                    }
                }
            } else {
                if(field.schema().getType() == Schema.Type.NULL) {
                    // TODO: get rid of this loop.
                    for (SchemaField schemaField : conditionalMagicFields.get(fieldName)) {
                        if(avroSchema.getField(schemaField.getName()) == null) {
                            if(newFields == null) {
                                newFields = new HashSet<>();
                            }
                            newFields.add(schemaField);
                        }
                    }
                }
            }

            record.put(field.pos(), getValue(jp, field.schema(), false));
        }

        if (newFields != null) {
            List<SchemaField> newSchema = metastore.getOrCreateCollectionFieldList(project, collection, newFields);
            Schema newAvroSchema = convertAvroSchema(newSchema);

            schemaCache.put(key, newAvroSchema);
            GenericData.Record newRecord = new GenericData.Record(newAvroSchema);
            for (Schema.Field field : record.getSchema().getFields()) {
                newRecord.put(field.name(), record.get(field.name()));
            }
            record = newRecord;
        }

        return record;
    }

    private Schema createNewSchema(Schema currentSchema, SchemaField newField) {
        List<Schema.Field> avroFields = currentSchema.getFields().stream()
                .map(field -> new Schema.Field(field.name(), field.schema(), field.doc(), field.defaultValue()))
                .collect(toList());

        avroFields.add(AvroUtil.generateAvroSchema(newField));

        conditionalMagicFields.keySet().stream()
                .filter(s -> !avroFields.stream().anyMatch(af -> af.name().equals(s)))
                .map(n -> new Schema.Field(n, Schema.create(Schema.Type.NULL), "", null))
                .forEach(x -> avroFields.add(x));

        Schema avroSchema = Schema.createRecord("collection", null, null, false);
        avroSchema.setFields(avroFields);

        return avroSchema;
    }

    private Object getValueOfMagicField(JsonParser jp) throws IOException {
        switch (jp.getCurrentToken()) {
            case VALUE_TRUE:
                return Boolean.TRUE;
            case VALUE_FALSE:
                return Boolean.FALSE;
            case VALUE_NUMBER_FLOAT:
                return jp.getValueAsDouble();
            case VALUE_NUMBER_INT:
                return jp.getValueAsLong();
            case VALUE_STRING:
                return jp.getValueAsString();
            case VALUE_NULL:
                return null;
            default:
                throw new RakamException("The value of magic field is unknown", BAD_REQUEST);
        }
    }

    private Object getValue(JsonParser jp, Schema schema, boolean passInitialToken) throws IOException {
        if (schema.getType() == Schema.Type.UNION) {
            schema = schema.getTypes().get(1);
        }

        switch (schema.getType()) {
            case NULL:
                return getValueOfMagicField(jp);
            case STRING:
                // TODO: is it a good idea to cast the value automatically?
//                if (t == JsonToken.VALUE_STRING)
                    return jp.getValueAsString();
            case BOOLEAN:
                return jp.getValueAsBoolean();
            case LONG:
                return jp.getValueAsLong();
            case INT:
                return jp.getValueAsInt();
            case DOUBLE:
                return jp.getValueAsDouble();
            case MAP:
                JsonToken t = jp.getCurrentToken();

                Map<String, Object> map = new HashMap<>();

                if(!passInitialToken) {
                    if(t != JsonToken.START_OBJECT) {
                        return null;
                    } else {
                        t = jp.nextToken();
                    }
                } else {
                    // In order to determine the value type of map, getType method performed an extra
                    // jp.nextToken() so the cursor should be at VALUE_STRING token.
                    String key = jp.getParsingContext().getCurrentName();
                    map.put(key, getValue(jp, schema.getValueType(), false));
                    t = jp.nextToken();
                }

                for (; t == JsonToken.FIELD_NAME; t = jp.nextToken()) {
                    String key = jp.getCurrentName();

                    jp.nextToken();
                    map.put(key, getValue(jp, schema.getValueType(), false));
                }
                return map;
            case ARRAY:
                t = jp.getCurrentToken();
                // if the passStartArrayToken is true, we already performed jp.nextToken
                // so there is no need to check if the current token is
                if(!passInitialToken) {
                    if(t != JsonToken.START_ARRAY) {
                        return null;
                    } else {
                        t = jp.nextToken();
                    }
                }

                List<Object> objects = new ArrayList<>();
                for (; t != JsonToken.END_ARRAY; t = jp.nextToken()) {
                    objects.add(getValue(jp, schema.getElementType(), false));
                }
                return new GenericData.Array(schema, objects);
            default:
                throw new JsonMappingException(format("type is not supported."));
        }
    }

    private FieldType getType(JsonParser jp) throws IOException {
        switch (jp.getCurrentToken()) {
            case VALUE_NULL:
                return null;
            case VALUE_STRING:
                return FieldType.STRING;
            case VALUE_FALSE:
                return FieldType.BOOLEAN;
            case VALUE_NUMBER_FLOAT:
                return FieldType.DOUBLE;
            case VALUE_TRUE:
                return FieldType.BOOLEAN;
            case VALUE_NUMBER_INT:
                return FieldType.LONG;
            case START_ARRAY:
                JsonToken t = jp.nextToken();
                if(t == JsonToken.END_ARRAY) {
                    // if the array is null, return null as value.
                    // TODO: if the key already has a type, return that type instead of null.
                    return null;
                }
                return getType(jp).convertToArrayType();
            case START_OBJECT:
                t = jp.nextToken();
                if(t == JsonToken.END_OBJECT) {
                    // if the map is null, return null as value.
                    // TODO: if the key already has a type, return that type instead of null.
                    return null;
                }
                if(t != JsonToken.FIELD_NAME) {
                    throw new IllegalArgumentException();
                }
                jp.nextToken();
                return getType(jp).convertToMapValueType();
            default:
                throw new JsonMappingException(format("the type is not supported: %s", jp.getValueAsString()));
        }
    }
}

