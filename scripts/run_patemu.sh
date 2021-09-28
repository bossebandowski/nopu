#!/bin/bash

PATMOSPATH=$HOME/t-crest/patmos
set -e

sh ./scripts/setup_patmos.sh
pushd $PATMOSPATH
make clean tools emulator
make comp APP=accelerator_main
patemu tmp/accelerator_main.elf
popd
