#!/usr/bin/env bash

echo "Importing postgres cert"
keytool -import -trustcacerts -keystore /usr/lib/jvm/default-jvm/jre/lib/security/cacerts -storepass changeit -noprompt -alias postgrescert -file /pg-certs/server.crt
echo "Importing kafka CA"
keytool -importkeystore -srckeystore /kafka-certs/ca.truststore.jks -destkeystore /usr/lib/jvm/default-jvm/jre/lib/security/cacerts -srcstorepass qwerty -deststorepass changeit -srcalias caroot -destalias schemacert -noprompt

if [ -z "$1" ]; then
    lein repl :headless
elif [ "$1" == "integration-test" ]; then
    lein test :integration
elif [ "$1" == "kubernetes-test" ]; then
    lein test :kubernetes-test
else
    true
fi