# nopu

## Introduction

This repo is used to program practical CNN hardware accelerators on FPGA boards. It contains the entire pipeline from model quantization and evaluation to behavioural hardware description.

## Tested With

- Vivado 2019.2
- FPGA
- Python 3.8
- CUDA 11.1 (Optional, only for GPU support)
- Ubuntu 20.04

## Setup

- Build patmos
follow the instructions on `https://github.com/t-crest/patmos`

- Clone this repo and copy nopu project files into the right patmos locations
```
TCRESTPATH=$HOME/t-crest
PATMOSPATH=$TCRESTPATH/patmos

git clone https://github.com/bossebandowski/nopu.git
cd nopu

cp hardware_src/Accelerator.scala $PATMOSPATH/hardware/src/main/scala/io/
cp hardware_src/MemorySInt.scala $PATMOSPATH/hardware/src/main/scala/util/
# cp hardware_src/build.sbt $PATMOSPATH/hardware

cp -r hardware_test/accelerator $PATMOSPATH/c/
cp hardware_test/accelerator_main.c $PATMOSPATH/c/

cp hardware_config/altde2-115.xml $PATMOSPATH/hardware/config/
```

- Create venv
```
python3 -m venv venv
source venv/bin/activate
pip install -U pip cython setuptools wheel
```
- Install tensorflow
```
pip install tensorflow
```
- Install other requirements
```
pip install -r requirements.txt
```
- Install coco if you want to validate model performance

## Results

## Run

### Quantize Model

### Evaluate Model

### Extract Model Parameters

### Emulate Accelerator

To run the accelerator on the patmos emulator, do

```
cd $PATMOSPATH
make clean tools emulator
make comp APP=accelerator_main
patemu tmp/accelerator_main.elf
```

### Generate Accelerator Code

### Synthesize Accelerator

### Program Accelerator

## Acknowledgements