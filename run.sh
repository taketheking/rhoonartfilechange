#!/bin/bash
java -Xmx2048m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:MaxGCPauseMillis=200 \
  -Dsun.net.client.defaultConnectTimeout=600000 \
  -Dsun.net.client.defaultReadTimeout=600000 \
  -Dserver.tomcat.connection-timeout=1800000 \
  -Dspring.mvc.async.request-timeout=1800000 \
  -jar plplsettlement-0.0.1-SNAPSHOT.jar
