#!/usr/bin/env bash

keytool -import -trustcacerts -keystore /usr/lib/jvm/default-jvm/jre/lib/security/cacerts -storepass changeit -noprompt -alias postgrescert -file /pg-certs/server.crt

if [ -z "$1" ]; then
    lein repl :headless
elif [ "$1" == "integration-test" ]; then
    lein test :integration
elif [ "$1" == "kubernetes-test" ]; then
    lein test :kubernetes-test
else
    true
fi