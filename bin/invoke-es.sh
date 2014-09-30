#!/bin/bash

# ensure sdk is installed
bin/install-elasticsearch-instance.sh

.es/bin/elasticsearch "$@"
