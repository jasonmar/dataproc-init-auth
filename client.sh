#!/usr/bin/env bash

# Use this script to obtain a token
tokenenc=$(curl -H "Metadata-Flavor: Google" "http://metadata/computeMetadata/v1/instance/service-accounts/default/identity?audience=${AUDIENCE}&format=full" 2>/dev/null)

echo $tokenenc

curl --data "$tokenenc" $AUDIENCE/secret


