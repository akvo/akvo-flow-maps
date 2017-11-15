#!/usr/bin/env sh

if [ -f "/pg-certs/server.crt" ]; then
    keytool -import -trustcacerts -keystore /usr/lib/jvm/default-jvm/jre/lib/security/cacerts -storepass changeit -noprompt -alias postgrescert -file /pg-certs/server.crt
fi

JAVA_CERTS_OPTS=""

echo "LSSSSsss"
ls -l /kafka-certs

if [ -f "/kafka-certs/client.p12" ]; then
    keytool -importkeystore -srckeystore /kafka-certs/ca.truststore.jks -destkeystore /usr/lib/jvm/default-jvm/jre/lib/security/cacerts -srcstorepass qwerty -deststorepass changeit -srcalias caroot -destalias schemacert -noprompt
    keytool -importkeystore -srckeystore /kafka-certs/ca.truststore.jks -destkeystore /usr/lib/jvm/default-jvm/jre/lib/security/cacerts -srcstorepass qwerty -deststorepass changeit -srcalias caroot -destalias schemacert -noprompt
    JAVA_CERTS_OPTS="-Djavax.net.ssl.keyStorePassword=asdfgh -Djavax.net.ssl.keyStore=/kafka-certs/client.p12 -Djavax.net.ssl.keyStoreType=pkcs12"
else
    echo "cert file not found"
fi

java $JAVA_CERTS_OPTS -jar /app/akvo-flow-maps.jar