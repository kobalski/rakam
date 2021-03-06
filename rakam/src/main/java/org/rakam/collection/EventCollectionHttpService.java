package org.rakam.collection;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.ContextAttributes;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import io.airlift.log.Logger;
import io.airlift.slice.InputStreamSliceInput;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.cookie.Cookie;
import org.apache.avro.generic.GenericData;
import org.rakam.analysis.ApiKeyService;
import org.rakam.analysis.QueryHttpService;
import org.rakam.analysis.metadata.Metastore;
import org.rakam.collection.Event.EventContext;
import org.rakam.plugin.CopyEvent;
import org.rakam.plugin.EventMapper;
import org.rakam.plugin.EventStore;
import org.rakam.plugin.EventStore.CopyType;
import org.rakam.report.ChainQueryExecution;
import org.rakam.report.QueryExecution;
import org.rakam.server.http.HttpRequestException;
import org.rakam.server.http.HttpService;
import org.rakam.server.http.RakamHttpRequest;
import org.rakam.server.http.SwaggerJacksonAnnotationIntrospector;
import org.rakam.server.http.annotations.Api;
import org.rakam.server.http.annotations.ApiOperation;
import org.rakam.server.http.annotations.ApiParam;
import org.rakam.server.http.annotations.ApiResponse;
import org.rakam.server.http.annotations.ApiResponses;
import org.rakam.server.http.annotations.Authorization;
import org.rakam.server.http.annotations.BodyParam;
import org.rakam.server.http.annotations.IgnoreApi;
import org.rakam.server.http.annotations.JsonRequest;
import org.rakam.util.JsonHelper;
import org.rakam.util.LogUtil;
import org.rakam.util.RakamException;
import org.rakam.util.SuccessMessage;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.xml.bind.DatatypeConverter;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.fasterxml.jackson.core.JsonToken.START_OBJECT;
import static com.google.common.base.Charsets.UTF_8;
import static io.netty.handler.codec.http.HttpHeaders.Names.ACCESS_CONTROL_ALLOW_CREDENTIALS;
import static io.netty.handler.codec.http.HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN;
import static io.netty.handler.codec.http.HttpHeaders.Names.ACCESS_CONTROL_EXPOSE_HEADERS;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaders.Names.ORIGIN;
import static io.netty.handler.codec.http.HttpHeaders.Names.SET_COOKIE;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.CONFLICT;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.PRECONDITION_FAILED;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty.handler.codec.http.cookie.ServerCookieEncoder.STRICT;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.rakam.analysis.ApiKeyService.AccessKeyType.MASTER_KEY;
import static org.rakam.plugin.EventMapper.COMPLETED_EMPTY_FUTURE;
import static org.rakam.plugin.EventStore.COMPLETED_FUTURE;
import static org.rakam.plugin.EventStore.CopyType.AVRO;
import static org.rakam.plugin.EventStore.CopyType.CSV;
import static org.rakam.plugin.EventStore.CopyType.JSON;
import static org.rakam.plugin.EventStore.SUCCESSFUL_BATCH;
import static org.rakam.server.http.HttpServer.errorMessage;
import static org.rakam.util.JsonHelper.encode;
import static org.rakam.util.JsonHelper.encodeAsBytes;
import static org.rakam.util.StandardErrors.CONFLICT_COMMIT_MESSAGE;
import static org.rakam.util.StandardErrors.PARTIAL_ERROR_MESSAGE;
import static org.rakam.util.ValidationUtil.checkCollection;

