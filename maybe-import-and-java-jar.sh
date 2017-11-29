#!/usr/bin/env sh

if [ -f "/pg-certs/server.crt" ]; then
    keytool -import -trustcacerts -keystore /usr/lib/jvm/default-jvm/jre/lib/security/cacerts -storepass changeit -noprompt -alias postgrescert -file /pg-certs/server.crt
fi

JAVA_CERTS_OPTS=""

if [ -f "/kafka-certs/client.p12" ]; then
    echo "Found client certificate"
    keytool -importkeystore -srckeystore /kafka-certs/ca.truststore.jks -destkeystore /usr/lib/jvm/default-jvm/jre/lib/security/cacerts -srcstorepass qwerty -deststorepass changeit -srcalias caroot -destalias schemacert -noprompt
    JAVA_CERTS_OPTS="-Djavax.net.ssl.keyStorePassword=asdfgh -Djavax.net.ssl.keyStore=/kafka-certs/client.p12 -Djavax.net.ssl.keyStoreType=pkcs12"
fi

java $JAVA_CERTS_OPTS -Dorg.jboss.logging.provider=slf4j -jar /app/akvo-flow-maps.jar