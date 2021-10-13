#!/bin/bash

PATMOSPATH=$HOME/t-crest/patmos
set -e

./scripts/setup_patmos.sh
pushd $PATMOSPATH
make comp APP=test
patemu tmp/test.elf
popd
