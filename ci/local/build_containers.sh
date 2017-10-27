#!/usr/bin/env bash

set -e

PROJECT_NAME=akvo-lumen
CIRCLE_SHA1=any

#lein uberjar

docker build --rm=false -t eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps:$CIRCLE_SHA1 .
docker tag eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps:$CIRCLE_SHA1 eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps:develop

docker build --rm=false -t eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps-windshaft:$CIRCLE_SHA1 ./windshaft
docker tag eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps-windshaft:$CIRCLE_SHA1 eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps-windshaft:develop