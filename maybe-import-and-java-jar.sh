#!/usr/bin/env sh

if [ -f "/pg-certs/server.crt" ]; then
    keytool -import -trustcacerts -keystore /usr/lib/jvm/java-8-openjdk-amd64/jre/lib/security/cacerts -storepass changeit -noprompt -alias postgrescert -file /pg-certs/server.crt
fi

java -jar /app/akvo-flow-maps.jar