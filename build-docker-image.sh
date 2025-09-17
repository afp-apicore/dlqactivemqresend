#!/bin/bash

docker build -t $APICORE_REGISTRY/afpscia/afpcontent/dlq-activemq-resend .

VERSION=$(xmlstarlet select -t -v "/*[local-name()=\"project\"]/*[local-name()=\"version\"]" pom.xml)

export ECR_TOKEN=$(aws ecr get-login-password --profile afp-dev-codeartifact --region eu-west-3)
echo $ECR_TOKEN | docker login --username AWS --password-stdin apicore-registry.app.afp.com
echo $ECR_TOKEN | docker login --username AWS --password-stdin 161951201144.dkr.ecr.eu-west-3.amazonaws.com

docker buildx build --push \
    -t $APICORE_REGISTRY/afpscia/afpcontent/dlq-activemq-resend:${VERSION} \
    -t $APICORE_REGISTRY/afpscia/afpcontent/dlq-activemq-resend:latest .
