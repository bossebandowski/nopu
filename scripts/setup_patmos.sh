#!/bin/bash

PATMOSPATH=$HOME/t-crest/patmos
set -e

# copy src files
cp hardware_src/Accelerator.scala $PATMOSPATH/hardware/src/main/scala/io/
cp hardware_src/MemorySInt.scala $PATMOSPATH/hardware/src/main/scala/util/

# copy test scripts
cp -r hardware_test/accelerator $PATMOSPATH/c/
cp hardware_test/accelerator_main.c $PATMOSPATH/c/

# copy config
cp hardware_config/altde2-115.xml $PATMOSPATH/hardware/config/
cp hardware_config/build.sbt $PATMOSPATH/hardware

# copy inference parameters (inputs, weights, and biases)
