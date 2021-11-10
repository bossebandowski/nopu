# nopu

## Release Notes v0.2

- network identical to release v0.1
- separated FC layer state machine from main accelerator loop to prepare for integration of conv and pool layers
- decided against increasing number of FC layers due to overflows with 3+ layers
- added software simulator to develop pseudocode for conv and pool layers. To run it:
    - prepare environment
        ```
        python3 -m venv venv
        source venv/bin/activate
        pip install -U pip cython setuptools wheel
        pip install -r model_prep/requirements.txt
        ```
    - train and quantize model
        ```
        cd modelprep/src
        python quant_pipeline.py --train --model basic_fc
        ```
    - run simulator
        ```
        python simulator.py
        ```

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
    gross execution time per inference (including img load): 1769122
    ```
- **Speed**
    - clock cycles per inference: 1769122
    - max frequency: 80 MHz
    - inferences per second: 45.22

- **Memory requirements**

    | component         | datapoints     | width [bit] | kB |
    |--------------|-----------|------------| --- |
    | image | 784      | 32        | 3.06
    | layer 0 weights      | 78400  | 8       | 76.6
    | layer 0 biases      | 100  | 32       | 0.391
    | layer 1 weights      | 1000  | 8       | 0.977
    | layer 1 biases      | 10  | 32       | 0.0391
    | **sum** | | | **81.1**

- **Accuracy**
97.58%

## Synthesis Report

Flow Status	Successful - Tue Nov 02 14:19:12 2021

Quartus Prime Version	19.1.0 Build 670 09/22/2019 Patches 0.02i SJ Lite Edition

Revision Name	patmos

Top-level Entity Name	patmos_top

Family	Cyclone IV E

Device	EP4CE115F29C7

Timing Models	Final

Total logic elements	20,157 / 114,480 ( 18 % )

Total registers	6650

Total pins	57 / 529 ( 11 % )

Total virtual pins	0

Total memory bits	146,624 / 3,981,312 ( 4 % )

Embedded Multiplier 9-bit elements	24 / 532 ( 5 % )

Total PLLs	1 / 4 ( 25 % )
