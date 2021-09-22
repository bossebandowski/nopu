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

### Generate Accelerator Code

### Synthesize Accelerator

### Program Accelerator

## Acknowledgements