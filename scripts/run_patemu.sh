#!/bin/bash

PATMOSPATH=$HOME/t-crest/patmos
set -e

./scripts/setup_patmos.sh
pushd $PATMOSPATH
make comp APP=emulator_test
patemu tmp/emulator_test.elf
popd
