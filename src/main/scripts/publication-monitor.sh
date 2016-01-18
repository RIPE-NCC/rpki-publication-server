#!/usr/bin/env bash

function error() {
     echo CRITICAL ${OUT}
     exit 2
}

CERT_DIR=${CERT_DIR:-.}

/bin/echo -n "PUBLICATION_SERVER "

OUT=`curl --fail \
    --cert ${CERT_DIR}/client-key.pem:1234567 \
    --cacert ${CERT_DIR}/pub-server.cert \
    --data '<msg type="query" version="3" xmlns="http://www.hactrn.net/uris/rpki/publication-spec/"><list/></msg>' \
    --header 'Content-Type: application/rpki-publication' \
    --insecure \
    --silent \
    --show-error \
      https://pub-server.elasticbeanstalk.com:7766/?clientId=X 2>&1`

if [ $? -ne 0 ]
then
    error
fi

echo $OUT | grep -F \
    --ignore-case \
    --quiet \
    'type="reply"'

if [ $? -ne 0 ]
then
    error
else
    echo OK
fi
