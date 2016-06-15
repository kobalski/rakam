package org.rakam.postgresql.plugin.user;

import com.facebook.presto.sql.ExpressionFormatter;
import com.facebook.presto.sql.tree.Expression;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import org.postgresql.util.PGobject;
import org.rakam.analysis.ConfigManager;
import org.rakam.analysis.InternalConfig;
import org.rakam.collection.FieldType;
import org.rakam.collection.SchemaField;
import org.rakam.plugin.user.User;
import org.rakam.plugin.user.UserStorage;
import org.rakam.postgresql.report.PostgresqlQueryExecutor;
import org.rakam.report.QueryError;
import org.rakam.report.QueryExecution;
import org.rakam.report.QueryExecutor;
import org.rakam.report.QueryResult;
import org.rakam.util.DateTimeUtils;
import org.rakam.util.JsonHelper;
import org.rakam.util.RakamException;
import org.rakam.util.ValidationUtil;

import java.lang.reflect.ParameterizedType;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static java.lang.String.format;
import static org.rakam.collection.SchemaField.stripName;
import static org.rakam.postgresql.analysis.PostgresqlMetastore.fromSql;
import static org.rakam.util.ValidationUtil.checkProject;
import static org.rakam.util.ValidationUtil.checkTableColumn;

public abstract class AbstractPostgresqlUserStorage
        implements UserStorage
{
    private final PostgresqlQueryExecutor queryExecutor;
    private final Cache<String, Map<String, FieldType>> propertyCache;
    private final LoadingCache<String, Optional<FieldType>> userTypeCache;
    private final ConfigManager configManager;

    public AbstractPostgresqlUserStorage(PostgresqlQueryExecutor queryExecutor, ConfigManager configManager)
    {
        this.queryExecutor = queryExecutor;
        propertyCache = CacheBuilder.newBuilder().build();
        this.configManager = configManager;
        userTypeCache = CacheBuilder.newBuilder().build(new CacheLoader<String, Optional<FieldType>>()
        {
            @Override
            public Optional<FieldType> load(String key)
                    throws Exception
            {
                return Optional.ofNullable(configManager.getConfig(key, InternalConfig.USER_TYPE.name(), FieldType.class));
            }
        });
    }

    public Map<String, FieldType> loadColumns(String project)
    {
        Map<String, FieldType> columns = getMetadata(project).stream()
                .collect(Collectors.toMap(col -> col.getName(), col -> col.getType()));
        return columns;
    }

    @Override
    public Object create(String project, Object id, Map<String, Object> properties)
    {
        return createInternal(project, id, properties.entrySet());
    }

    private Map<String, FieldType> createMissingColumns(String project, Object id, Iterable<Map.Entry<String, Object>> properties)
    {
        Map<String, FieldType> columns = propertyCache.getIfPresent(project);
        if (columns == null) {
            columns = loadColumns(project);
            propertyCache.put(project, columns);
        }

        boolean created = false;
        for (Map.Entry<String, Object> entry : properties) {
            FieldType fieldType = columns.get(entry.getKey());
            if (fieldType == null && entry.getValue() != null && !entry.getKey().equals("created_at")) {
                created = true;
                createColumn(project, id, entry.getKey(), entry.getValue());
            }
        }

        if (created) {
            columns = loadColumns(project);
            propertyCache.put(project, columns);
        }

        if(columns.isEmpty()) {
            FieldType other = (id instanceof Long ? FieldType.LONG :
                    (id instanceof Integer ? FieldType.INTEGER : FieldType.STRING));
            FieldType fieldType = configManager.setConfigOnce(project, InternalConfig.USER_TYPE.name(), other);
            createProjectIfNotExists(project, fieldType.isNumeric());
            columns = loadColumns(project);
        }

        return columns;
    }

    public abstract QueryExecutor getExecutorForWithEventFilter();

    private List<Map.Entry<String, Object>> strip(Iterable<Map.Entry<String, Object>> _properties)
    {
        List<Map.Entry<String, Object>> properties = new ArrayList<>();

        for (Map.Entry<String, Object> entry : _properties) {
            String key = stripName(entry.getKey());
            if(key.equals("id")) {
                continue;
            }
            if (!key.equals(entry.getKey())) {
                properties.add(new SimpleImmutableEntry<>(key, entry.getValue()));
            }
            else {
                properties.add(entry);
            }
        }

        return properties;
    }

    public Object createInternal(String project, Object id,
            Iterable<Map.Entry<String, Object>> _properties)
    {

        List<Map.Entry<String, Object>> properties = strip(_properties);

        Map<String, FieldType> columns = createMissingColumns(project, id, properties);

        try (Connection conn = queryExecutor.getConnection()) {

            StringBuilder cols = new StringBuilder();
            StringBuilder parametrizedValues = new StringBuilder();
            Iterator<Map.Entry<String, Object>> stringIterator = properties.iterator();

            if (stringIterator.hasNext()) {
                while (stringIterator.hasNext()) {
                    Map.Entry<String, Object> next = stringIterator.next();

                    if (!next.getKey().equals(PRIMARY_KEY) && !next.getKey().equals("created_at")) {
                        if (!columns.containsKey(next.getKey())) {
                            continue;
                        }
                        if (cols.length() > 0) {
                            cols.append(", ");
                            parametrizedValues.append(", ");
                        }
                        cols.append(checkTableColumn(next.getKey()));
                        parametrizedValues.append('?');
                    }
                }
            }

            if (parametrizedValues.length() > 0) {
                parametrizedValues.append(", ");
            }
            parametrizedValues.append('?');

            if (cols.length() > 0) {
                cols.append(", ");
            }
            cols.append("created_at");

            if (id != null) {
                parametrizedValues.append(", ").append('?');
                cols.append(", ").append(PRIMARY_KEY);
            }

            PreparedStatement statement = conn.prepareStatement("INSERT INTO  " + getUserTable(project, false) + " (" + cols +
                    ") values (" + parametrizedValues + ") RETURNING " + PRIMARY_KEY);

            long createdAt = -1;
            int i = 1;
            for (Map.Entry<String, Object> o : properties) {
                if (o.getKey().equals(PRIMARY_KEY)) {
                    throw new RakamException(String.format("User property %s is invalid. It's used as primary key", PRIMARY_KEY), BAD_REQUEST);
                }

                if (!columns.containsKey(o.getKey())) {
                    continue;
                }

                if (o.getKey().equals("created_at")) {
                    try {
                        createdAt = DateTimeUtils.parseTimestamp(o.getValue());
                    }
                    catch (Exception e) {
                        createdAt = Instant.now().toEpochMilli();
                    }
                }
                else {
                    FieldType fieldType = columns.get(o.getKey());
                    statement.setObject(i++, getJDBCValue(fieldType, o.getValue(), conn));
                }
            }

            statement.setTimestamp(i++, new java.sql.Timestamp(createdAt == -1 ? Instant.now().toEpochMilli() : createdAt));
            if (id != null) {
                statement.setObject(i++, id);
            }

            ResultSet resultSet;
            try {
                resultSet = statement.executeQuery();
            }
            catch (SQLException e) {
                if (e.getMessage().contains("duplicate key value")) {
                    setUserProperty(project, id, properties, false);
                    return id;
                }
                else {
                    throw e;
                }
            }
            resultSet.next();
            return resultSet.getObject(1);
        }
        catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    private String sqlArrayTypeName(FieldType fieldType)
    {
        if (fieldType.isArray()) {
            throw new UnsupportedOperationException();
        }
        switch (fieldType) {
            case BOOLEAN:
                return "boolean";
            case STRING:
                return "varchar";
            case DOUBLE:
                return "double precision";
            case LONG:
                return "bigint";
            case INTEGER:
                return "int";
            case DECIMAL:
                return "decimal";
            case TIMESTAMP:
                return "timestamp";
            case TIME:
                return "time";
            case DATE:
                return "date";
            default:
                if (fieldType.isArray()) {
                    return fieldType.getArrayElementType() + "[]";
                }
                if (fieldType.isMap()) {
                    return "jsonb";
                }
                throw new UnsupportedOperationException();
        }
    }

    public Object getJDBCValue(FieldType fieldType, Object value, Connection conn)
            throws SQLException
    {
        if (value == null) {
            return null;
        }
        if (fieldType.isArray()) {
            if (value instanceof List) {
                FieldType arrayType = fieldType.getArrayElementType();
                List value1 = (List) value;

                Object[] objects = new Object[value1.size()];
                for (int i = 0; i < value1.size(); i++) {
                    objects[i] = getJDBCValue(arrayType, value1.get(i), conn);
                }
                return conn.createArrayOf(sqlArrayTypeName(arrayType), objects);
            }
            else {
                return null;
            }
        }
        if (fieldType.isMap()) {
            if (value instanceof Map) {
                PGobject jsonObject = new PGobject();
                jsonObject.setType("jsonb");
                jsonObject.setValue(JsonHelper.encode(value));
                return jsonObject;
            }
            else {
                return null;
            }
        }

        switch (fieldType) {
            case TIMESTAMP:
            case DATE:
                try {
                    return new Timestamp(DateTimeUtils.parseTimestamp(value));
                }
                catch (Exception e) {
                    return null;
                }
            case LONG:
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
                return safeCast(Long::parseLong, value.toString());
            case DECIMAL:
            case DOUBLE:
                if (value instanceof Number) {
                    return ((Number) value).doubleValue();
                }
                return safeCast(Double::parseDouble, value.toString());
            case INTEGER:
                if (value instanceof Number) {
                    return ((Number) value).intValue();
                }
                return safeCast(Integer::parseInt, value.toString());
            case STRING:
                return value.toString();
            case TIME:
                return parseTime(value);
            case BOOLEAN:
                return value instanceof Boolean ? value : !value.equals("0");
            default:
                throw new UnsupportedOperationException();
        }
    }

    private <T> Object safeCast(Function<String, T> func, String value)
    {
        try {
            return func.apply(value);
        }
        catch (Exception e) {
            if (value.toLowerCase(Locale.ENGLISH).equals(Boolean.TRUE.toString())) {
                return 1;
            }
            return null;
        }
    }

    private Time parseTime(Object value)
    {
        if (value instanceof String) {
            try {
                return Time.valueOf((String) value);
            }
            catch (Exception e) {
                return null;
            }
        }
        else {
            return null;
        }
    }

    @Override
    public List<Object> batchCreate(String project, List<User> users)
    {
        // may use transaction when we start to use Postgresql 9.5. Since we use insert or merge, it doesn't work right now.
        return users.stream()
                .map(user -> {
                    Object o = create(project, user.id, user.properties);
                    if (user.api != null) {
                        throw new RakamException("api property in User object is not allowed in batch endpoint", BAD_REQUEST);
                    }
                    return o;
                })
                .collect(Collectors.toList());
    }

    private void createColumn(String project, Object id, String column, Object value)
    {
        createColumnInternal(project, id, column, value, true);
    }

    private void createColumnInternal(String project, Object id, String column, Object value, boolean retry)
    {
        // it must be called from a separated transaction, otherwise it may lock table and the other insert may cause deadlock.
        try (Connection conn = queryExecutor.getConnection()) {
            try {
                if(value == null) {
                    return;
                }
                conn.createStatement().execute(format("alter table %s add column %s %s",
                        getUserTable(project, false), checkTableColumn(column), getPostgresqlType(value.getClass())));
            }
            catch (SQLException e) {
                Map<String, FieldType> fields = loadColumns(project);
                if (fields.containsKey(column)) {
                    return;
                }

                if (getMetadata(project).stream()
                        .anyMatch(col -> col.getName().equals(column))) {
                    // what if the type does not match?
                    return;
                }

                if (retry) {
                    FieldType other = (id instanceof Long ? FieldType.LONG :
                            (id instanceof Integer ? FieldType.INTEGER : FieldType.STRING));
                    FieldType fieldType = configManager.setConfigOnce(project, InternalConfig.USER_TYPE.name(), other);

                    createProjectIfNotExists(project, fieldType.isNumeric());

                    createColumnInternal(project, id, column, value, false);
                }
                else {
                    throw e;
                }
            }
        }
        catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    public abstract List<String> getEventFilterPredicate(String project, List<EventFilter> eventFilter);

    @Override
    public CompletableFuture<QueryResult> filter(String project, List<String> selectColumns, Expression filterExpression, List<EventFilter> eventFilter, Sorting sortColumn, long limit, String offset)
    {
        checkProject(project);
        List<SchemaField> metadata = getMetadata(project);

        if (metadata.isEmpty()) {
            return CompletableFuture.completedFuture(QueryResult.empty());
        }
        Stream<SchemaField> projectColumns = metadata.stream();
        if (selectColumns != null) {
            projectColumns = projectColumns.filter(column -> selectColumns.contains(column.getName()));
        }
        // TODO: fail id column is not exist.
        String columns = Joiner.on(", ").join(projectColumns.map(col -> col.getName())
                .toArray());

        LinkedList<String> filters = new LinkedList<>();
        if (filterExpression != null) {
            filters.add(new ExpressionFormatter.Formatter().process(filterExpression, true));
        }

        if (eventFilter != null && !eventFilter.isEmpty()) {
            filters.addAll(getEventFilterPredicate(project, eventFilter));
        }

        if (sortColumn != null && !metadata.stream().anyMatch(col -> col.getName().equals(sortColumn.column))) {
            throw new IllegalArgumentException(format("sorting column does not exist: %s", sortColumn.column));
        }

        String orderBy = sortColumn == null ? "" : format(" ORDER BY %s %s", sortColumn.column, sortColumn.order);

        boolean isEventFilterActive = eventFilter != null && !eventFilter.isEmpty();

        QueryExecution query = (isEventFilterActive ? getExecutorForWithEventFilter() : queryExecutor)
                .executeRawQuery(format("SELECT %s FROM %s %s %s LIMIT %s",
                        columns, getUserTable(project, isEventFilterActive), filters.isEmpty() ? "" : " WHERE " + Joiner.on(" AND ").join(filters), orderBy, limit, offset));

        CompletableFuture<QueryResult> dataResult = query.getResult();

        if (!isEventFilterActive) {
            StringBuilder builder = new StringBuilder();
            builder.append("SELECT count(*) FROM " + getUserTable(project, false));
            if (filterExpression != null) {
                builder.append(" WHERE ").append(filters.get(0));
            }

            QueryExecution totalResult = queryExecutor.executeRawQuery(builder.toString());

            CompletableFuture<QueryResult> result = new CompletableFuture<>();
            CompletableFuture.allOf(dataResult, totalResult.getResult()).whenComplete((__, ex) -> {
                QueryResult data = dataResult.join();
                QueryResult totalResultData = totalResult.getResult().join();
                if (ex == null && !data.isFailed() && !totalResultData.isFailed()) {
                    Object v1 = totalResultData.getResult().get(0).get(0);
                    result.complete(new QueryResult(data.getMetadata(), data.getResult(),
                            ImmutableMap.of(QueryResult.TOTAL_RESULT, v1)));
                }
                else if (ex != null) {
                    result.complete(QueryResult.errorResult(new QueryError(ex.getMessage(), null, 0, null, null)));
                }
                else {
                    result.complete(data);
                }
            });

            return result;
        }
        else {
            return dataResult;
        }
    }

    @Override
    public List<SchemaField> getMetadata(String project)
    {
        checkProject(project);
        LinkedList<SchemaField> columns = new LinkedList<>();

        try (Connection conn = queryExecutor.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            String[] userTable = getUserTable(project, false).split("\\.", 2);
            ResultSet indexInfo = metaData.getIndexInfo(null, userTable[0], userTable[1], true, false);
            ResultSet dbColumns = metaData.getColumns(null, userTable[0], userTable[1], null);

            Set<String> uniqueColumns = Sets.newHashSet();
            while (indexInfo.next()) {
                uniqueColumns.add(indexInfo.getString("COLUMN_NAME"));
            }

            while (dbColumns.next()) {
                String columnName = dbColumns.getString("COLUMN_NAME");
                FieldType fieldType;
                try {
                    fieldType = fromSql(dbColumns.getInt("DATA_TYPE"), dbColumns.getString("TYPE_NAME"));
                }
                catch (IllegalStateException e) {
                    continue;
                }
                columns.add(new SchemaField(columnName, fieldType, uniqueColumns.contains(columnName), null, null, null));
            }
            return columns;
        }
        catch (SQLException e) {
            throw new IllegalStateException("couldn't get metadata from plugin.user.storage");
        }
    }

    public abstract String getUserTable(String project, boolean isEventFilterActive);

    @Override
    public CompletableFuture<User> getUser(String project, Object userId)
    {
        checkProject(project);
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = queryExecutor.getConnection()) {
                PreparedStatement ps = conn.prepareStatement(format("select * from %s where %s = ?", getUserTable(project, false), PRIMARY_KEY));

                Optional<FieldType> unchecked = userTypeCache.getUnchecked(project);

                if (!unchecked.isPresent() || !unchecked.get().isNumeric()) {
                    ps.setString(1, userId.toString());
                }
                else if (unchecked.get() == FieldType.LONG) {
                    long x;
                    try {
                        x = Long.parseLong(userId.toString());
                    }
                    catch (NumberFormatException e) {
                        throw new RakamException("User id is invalid", BAD_REQUEST);
                    }

                    ps.setLong(1, x);
                }
                else if (unchecked.get() == FieldType.INTEGER) {
                    int x;
                    try {
                        x = Integer.parseInt(userId.toString());
                    }
                    catch (NumberFormatException e) {
                        throw new RakamException("User id is invalid", BAD_REQUEST);
                    }

                    ps.setInt(1, x);
                }

                ResultSet resultSet = ps.executeQuery();

                Map<String, Object> properties = new HashMap<>();
                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount() + 1;

                while (resultSet.next()) {
                    for (int i = 1; i < columnCount; i++) {
                        String key = metaData.getColumnName(i);
                        if (!key.equals(PRIMARY_KEY)) {
                            Object value = resultSet.getObject(i);
                            if(value == null) {
                                continue;
                            }
                            if (value instanceof Timestamp) {
                                value = ((Timestamp) value).toInstant();
                            }
                            properties.put(key, value);
                        }
                    }
                }
                return new User(userId, null, properties);
            }
            catch (SQLException e) {
                throw Throwables.propagate(e);
            }
        });
    }

    @Override
    public void setUserProperty(String project, Object userId, Map<String, Object> properties)
    {
        setUserProperty(project, userId, properties.entrySet(), false);
    }

    @Override
    public void setUserPropertyOnce(String project, Object userId, Map<String, Object> properties)
    {
        setUserProperty(project, userId, properties.entrySet(), true);
    }

    public void setUserProperty(String project, Object userId, Iterable<Map.Entry<String, Object>> _properties, boolean onlyOnce)
    {
        if (userId == null) {
            throw new RakamException("User id is not set.", BAD_REQUEST);
        }

        List<Map.Entry<String, Object>> properties = strip(_properties);
        if (properties.isEmpty()) {
            return;
        }

        Map<String, FieldType> columns = createMissingColumns(project, userId, properties);

        StringBuilder builder = new StringBuilder("update " + getUserTable(project, false) + " set ");
        Iterator<Map.Entry<String, Object>> entries = properties.iterator();
        boolean hasColumn = false;
        if (entries.hasNext()) {

            while (entries.hasNext()) {
                Map.Entry<String, Object> entry = entries.next();

                if(!columns.containsKey(entry.getKey())) {
                    continue;
                }

                if(!hasColumn) {
                    hasColumn = true;
                } else {
                    builder.append(", ");
                }

                builder.append(checkTableColumn(entry.getKey()))
                        .append((onlyOnce || entry.getKey().equals("created_at")) ?
                                " = coalesce(" + checkTableColumn(entry.getKey()) + ", ?)" : " = ?");
            }
        }

        if(!hasColumn) {
            builder.append("created_at = created_at");
        }

        builder.append(" where " + PRIMARY_KEY + " = ?");

        try (Connection conn = queryExecutor.getConnection()) {
            PreparedStatement statement = conn.prepareStatement(builder.toString());
            int i = 1;
            for (Map.Entry<String, Object> entry : properties) {
                FieldType fieldType = columns.get(entry.getKey());
                if (fieldType == null) {
                    continue;
                }
                statement.setObject(i++, getJDBCValue(fieldType, entry.getValue(), conn));
            }
            Optional<FieldType> fieldType = userTypeCache.getUnchecked(project);
            if (!fieldType.isPresent() || fieldType.get() == FieldType.STRING) {
                statement.setString(i++, userId.toString());
            }
            else if (fieldType.get() == FieldType.INTEGER) {
                statement.setInt(i++, (userId instanceof Number) ? ((Number) userId).intValue() :
                        Integer.parseInt(userId.toString()));
            }
            else if (fieldType.get() == FieldType.LONG) {
                statement.setLong(i++, (userId instanceof Number) ? ((Number) userId).longValue() :
                        Integer.parseInt(userId.toString()));
            }
            else {
                throw new IllegalStateException();
            }

            i = statement.executeUpdate();
            if (i == 0) {
                createInternal(project, userId, properties);
            }
        }
        catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    private String getPostgresqlType(Class clazz)
    {
        if (clazz.equals(String.class)) {
            return "text";
        }
        else if (clazz.equals(Float.class) || clazz.equals(Double.class)) {
            return "double precision";
        }
        else if (Number.class.isAssignableFrom(clazz)) {
            return "bigint";
        }
        else if (clazz.equals(Boolean.class)) {
            return "bool";
        }
        else if (Collection.class.isAssignableFrom(clazz)) {
            return getPostgresqlType((Class) ((ParameterizedType) getClass().getGenericInterfaces()[0]).getActualTypeArguments()[0]) + "[]";
        }
        else if (Map.class.isAssignableFrom(clazz)) {
            return "jsonb";
        }
        else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public void createProjectIfNotExists(String project, boolean userIdIsNumeric)
    {
        checkProject(project);
        queryExecutor.executeRawStatement(format("CREATE TABLE IF NOT EXISTS %s (" +
                "  %s " + (userIdIsNumeric ? "serial" : "text") + " NOT NULL,\n" +
                "  created_at timestamp NOT NULL,\n" +
                "  PRIMARY KEY (%s)" +
                ")", getUserTable(project, false), PRIMARY_KEY, PRIMARY_KEY)).getResult().join();
    }

    @Override
    public void dropProjectIfExists(String project)
    {
        checkProject(project);
        QueryResult result = queryExecutor.executeRawStatement(format("DROP TABLE IF EXISTS %s",
                getUserTable(project, false))).getResult().join();
        propertyCache.invalidateAll();
        userTypeCache.invalidateAll();
        if (result.isFailed()) {
            throw new IllegalStateException(result.toString());
        }
    }

    @Override
    public void unsetProperties(String project, Object user, List<String> properties)
    {
        setUserProperty(project, user, Iterables.transform(properties,
                input -> new SimpleImmutableEntry<>(input, null)), false);
    }

    @Override
    public void incrementProperty(String project, Object userId, String property, double value)
    {
        Map<String, FieldType> columns = createMissingColumns(project, userId, ImmutableList.of(new SimpleImmutableEntry<>(property, value)));

        FieldType fieldType = columns.get(property);
        if (fieldType == null) {
            createColumn(project, userId, property, 0);
        }

        if (!fieldType.isNumeric()) {
            throw new RakamException(String.format("The property the is %s and it can't be incremented.", fieldType.name()),
                    BAD_REQUEST);
        }

        try (Connection conn = queryExecutor.getConnection()) {
            String tableRef = checkTableColumn(stripName(property));
            Statement statement = conn.createStatement();
            int execute = statement.executeUpdate("update " + getUserTable(project, false) +
                    " set " + tableRef + " = " + value + " + coalesce(" + tableRef + ", 0)");
            if (execute == 0) {
                create(project, userId, ImmutableMap.of(property, value));
            }
        }
        catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }
}
