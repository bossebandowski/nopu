#!/bin/bash

PATMOSPATH=$HOME/t-crest/patmos
set -e

sh ./scripts/setup_patmos.sh
pushd $PATMOSPATH
make clean tools emulator
make comp APP=my_simple_test
patemu tmp/my_simple_test.elf
popd
