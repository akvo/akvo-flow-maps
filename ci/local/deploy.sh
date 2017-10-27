#!/usr/bin/env bash

set -e

PROJECT_NAME=akvo-lumen

gcloud container clusters get-credentials test --zone europe-west1-d --project akvo-lumen

#sudo /opt/google-cloud-sdk/bin/gcloud config set container/use_client_certificate True

gcloud docker -- push eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps
gcloud docker -- push eu.gcr.io/${PROJECT_NAME}/akvo-flow-maps-windshaft

#kubectl apply -f ci/local/deployment.yaml
kubectl apply -f ci/redis-master-windshaft.yaml
kubectl apply -f ci/local/windshaft.yaml
