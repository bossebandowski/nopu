#!/bin/bash

PATMOSPATH=$HOME/t-crest/patmos
set -e

pushd $PATMOSPATH
quartus_sta hardware/quartus/altde2-all/patmos.qpf
popd