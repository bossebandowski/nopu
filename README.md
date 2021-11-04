# nopu

## Release Notes v0.3

- network identical to release v0.2
- using BRAM instead of SRAM for network nodes. Reasons:
    - massive speedup (factor two) due to faster memory accesses
    - burst access of SRAM makes it difficult to load the right inputs when doing convolutions
- static network parameters still live in SRAM

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
    gross execution time per inference (including img load): 836376
    ```
- **Speed**
    - clock cycles per inference: 836376
    - max frequency: 80 MHz
    - inferences per second: 95.65

- **Memory requirements**

    | component         | datapoints     | width [bit] | kB |
    |--------------|-----------|------------| --- |
    | image | 784      | 32        | 3.06
    | layer 0 weights      | 78400  | 8       | 76.6
    | layer 0 biases      | 100  | 32       | 0.391
    | layer 1 weights      | 1200  | 8       | 1.17
    | layer 1 biases      | 12  | 32       | 0.0469
    | **sum** | | | **81.3**

- **Accuracy**
97.58%

## Synthesis Report

Flow Status	Successful - Thu Nov 04 12:22:37 2021

Quartus Prime Version	19.1.0 Build 670 09/22/2019 Patches 0.02i SJ Lite Edition

Revision Name	patmos

Top-level Entity Name	patmos_top

Family	Cyclone IV E

Device	EP4CE115F29C7

Timing Models	Final

Total logic elements	20,059 / 114,480 ( 18 % )

Total registers	6710

Total pins	57 / 529 ( 11 % )

Total virtual pins	0

Total memory bits	212,160 / 3,981,312 ( 5 % )

Embedded Multiplier 9-bit elements	24 / 532 ( 5 % )

Total PLLs	1 / 4 ( 25 % )