# nopu

## Introduction

This repo is used to program practical CNN hardware accelerators on FPGA boards. It contains the entire pipeline from model quantization and evaluation to behavioural hardware description.

## Tested With

- Quartus 2019.1
- Altera de2-115
- Python 3.8
- CUDA 11.1 (Optional, only for GPU support during network prep)
- Ubuntu 20.04

## Setup

- Build patmos
follow the instructions on `https://github.com/t-crest/patmos`

- Clone this repo and copy nopu project files into the right patmos locations
```
./scripts/setup_patmos.sh
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
./scripts/run_patemu.sh
```


### Synthesize and Program Accelerator

First, generate the vhdl code
```
cd ~/t-crest/patmos
make gen
```

Then, use quartus to open
```
~/t-crest/patmos/hardware/quartus/altde2-115/patmos.qpf
```
Then follow the standard procedure for compilation and programming.
Alternatively, use the patmos cli like so:

```
make BOOTAPP=bootable-bootloader APP=\<app_name> tools comp gen synth config download
```

## Acknowledgements