@Path("/event")
@Api(value = "/event", nickname = "collectEvent", description = "Event collection", tags = {"collect"})
public class EventCollectionHttpService
        extends HttpService
{
    private final static Logger LOGGER = Logger.get(EventCollectionHttpService.class);

    private final byte[] OK_MESSAGE = "1".getBytes(UTF_8);

    private final ObjectMapper jsonMapper;
    private final ObjectMapper csvMapper;
    private final EventStore eventStore;
    private final List<EventMapper> eventMappers;
    private final ApiKeyService apiKeyService;
    private final AvroEventDeserializer avroEventDeserializer;
    private final Metastore metastore;
    private final QueryHttpService queryHttpService;
    private final com.google.common.base.Optional<CopyEvent> copyEvent;
    private final JsonEventDeserializer jsonEventDeserializer;

    @Inject
    public EventCollectionHttpService(
            EventStore eventStore,
            com.google.common.base.Optional<CopyEvent> copyEvent,
            ApiKeyService apiKeyService,
            JsonEventDeserializer deserializer,
            QueryHttpService queryHttpService,
            AvroEventDeserializer avroEventDeserializer,
            EventListDeserializer eventListDeserializer,
            CsvEventDeserializer csvEventDeserializer,
            Metastore metastore,
            Set<EventMapper> mappers)
    {
        this.eventStore = eventStore;
        this.eventMappers = ImmutableList.copyOf(mappers);
        this.apiKeyService = apiKeyService;
        this.queryHttpService = queryHttpService;
        this.metastore = metastore;
        this.copyEvent = copyEvent;

        jsonMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(Event.class, deserializer);
        module.addDeserializer(EventList.class, eventListDeserializer);
        jsonMapper.registerModule(module);

        jsonMapper.registerModule(new SimpleModule("swagger", Version.unknownVersion())
        {
            @Override
            public void setupModule(SetupContext context)
            {
                context.insertAnnotationIntrospector(new SwaggerJacksonAnnotationIntrospector());
            }
        });

        this.avroEventDeserializer = avroEventDeserializer;
        this.jsonEventDeserializer = deserializer;
        csvMapper = new CsvMapper();
        csvMapper.registerModule(new SimpleModule().addDeserializer(EventList.class, csvEventDeserializer));
    }

    public static CompletableFuture<List<Cookie>> mapEvent(List<EventMapper> eventMappers, Function<EventMapper, CompletableFuture<List<Cookie>>> mapperFunction)
    {
        List<Cookie> cookies = new ArrayList<>();
        CompletableFuture[] futures = null;
        int futureIndex = 0;

        for (int i = 0; i < eventMappers.size(); i++) {
            EventMapper mapper = eventMappers.get(i);
            CompletableFuture<List<Cookie>> mapperCookies = mapperFunction.apply(mapper);
            if (COMPLETED_EMPTY_FUTURE.equals(mapperCookies)) {
                if (futures != null) {
                    futures[futureIndex++] = COMPLETED_FUTURE;
                }
            }
            else {
                CompletableFuture<Void> future = mapperCookies.thenAccept(cookies::addAll);

                if (futures == null) {
                    futures = new CompletableFuture[eventMappers.size() - i];
                }

                futures[futureIndex++] = future;
            }
        }

        if (futures == null) {
            return COMPLETED_EMPTY_FUTURE;
        }
        else {
            return CompletableFuture.allOf(futures).thenApply(v -> cookies);
        }
    }

    public static void returnError(RakamHttpRequest request, String msg, HttpResponseStatus status)
    {
        ByteBuf byteBuf = Unpooled.wrappedBuffer(JsonHelper.encodeAsBytes(errorMessage(msg, status)));
        DefaultFullHttpResponse errResponse = new DefaultFullHttpResponse(HTTP_1_1, status, byteBuf);
        setBrowser(request, errResponse);
        request.response(errResponse).end();
    }

    public static void setBrowser(HttpRequest request, HttpResponse response)
    {
        response.headers().set(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        if (request.headers().contains(ORIGIN)) {
            response.headers().set(ACCESS_CONTROL_ALLOW_ORIGIN, request.headers().get(ORIGIN));
        }
        String headerList = getHeaderList(request.headers().iterator());
        if (headerList != null) {
            response.headers().set(ACCESS_CONTROL_EXPOSE_HEADERS, headerList);
        }
    }

    @POST
    @ApiOperation(value = "Collect event", response = Integer.class, request = Event.class)
    @Path("/collect")
    public void collectEvent(RakamHttpRequest request)
    {
        String socketAddress = request.getRemoteAddress();

        request.bodyHandler(buff -> {
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.wrappedBuffer(OK_MESSAGE));

            CompletableFuture<List<Cookie>> cookiesFuture;

            try {
                Event event = jsonMapper.readValue(buff, Event.class);

                EventContext context = event.api();

                if (context.checksum != null && !validateChecksum(request, context.checksum, buff)) {
                    return;
                }

                cookiesFuture = mapEvent(eventMappers, (mapper) -> mapper.mapAsync(event, new HttpRequestParams(request),
                        getRemoteAddress(socketAddress), response.trailingHeaders()));
                cookiesFuture.thenAccept(v -> eventStore.store(event));
            }
            catch (JsonMappingException e) {
                String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                returnError(request, "JSON couldn't parsed: " + message, BAD_REQUEST);
                return;
            }
            catch (IOException e) {
                returnError(request, "JSON couldn't parsed: " + e.getMessage(), BAD_REQUEST);
                return;
            }
            catch (RakamException e) {
                LogUtil.logException(request, e);
                returnError(request, e.getMessage(), e.getStatusCode());
                return;
            }
            catch (HttpRequestException e) {
                returnError(request, e.getMessage(), e.getStatusCode());
                return;
            }
            catch (IllegalArgumentException e) {
                LogUtil.logException(request, e);
                returnError(request, e.getMessage(), BAD_REQUEST);
                return;
            }
            catch (Exception e) {
                LOGGER.error(e, "Error while collecting event");

                returnError(request, "An error occurred", INTERNAL_SERVER_ERROR);
                return;
            }

            String headerList = getHeaderList(response.headers().iterator());
            if (headerList != null) {
                response.headers().set(ACCESS_CONTROL_EXPOSE_HEADERS, headerList);
            }
            if (request.headers().contains(ORIGIN)) {
                response.headers().set(ACCESS_CONTROL_ALLOW_ORIGIN, request.headers().get(ORIGIN));
            }

            cookiesFuture.thenAccept(cookies -> {
                if (cookies != null) {
                    response.headers().add(SET_COOKIE, STRICT.encode(cookies));
                }
                request.response(response).end();
            });
        });
    }

    public static InetAddress getRemoteAddress(String socketAddress)
    {
        try {
            return InetAddress.getByName(socketAddress);
        }
        catch (UnknownHostException e) {
            return null;
        }
    }

    @POST
    @ApiOperation(value = "Collect Bulk events", request = EventList.class, response = SuccessMessage.class, notes = "Bulk API requires master_key as api key and designed to handle large value of data. " +
            "The endpoint also accepts application/avro and text/csv formats. You need need to set 'collection' and 'master_key' query parameters if the content-type is not application/json.")
    @Path("/bulk")
    public void bulkEvents(RakamHttpRequest request)
    {
        bulkEvents(request, true);
    }

    public void bulkEvents(RakamHttpRequest request, boolean mapEvents)
    {
        storeEventsSync(request,
                buff -> {
                    String contentType = request.headers().get(CONTENT_TYPE);
                    if (contentType == null || "application/json".equals(contentType)) {
                        String apiKey = getParam(request.params(), MASTER_KEY.getKey());
                        String project = apiKeyService.getProjectOfApiKey(apiKey, MASTER_KEY);
                        String collection = getParam(request.params(), "collection");

                        JsonParser parser = jsonMapper.getFactory().createParser(buff);
                        ArrayList<Event> events = new ArrayList<>();

                        JsonToken t = parser.nextToken();
                        if (t == JsonToken.START_OBJECT) {
                            while (t == JsonToken.START_OBJECT) {
                                Map.Entry<List<SchemaField>, GenericData.Record> entry = jsonEventDeserializer.parseProperties(project, collection, parser, true);
                                events.add(new Event(project, collection, null, entry.getKey(), entry.getValue()));
                                t = parser.nextToken();
                            }
                        }
                        else if (t == JsonToken.START_ARRAY) {
                            t = parser.nextToken();

                            for (; t == START_OBJECT; t = parser.nextToken()) {
                                Map.Entry<List<SchemaField>, GenericData.Record> entry = jsonEventDeserializer.parseProperties(project, collection, parser, true);
                                events.add(new Event(project, collection, null, entry.getKey(), entry.getValue()));
                            }
                        }
                        else {
                            throw new RakamException("The body must be an array of events or line-separated events", BAD_REQUEST);
                        }

                        return new EventList(EventContext.apiKey(apiKey), project, events);
                    }
                    else if ("application/avro".equals(contentType)) {
                        String apiKey = getParam(request.params(), MASTER_KEY.getKey());
                        String project = apiKeyService.getProjectOfApiKey(apiKey, MASTER_KEY);
                        String collection = getParam(request.params(), "collection");

                        return avroEventDeserializer.deserialize(project, collection, new InputStreamSliceInput(buff));
                    }
                    else if ("text/csv".equals(contentType)) {
                        String apiKey = getParam(request.params(), MASTER_KEY.getKey());
                        String project = apiKeyService.getProjectOfApiKey(apiKey, MASTER_KEY);
                        String collection = getParam(request.params(), "collection");

                        CsvSchema.Builder builder = CsvSchema.builder();
                        if (request.params().get("column_separator") != null) {
                            List<String> column_seperator = request.params().get("column_separator");
                            if (column_seperator != null && column_seperator.get(0).length() != 1) {
                                throw new RakamException("Invalid column separator", BAD_REQUEST);
                            }
                            builder.setColumnSeparator(column_seperator.get(0).charAt(0));
                        }

                        boolean useHeader = false;
                        if (request.params().get("use_header") != null) {
                            useHeader = Boolean.valueOf(request.params().get("use_header").get(0));
                            // do not set CsvSchema setUseHeader, it has extra overhead and the deserializer cannot handle that.
                        }

                        return csvMapper.readerFor(EventList.class)
                                .with(ContextAttributes.getEmpty()
                                        .withSharedAttribute("project", project)
                                        .withSharedAttribute("useHeader", useHeader)
                                        .withSharedAttribute("collection", collection)
                                        .withSharedAttribute("apiKey", apiKey))
                                .with(builder.build()).readValue(buff);
                    }

                    throw new RakamException("Unsupported content type: " + contentType, BAD_REQUEST);
                },
                (events, responseHeaders) -> {
                    if (events.size() > 0) {
                        try {
                            eventStore.storeBulk(events);
                        }
                        catch (Throwable e) {
                            List<Event> sample = events.size() > 5 ? events.subList(0, 2) : events;
                            LOGGER.error(new RuntimeException("Error executing EventStore bulk method.",
                                            new RuntimeException(sample.toString().substring(0, 200), e)),
                                    "Error while storing event.");

                            return new HeaderDefaultFullHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR,
                                    Unpooled.wrappedBuffer(encodeAsBytes(errorMessage("An error occurred: " + e.getMessage(), INTERNAL_SERVER_ERROR))),
                                    responseHeaders);
                        }
                    }

                    return new HeaderDefaultFullHttpResponse(HTTP_1_1, OK,
                            Unpooled.wrappedBuffer(encodeAsBytes(SuccessMessage.success())),
                            responseHeaders);
                }, mapEvents);
    }

    @POST
    @Consumes("text/event-stream")
    @IgnoreApi
    @ApiOperation(value = "Copy events from remote", request = BulkEventRemote.class, response = Integer.class)
    @Path("/copy/remote")
    public void copyEventsRemote(RakamHttpRequest request)
    {
        if (!copyEvent.isPresent()) {
            bulkEventsRemote(request, false);
        }
        else {
            queryHttpService.handleServerSentQueryExecution(request, BulkEventRemote.class, (project, convert) ->
                    copyEvent.get().copy(project, convert.collection, convert.urls, convert.type,
                            convert.compression, convert.options), MASTER_KEY, false);
        }
    }

    @POST
    @ApiOperation(value = "Copy events directly to database", request = EventList.class, response = Integer.class)
    @Path("/copy")
    public void copyEvents(RakamHttpRequest request)
    {
        bulkEvents(request, false);
    }

    @POST
    @ApiOperation(value = "Collect bulk events from remote", request = BulkEventRemote.class, response = Integer.class)
    @ApiResponses(value = {@ApiResponse(code = 409, message = PARTIAL_ERROR_MESSAGE, response = int[].class)})
    @Path("/bulk/remote")
    public void bulkEventsRemote(RakamHttpRequest request)
    {
        bulkEventsRemote(request, true);
    }

    public void bulkEventsRemote(RakamHttpRequest request, boolean mapEvents)
    {
        storeEventsSync(request,
                buff -> {
                    BulkEventRemote query = JsonHelper.read(buff, BulkEventRemote.class);
                    String masterKey = Optional.ofNullable(request.params().get("master_key"))
                            .map((v) -> v.get(0))
                            .orElseGet(() -> request.headers().get("master_key"));
                    String project = apiKeyService.getProjectOfApiKey(masterKey, MASTER_KEY);

                    checkCollection(query.collection);

                    if (query.urls.size() != 1) {
                        throw new RakamException("Only one url is supported", BAD_REQUEST);
                    }
                    if (query.compression != null) {
                        throw new RakamException("Compression is not supported yet", BAD_REQUEST);
                    }

                    URL url = query.urls.get(0);
                    if (query.type == JSON) {
                        return jsonMapper.readValue(url, EventList.class);
                    }
                    else if (query.type == CSV) {
                        CsvSchema.Builder builder = CsvSchema.builder();
                        if (request.headers().get("column_separator") != null) {
                            String column_seperator = request.headers().get("column_separator");
                            if (column_seperator.length() != 1) {
                                throw new RakamException("Invalid column separator", BAD_REQUEST);
                            }
                            builder.setColumnSeparator(column_seperator.charAt(0));
                        }

                        boolean useHeader = true;
                        if (request.headers().get("use_header") != null) {
                            useHeader = Boolean.valueOf(request.headers().get("use_header"));
                            // do not set CsvSchema setUseHeader, it has extra overhead and the deserializer cannot handle that.
                        }

                        return csvMapper.readerFor(EventList.class)
                                .with(ContextAttributes.getEmpty()
                                        .withSharedAttribute("project", project)
                                        .withSharedAttribute("useHeader", useHeader)
                                        .withSharedAttribute("collection", query.collection)
                                        .withSharedAttribute("apiKey", masterKey))
                                .with(builder.build()).readValue(url);
                    }
                    else if (query.type == AVRO) {
                        URLConnection conn = url.openConnection();
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(5000);
                        conn.connect();

                        return avroEventDeserializer.deserialize(project, query.collection,
                                new InputStreamSliceInput(conn.getInputStream()));
                    }

                    throw new RakamException("Unsupported or missing type.", BAD_REQUEST);
                },
                (events, responseHeaders) -> {
                    try {
                        eventStore.storeBulk(events);
                    }
                    catch (Exception e) {
                        List<Event> sample = events.size() > 5 ? events.subList(0, 5) : events;
                        LOGGER.error(new RuntimeException("Error executing EventStore bulkRemote method.",
                                        new RuntimeException(sample.toString().substring(0, 200), e)),
                                "Error while storing event.");
                        return new HeaderDefaultFullHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR,
                                Unpooled.wrappedBuffer(encodeAsBytes(errorMessage("An error occurred", INTERNAL_SERVER_ERROR))),
                                responseHeaders);
                    }

                    return new HeaderDefaultFullHttpResponse(HTTP_1_1, OK,
                            Unpooled.wrappedBuffer(OK_MESSAGE),
                            responseHeaders);
                }, mapEvents);
    }

    private String getParam(Map<String, List<String>> params, String param)
    {
        List<String> strings = params.get(param);
        if (strings == null || strings.size() == 0) {
            throw new RakamException(String.format("%s query parameter is required", param), BAD_REQUEST);
        }

        return strings.get(strings.size() - 1);
    }

    private static final int[] FAILED_SINGLE_EVENT = new int[] {0};

    @POST
    @ApiOperation(notes = "Returns 1 if the events are collected.", value = "Collect multiple events", request = EventList.class, response = Integer.class)
    @ApiResponses(value = {
            @ApiResponse(code = 409, message = PARTIAL_ERROR_MESSAGE, response = int[].class)
    })
    @Path("/batch")
    public void batchEvents(RakamHttpRequest request)
    {
        storeEvents(request, buff -> jsonMapper.readValue(buff, EventList.class),
                (events, responseHeaders) -> {
                    CompletableFuture<int[]> errorIndexes;

                    if (events.size() > 0) {
                        boolean single = events.size() == 1;
                        try {
                            if (single) {
                                errorIndexes = EventStore.COMPLETED_FUTURE_BATCH;
                                if (events.size() == 1) {
                                    errorIndexes = eventStore.storeAsync(events.get(0))
                                            .handle((result, ex) -> (ex != null) ? FAILED_SINGLE_EVENT : SUCCESSFUL_BATCH);
                                }
                            }
                            else {
                                errorIndexes = eventStore.storeBatchAsync(events);
                            }
                        }
                        catch (Exception e) {
                            List<Event> sample = events.size() > 5 ? events.subList(0, 5) : events;
                            LOGGER.error(new RuntimeException(sample.toString(), e), "Error executing EventStore " + (single ? "store" : "batch") + " method.");
                            return completedFuture(new HeaderDefaultFullHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR,
                                    Unpooled.wrappedBuffer(encodeAsBytes(errorMessage("An error occurred", INTERNAL_SERVER_ERROR))),
                                    responseHeaders));
                        }
                    }
                    else {
                        errorIndexes = EventStore.COMPLETED_FUTURE_BATCH;
                    }

                    return errorIndexes.thenApply(result -> {
                        if (result.length == 0) {
                            return new HeaderDefaultFullHttpResponse(HTTP_1_1, OK,
                                    Unpooled.wrappedBuffer(OK_MESSAGE), responseHeaders);
                        }
                        else {
                            return new HeaderDefaultFullHttpResponse(HTTP_1_1, CONFLICT,
                                    Unpooled.wrappedBuffer(encodeAsBytes(result)), responseHeaders);
                        }
                    });
                }, true);
    }

    public void storeEventsSync(RakamHttpRequest request, ThrowableFunction mapper, BiFunction<List<Event>, HttpHeaders, FullHttpResponse> responseFunction, boolean mapEvents)
    {
        storeEvents(request, mapper,
                (events, entries) -> completedFuture(responseFunction.apply(events, entries)), mapEvents);
    }

    public void storeEvents(RakamHttpRequest request, ThrowableFunction mapper, BiFunction<List<Event>, HttpHeaders, CompletableFuture<FullHttpResponse>> responseFunction, boolean mapEvents)
    {
        request.bodyHandler(buff -> {
            DefaultHttpHeaders responseHeaders = new DefaultHttpHeaders();
            responseHeaders.set(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
            if (request.headers().contains(ORIGIN)) {
                responseHeaders.set(ACCESS_CONTROL_ALLOW_ORIGIN, request.headers().get(ORIGIN));
            }

            CompletableFuture<FullHttpResponse> response;
            CompletableFuture<List<Cookie>> entries;
            try {
                EventList events = mapper.apply(buff);

                EventContext context = events.api;
                if (context.checksum != null && !validateChecksum(request, context.checksum, buff)) {
                    return;
                }

                InetAddress remoteAddress = getRemoteAddress(request.getRemoteAddress());

                if (mapEvents) {
                    entries = mapEvent(eventMappers, (m) -> m.mapAsync(events, new HttpRequestParams(request),
                            remoteAddress, responseHeaders));
                }
                else {
                    entries = EventMapper.COMPLETED_EMPTY_FUTURE;
                }

                response = responseFunction.apply(events.events, responseHeaders);
            }
            catch (JsonMappingException | JsonParseException e) {
                returnError(request, "JSON couldn't parsed: " + e.getOriginalMessage(), BAD_REQUEST);
                return;
            }
            catch (IOException e) {
                returnError(request, "JSON couldn't parsed: " + e.getMessage(), BAD_REQUEST);
                return;
            }
            catch (RakamException e) {
                LogUtil.logException(request, e);
                returnError(request, e.getMessage(), e.getStatusCode());
                return;
            }
            catch (HttpRequestException e) {
                returnError(request, e.getMessage(), e.getStatusCode());
                return;
            }
            catch (IllegalArgumentException e) {
                LogUtil.logException(request, e);
                returnError(request, e.getMessage(), BAD_REQUEST);
                return;
            }
            catch (Throwable e) {
                LOGGER.error(e, "Error while collecting event");

                returnError(request, "An error occurred", INTERNAL_SERVER_ERROR);
                return;
            }

            String headerList = getHeaderList(responseHeaders.iterator());
            if (headerList != null) {
                responseHeaders.set(ACCESS_CONTROL_EXPOSE_HEADERS, headerList);
            }

            responseHeaders.add(CONTENT_TYPE, "application/json");

            entries.thenAccept(value -> {
                if (value != null) {
                    responseHeaders.add(SET_COOKIE, STRICT.encode(value));
                }
                if (response.isDone()) {
                    request.response(response.join()).end();
                }
            });

            response.thenAccept(resp -> {
                if (entries.isDone()) {
                    List<Cookie> join = entries.join();
                    if (join != null) {
                        responseHeaders.add(SET_COOKIE, STRICT.encode(join));
                    }
                }
                request.response(resp).end();
            });
        });
    }

    public static String getHeaderList(Iterator<Map.Entry<String, String>> it)
    {
        StringBuilder builder = new StringBuilder("cf-ray,server,status");
        while (it.hasNext()) {
            String key = it.next().getKey();
            if (!key.equals(SET_COOKIE)) {
                if (builder.length() != 0) {
                    builder.append(',');
                }
                builder.append(key.toLowerCase(Locale.ENGLISH));
            }
        }
        return builder == null ? null : builder.toString();
    }

    private boolean validateChecksum(RakamHttpRequest request, String checksum, InputStream expected)
            throws IOException
    {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        }
        catch (NoSuchAlgorithmException e) {
            returnError(request, "0", INTERNAL_SERVER_ERROR);
            return false;
        }

        byte[] bytes = ByteStreams.toByteArray(expected);
        expected.reset();
        if (!DatatypeConverter.printHexBinary(md.digest(bytes))
                .equals(checksum.toUpperCase(Locale.ENGLISH))) {
            returnError(request, "Checksum is invalid", BAD_REQUEST);
            return false;
        }

        return true;
    }

    interface ThrowableFunction
    {
        EventList apply(InputStream buffer)
                throws IOException;
    }

    public static class HttpRequestParams
            implements EventMapper.RequestParams
    {
        private final RakamHttpRequest request;

        public HttpRequestParams(RakamHttpRequest request)
        {
            this.request = request;
        }

        @Override
        public Collection<Cookie> cookies()
        {
            return request.cookies();
        }

        @Override
        public HttpHeaders headers()
        {
            return request.headers();
        }
    }

    public static class BulkEventRemote
    {
        public final String collection;
        public final List<URL> urls;
        public final CopyType type;
        public final EventStore.CompressionType compression;
        public final Map<String, String> options;

        @JsonCreator
        public BulkEventRemote(@ApiParam("collection") String collection,
                @ApiParam("urls") List<URL> urls,
                @ApiParam("type") CopyType type,
                @ApiParam(value = "compression", required = false) EventStore.CompressionType compression,
                @ApiParam(value = "options", required = false) Map<String, String> options)
        {
            this.collection = collection;
            this.urls = urls;
            this.type = type;
            this.compression = compression;
            this.options = Optional.ofNullable(options).orElse(ImmutableMap.of());
        }
    }
}
