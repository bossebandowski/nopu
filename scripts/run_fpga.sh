#!/bin/bash

PATMOSPATH=$HOME/t-crest/patmos
set -e
sh ./scripts/setup_patmos.sh

pushd $PATMOSPATH
make APP=hardware_test BOARD=altde2-all config comp download
popd
