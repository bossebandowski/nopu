#!/bin/bash

PATMOSPATH=$HOME/t-crest/patmos
set -e

# copy src files
cp hardware_src/MySimpleCoprocessor.scala $PATMOSPATH/hardware/src/main/scala/io/

# copy test scripts
cp hardware_test/my_simple_test.c $PATMOSPATH/c/

# copy config
cp hardware_config/altde2-115.xml $PATMOSPATH/hardware/config/
cp hardware_config/build.sbt $PATMOSPATH/hardware

# copy inference parameters (inputs, weights, and biases)
