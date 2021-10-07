#!/bin/bash

PATMOSPATH=$HOME/t-crest/patmos
set -e

sh ./scripts/setup_patmos.sh
pushd $PATMOSPATH
make clean tools emulator
popd