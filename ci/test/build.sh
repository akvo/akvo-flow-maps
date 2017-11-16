#!/usr/bin/env bash

set -e

BRANCH_NAME="${TRAVIS_BRANCH:=unknown}"
export PROJECT_NAME=akvo-lumen

if [ -z "$TRAVIS_COMMIT" ]; then
    export TRAVIS_COMMIT=local
fi

docker build --rm=false -t akvo-flow-dev:develop . -f Dockerfile-dev
docker run -v $HOME/.m2:/root/.m2 -v `pwd`:/app akvo-flow-dev:develop lein do test, uberjar

docker build --rm=false -t eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps-consumer:$TRAVIS_COMMIT .
docker tag eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps-consumer:$TRAVIS_COMMIT eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps-consumer:develop

docker build --rm=false -t eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps-windshaft:$TRAVIS_COMMIT ./windshaft
docker tag eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps-windshaft:$TRAVIS_COMMIT eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps-windshaft:develop

docker-compose -p akvo-flow-ci -f docker-compose.yml -f docker-compose.ci.yml up -d --build
docker-compose -p akvo-flow-ci -f docker-compose.yml -f docker-compose.ci.yml run --no-deps tests /import-and-run.sh integration-test