#!/bin/bash

PATMOSPATH=$HOME/t-crest/patmos
set -e

sh ./scripts/setup_patmos.sh
pushd $PATMOSPATH
make BOOTAPP=bootable-bootloader BOARD=altde2-all clean tools comp gen synth
popd