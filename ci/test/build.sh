#!/usr/bin/env bash

set -e

BRANCH_NAME="${TRAVIS_BRANCH:=unknown}"
export PROJECT_NAME=akvo-lumen

if [ -z "$TRAVIS_COMMIT" ]; then
    export TRAVIS_COMMIT=local
fi

IMAGE_TAG=$(echo -n "${TRAVIS_COMMIT}" | cut -c-8)
IMAGE_NAME="akvo/akvo-maps:${IMAGE_TAG}"
export IMAGE_TAG="${IMAGE_TAG}"

docker build --rm=false -t akvo-flow-dev:develop . -f Dockerfile-dev
docker run -v $HOME/.m2:/root/.m2 -v `pwd`:/app akvo-flow-dev:develop lein uberjar

ls -lrt target

docker build --rm=false -t eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps:$IMAGE_TAG .
docker tag eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps:$IMAGE_TAG eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps:develop

docker build --rm=false -t eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps-windshaft:$IMAGE_TAG ./windshaft
docker tag eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps-windshaft:$IMAGE_TAG eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps-windshaft:develop

docker-compose -f docker-compose-ci.yml up -d --build
docker-compose -f docker-compose-ci.yml run tests lein test
rc=$?

docker-compose -f docker-compose-ci.yml down
exit $rc