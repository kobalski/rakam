web: java $JAVA_OPTS -cp $(echo rakam/target/rakam-*-bundle/rakam-*/lib)/*: -Dstore.adapter=postgresql -Dstore.adapter.postgresql.url="${JDBC_DATABASE_URL}&ssl=true&sslfactory=org.postgresql.ssl.NonValidatingFactory" -Devent-stream=server -Dstore.adapter.postgresql.username=${JDBC_DATABASE_USERNAME} -Dstore.adapter.postgresql.password=${JDBC_DATABASE_PASSWORD} -Dplugin.user.enabled=${ENABLE_USER_PLUGIN} -Dreal-time.enabled=${ENABLE_REALTIME_PLUGIN} -Devent-explorer.enabled=${ENABLE_EVENT_EXPLORER_PLUGIN} -Duser.funnel-analysis.enabled=${ENABLE_FUNNEL_PLUGIN} -Duser.retention-analysis.enabled=${ENABLE_RETENTION_ANALYSIS_PLUGIN} -Dhttp.server.address=0.0.0.0:${PORT} -Dplugin.geoip.enabled=${ENABLE_GEOIP_PLUGIN} -Dstore.adapter=postgresql -Dplugin.user.storage=postgresql -Dmodule.website.mapper=true -Dmodule.website.mapper.user-agent=true -Dmodule.website.mapper.referrer=true -Dplugin.user.storage.identifier-column=id -Dstore.adapter.postgresql.max-connection=8 -Dplugin.geoip.database.url=${GEOIP_DATABASE_URL} -Dplugin.geoip.connection-type-database.url=${GEOIP_CONNECTION_TYPE_URL} -Dui.enable=false -Dautomation.enabled=false -Dmail.smtp.host=127.0.0.1 -Dmail.smtp.user=test -Dplugin.user.actions=email -Dlock-key=${LOCK_KEY} -Dplugin.user.enable_user_mapping=true -Devent.ab-testing.enabled=false -Dplugin.user.enable-user-mapping=true -Dlog-identifier=${HEROKU_APP_NAME} org.rakam.ServiceStarter
