# nopu

## Release Notes v1.1

- the very first accelerated convolutional neural network with pooling layer
    - 3x3x16 convolutional layer + relu
    - 2x2x2 pooling layer
    - 2704x12 fully connected layer + softmax
- pooling layer implemented both in simulator and hardware description
- other minor updates to simulator (peek layers etc with command line args)

## Setup and Run

- Build patmos
follow the instructions on `https://github.com/t-crest/patmos`

- Clone this repo and use the scripts to build patmos plus the coprocessor and run a test script.
  Careful if you are developing in your `~/t-crest/patmos` folder, these scripts might overwrite some files. 
    ```
    chmod +x ./scripts/*.sh
    ./scripts/build_patemu.sh
    ./scripts/run_patemu.sh
    ```

## Results

- **Test script output**
    ```
    Loading network...done
    EXPECTED 7, RETURNED 7
    EXPECTED 2, RETURNED 2
    EXPECTED 1, RETURNED 1
    EXPECTED 0, RETURNED 0
    EXPECTED 4, RETURNED 4
    EXPECTED 1, RETURNED 1
    EXPECTED 4, RETURNED 4
    EXPECTED 9, RETURNED 9
    EXPECTED 5, RETURNED 6
    EXPECTED 9, RETURNED 9
    gross execution time per inference (including img load): 774330
    ```
- **Speed**
    - clock cycles per inference: 774330
    - max frequency: 80 MHz
    - inferences per second: 103.3

- **Memory requirements**

    | component         | datapoints     | width [bit] | kB |
    |--------------|-----------|------------| --- |
    | image | 784      | 32        | 3.06
    | layer 0 weights      | 192  | 8       | 0.188
    | layer 0 biases      | 16  | 32       | 0.0625
    | layer 2 weights      | 32448  | 8       | 31.7
    | layer 2 biases      | 12  | 32       | 0.0469
    | **sum** | | | **35.1**

- **Accuracy**
98.80%

## Synthesis Report

Flow Status	Successful - Tue Nov 09 13:43:20 2021

Quartus Prime Version	19.1.0 Build 670 09/22/2019 Patches 0.02i SJ Lite Edition

Revision Name	patmos

Top-level Entity Name	patmos_top

Family	Cyclone IV E

Device	EP4CE115F29C7

Timing Models	Final

Total logic elements	22,564 / 114,480 ( 20 % )

Total registers	7200

Total pins	57 / 529 ( 11 % )

Total virtual pins	0

Total memory bits	1,195,200 / 3,981,312 ( 30 % )

Embedded Multiplier 9-bit elements	40 / 532 ( 8 % )

Total PLLs	1 / 4 ( 25 % )
