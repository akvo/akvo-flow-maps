#!/usr/bin/env bash

set -eu

PROJECT_NAME=akvo-lumen

if [[ "${TRAVIS_BRANCH}" != "develop" ]] && [[ "${TRAVIS_BRANCH}" != "master" ]]; then
    exit 0
fi

if [[ "${TRAVIS_PULL_REQUEST}" != "false" ]]; then
    exit 0
fi

# Making sure gcloud and kubectl are installed and up to date
gcloud components install kubectl
gcloud components update
gcloud version
which gcloud kubectl

# Authentication with gcloud and kubectl
gcloud auth activate-service-account --key-file ci/gcloud-service-account.json
gcloud config set project akvo-lumen
gcloud config set container/cluster europe-west1-d
gcloud config set compute/zone europe-west1-d
gcloud config set container/use_client_certificate True

if [[ "${TRAVIS_BRANCH}" == "master" ]]; then
    gcloud container clusters get-credentials test
else
    gcloud container clusters get-credentials test
fi

# Pushing images
gcloud docker -- push eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps
gcloud docker -- push eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps-windshaft

# Deploying

sed -e "s/\$TRAVIS_COMMIT/$TRAVIS_COMMIT/" ci/test/flow-maps.yaml.template > flow-maps.yaml
sed -e "s/\$TRAVIS_COMMIT/$TRAVIS_COMMIT/" ci/test/windshaft.yaml.template > windshaft.yaml

kubectl apply -f ci/namespace.yaml
kubectl apply -f ci/redis-master-flow-maps.yaml
kubectl apply -f ci/test/windshaft.yaml
kubectl apply -f ci/test/flow-maps.yaml
kubectl apply -f ci/test/ingress.yaml
