#!/bin/bash

TCRESTPATH=$HOME/t-crest
PATMOSPATH=$TCRESTPATH/patmos

cp hardware_src/Accelerator.scala $PATMOSPATH/hardware/src/main/scala/io/
cp hardware_src/MemorySInt.scala $PATMOSPATH/hardware/src/main/scala/util/
cp hardware_src/build.sbt $PATMOSPATH/hardware

cp -r hardware_test/accelerator $PATMOSPATH/c/
cp hardware_test/accelerator_main.c $PATMOSPATH/c/
