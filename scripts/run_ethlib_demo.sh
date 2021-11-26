#!/bin/bash

PATMOSPATH=$HOME/t-crest/patmos
set -e

pushd $PATMOSPATH
make BOOTAPP=bootable-bootloader BOARD=altde2-all clean tools comp gen synth config
make APP=ethlib_demo comp download
popd
