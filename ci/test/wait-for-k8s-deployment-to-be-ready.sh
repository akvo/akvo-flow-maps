#!/usr/bin/env bash

PROJECT_NAME=akvo-lumen

starttime=`date +%s`

while [ $(( $(date +%s) - 120 )) -lt ${starttime} ]; do

    consumer_status=`kubectl get pods --namespace flow-maps -l "flow-maps-version=$TRAVIS_COMMIT,run=flow-maps-consumer" -ao jsonpath='{range .items[*].status.containerStatuses[*]}{@.name}{" ready="}{@.ready}{"\n"}{end}'`
    windshaft_status=`kubectl get pods --namespace flow-maps -l "flow-maps-version=$TRAVIS_COMMIT,run=flow-maps-windshaft" -ao jsonpath='{range .items[*].status.containerStatuses[*]}{@.name}{" ready="}{@.ready}{"\n"}{end}'`

    if [[ ${consumer_status} =~ "ready=true" ]] && [[ ${windshaft_status} =~ "ready=true" ]]; then
        exit 0
    else
        sleep 10
    fi
done

echo "Containers not ready after 2 minutes"
echo $consumer_status
echo $windshaft_status
exit 1
