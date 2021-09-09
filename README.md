# nopu

## Introduction

This repo is used to program practical CNN hardware accelerators on FPGA boards. It contains the entire pipeline from model quantization and evaluation to behavioural hardware description.

## Required Tools and Hardware

- Vivado
- FPGA

## Setup

- Create venv
```
python3 -m venv venv
source venv/bin/activate
```
- Install pytorch according to your environment: https://pytorch.org/, f.x.
```
pip install torch==1.9.0+cu111 torchvision==0.10.0+cu111 torchaudio==0.9.0 -f https://download.pytorch.org/whl/torch_stable.html
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