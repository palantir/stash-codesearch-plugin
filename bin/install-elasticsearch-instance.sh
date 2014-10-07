#!/bin/bash

# 1.3.3
DOWNLOAD_URL="https://download.elasticsearch.org/elasticsearch/elasticsearch/elasticsearch-1.3.3.tar.gz"

# To find new URLs, see: http://www.elasticsearch.org/download/

INSTALL_BIN=`pwd`/.es.tar.gz
INSTALL_DIR=`pwd`/.es
TMP_DIR=`pwd`/.tmp

if [[ `uname` -ne "Linux" ]]; then
    echo "ERROR: this script currently only supports linux" && exit 1
fi

if [[ ! -x `which wget` ]]; then
    echo "ERROR: this script requires wget" && exit 1
fi

if [[ ! -f $INSTALL_BIN ]]; then
    # download es
    echo "Downloading ElasticSearch"
    wget -O$INSTALL_BIN $DOWNLOAD_URL || exit 1;
fi

if [[ ! -d $INSTALL_DIR ]]; then
    rm -rf $TMP_DIR
    mkdir $TMP_DIR && cd $TMP_DIR && tar -xzf $INSTALL_BIN
    ES_DIR=`ls -d elasticsearch-*`
    mv $ES_DIR $INSTALL_DIR
    cd .. && rm -rf $TMP_DIR
fi

exit 0
