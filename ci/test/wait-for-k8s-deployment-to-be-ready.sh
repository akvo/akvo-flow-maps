#!/usr/bin/env bash

PROJECT_NAME=akvo-lumen

starttime=`date +%s`

while [ $(( $(date +%s) - 300 )) -lt ${starttime} ]; do

    consumer_status=`kubectl get pods -l "flow-maps-version=$TRAVIS_COMMIT,run=flow-maps-consumer" -ao jsonpath='{range .items[*].status.containerStatuses[*]}{@.name}{" ready="}{@.ready}{"\n"}{end}'`
    windshaft_status=`kubectl get pods -l "flow-maps-version=$TRAVIS_COMMIT,run=flow-maps-windshaft" -ao jsonpath='{range .items[*].status.containerStatuses[*]}{@.name}{" ready="}{@.ready}{"\n"}{end}'`
    nginx_status=`kubectl get pods -l "flow-maps-version=$TRAVIS_COMMIT,run=flow-maps-nginx" -ao jsonpath='{range .items[*].status.containerStatuses[*]}{@.name}{" ready="}{@.ready}{"\n"}{end}'`

# We want to make sure that when we hit the ingress from the integration test, we are hitting the new containers,
# hence we wait until the old pods are gone.
# Another possibility could be to check that the service is pointing just to the new containers.
    old_consumer_status=`kubectl get pods -l "flow-maps-version!=$TRAVIS_COMMIT,run=flow-maps-consumer" -ao jsonpath='{range .items[*].status.containerStatuses[*]}{@.name}{" ready="}{@.ready}{"\n"}{end}'`
    old_windshaft_status=`kubectl get pods -l "flow-maps-version!=$TRAVIS_COMMIT,run=flow-maps-windshaft" -ao jsonpath='{range .items[*].status.containerStatuses[*]}{@.name}{" ready="}{@.ready}{"\n"}{end}'`
    old_nginx_status=`kubectl get pods -l "flow-maps-version!=$TRAVIS_COMMIT,run=flow-maps-nginx" -ao jsonpath='{range .items[*].status.containerStatuses[*]}{@.name}{" ready="}{@.ready}{"\n"}{end}'`

    if [[ ${consumer_status} =~ "ready=true" ]] && [[ ${windshaft_status} =~ "ready=true" ]] && [[ ${nginx_status} =~ "ready=true" ]] && ! [[ ${old_consumer_status} =~ "ready" ]] && ! [[ ${old_windshaft_status} =~ "ready" ]] && ! [[ ${old_nginx_status} =~ "ready" ]] ; then
        exit 0
    else
        echo "Waiting for the containers to be ready"
        sleep 10
    fi
done

echo "Containers not ready after 5 minutes or old containers not stopped"

kubectl get pods -l "run=flow-maps-consumer" -ao jsonpath='{range .items[*].status.containerStatuses[*]}{@.name}{" ready="}{@.ready}{"\n"}{end}'
kubectl get pods -l "run=flow-maps-windshaft" -ao jsonpath='{range .items[*].status.containerStatuses[*]}{@.name}{" ready="}{@.ready}{"\n"}{end}'
kubectl get pods -l "run=flow-maps-nginx" -ao jsonpath='{range .items[*].status.containerStatuses[*]}{@.name}{" ready="}{@.ready}{"\n"}{end}'

exit 1
