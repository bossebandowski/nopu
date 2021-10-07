#!/bin/bash

PATMOSPATH=$HOME/t-crest/patmos
set -e

sh ./scripts/setup_patmos.sh
pushd $PATMOSPATH
make comp APP=test
patemu tmp/test.elf
popd
