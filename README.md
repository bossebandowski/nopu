# nopu

## Release Notes v1.2

- basic conv architecture working with MNIST in simulation and in hardware
    - 3x3x16 conv layer + relu
    - 2x2x2 pool layer
    - 3x3x16 conv layer + relu
    - 2x2x2 pool layer
    - 400x64 fc layer + relu
    - 64x12 fc layer + softmax
- increase in model size results in longer execution times and memory requirements but is a necessary step towards more advanced computer vision tasks
- fixed overflow issues from previous releases by applying tensorflow requantization after FC and CONV layers. This is necessary and contributes to increased run times, but also opens the door to much larger networks


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
    EXPECTED 5, RETURNED 5
    EXPECTED 9, RETURNED 9
    gross execution time per inference (including img load): 1749540
    ```
- **Speed**
    - clock cycles per inference: 1749540
    - max frequency: 80 MHz
    - inferences per second: 45.73

- **Memory requirements**

    
    | component         | datapoints     | width [bit] | kB |
    |--------------|-----------|------------| --- |
    | image | 784      | 32        | 3.06
    | layer 0 weights      | 192  | 8       | 0.188
    | layer 0 biases      | 16  | 32       | 0.0625
    | layer 2 weights      | 3072  | 8       | 3
    | layer 2 biases      | 16  | 32       | 0.0625
    | layer 4 weights      | 25600  | 8       | 25
    | layer 4 biases      | 64  | 32       | 0.25
    | layer 5 weights      | 768  | 8       | 0.75
    | layer 5 biases      | 12  | 32       | 0.0469
    | Ms      | 36  | 32       | 1.13
    | **sum** | | | **32.4**


- **Accuracy**
95 - 99%

## Synthesis Report

Flow Status	Successful - Wed Nov 17 15:57:22 2021

Quartus Prime Version	19.1.0 Build 670 09/22/2019 Patches 0.02i SJ Lite Edition

Revision Name	patmos

Top-level Entity Name	patmos_top

Family	Cyclone IV E

Device	EP4CE115F29C7

Timing Models	Final

Total logic elements	25,132 / 114,480 ( 22 % )

Total registers	7592

Total pins	57 / 529 ( 11 % )

Total virtual pins	0

Total memory bits	1,195,200 / 3,981,312 ( 30 % )

Embedded Multiplier 9-bit elements	102 / 532 ( 19 % )

Total PLLs	1 / 4 ( 25 % )
