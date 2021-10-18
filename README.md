# nopu

## Release Notes v0.0

The very first release: A simple 784x100x10 neural network with two fully connected layers that can identify MNIST handwritten digits.
- all parameters are 32 bit wide
- no parallelism has been exploited
- io completely absent
- there are known issues with repeated inference (probably bug in the reset process)

## Setup and Run

- Build patmos
follow the instructions on `https://github.com/t-crest/patmos`

- Clone this repo and use the scripts to build patmos plus the coprocessor and run a test script 
```
chmod +x ./scripts/*.sh
./scripts/build_patemu.sh
./scripts/run_patemu.sh
```

## Results

- **Test script output**
```
inference result img_4: 4
inference result img_0: 0
inference result img_3: 3
inference result img_5: 5
gross execution time per inference (including img load): 1835338
```
- **Speed**
    - clock cycles per inference: 1835338
    - max frequency: 80 MHz
    - inferences per second: 43.6

- **Memory requirements**

| component         | datapoints     | width [bit] | kB |
|--------------|-----------|------------| --- |
| image | 784      | 32        | 3.06
| layer 0 weights      | 78400  | 32       | 306
| layer 0 biases      | 100  | 32       | 0.391
| layer 1 weights      | 1000  | 32       | 3.91
| layer 1 biases      | 10  | 32       | 0.0391
| **sum** | | | **313**

- **Accuracy**
Not measured

- **Synthesis Report**
Flow Status	Successful - Mon Oct 18 11:26:08 2021

Quartus Prime Version	19.1.0 Build 670 09/22/2019 Patches 0.02i SJ Lite Edition

Revision Name	patmos

Top-level Entity Name	patmos_top

Family	Cyclone IV E

Device	EP4CE115F29C7

Timing Models	Final

Total logic elements	20,010 / 114,480 ( 17 % )

Total registers	6697

Total pins	57 / 529 ( 11 % )

Total virtual pins	0

Total memory bits	146,624 / 3,981,312 ( 4 % )

Embedded Multiplier 9-bit elements	32 / 532 ( 6 % )

Total PLLs	1 / 4 ( 25 % )