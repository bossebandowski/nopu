#!/bin/bash

PATMOSPATH=$HOME/t-crest/patmos
set -e

# copy src files
cp hardware_src/CnnAccelerator.scala $PATMOSPATH/hardware/src/main/scala/cop/
cp -r hardware_src/cnnacc $PATMOSPATH/hardware/src/main/scala

# copy test scripts
cp hardware_test/* $PATMOSPATH/c/

# copy model parameters
cp model_parameters/parameters.h $PATMOSPATH/c/

# copy config
cp hardware_config/altde2-115.xml $PATMOSPATH/hardware/config/
cp hardware_config/altde2-all.xml $PATMOSPATH/hardware/config/
cp hardware_config/build.sbt $PATMOSPATH/hardware/
