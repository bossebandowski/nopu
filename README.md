# nopu

## Release Notes v1.3

- moved from MNIST to CIFAR10 (`https://www.cs.toronto.edu/~kriz/cifar.html`)
- image classification on small 3-channel input images. Input shape (32x32x3)
- same model architecture as previously, but new input layer resulting in new FC sizes:
    - 576x64 fc layer + relu
    - 64x12 fc layer + softmax
- the dataset is much more challenging, resulting in lower accuracy
- most of the clock cycles are used on image transfers, this needs to be more efficient but will be visited when implementing IO stuff

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
    EXPECTED 3, RETURNED 3
    EXPECTED 8, RETURNED 1
    EXPECTED 8, RETURNED 8
    EXPECTED 0, RETURNED 0
    EXPECTED 6, RETURNED 4
    EXPECTED 6, RETURNED 6
    EXPECTED 1, RETURNED 3
    EXPECTED 6, RETURNED 2
    EXPECTED 3, RETURNED 3
    EXPECTED 0, RETURNED 1
    gross execution time per inference (including img load): 3328624
    ```
- **Speed**
    - clock cycles per inference: 3328624
    - max frequency: 80 MHz
    - inferences per second: 24.03

- **Memory requirements**

    | component         | datapoints     | width [bit] | kB |
    |--------------|-----------|------------| --- |
    | image | 3072      | 32        | 12
    | layer 0 weights      | 576  | 8       | 0.563
    | layer 0 biases      | 16  | 32       | 0.0625
    | layer 2 weights      | 3072  | 8       | 3
    | layer 2 biases      | 16  | 32       | 0.0625
    | layer 4 weights      | 36864  | 8       | 36
    | layer 4 biases      | 64  | 32       | 0.25
    | layer 5 weights      | 768  | 8       | 0.75
    | layer 5 biases      | 12  | 32       | 0.0469
    | Ms      | 36  | 32       | 1.13
    | **sum** | | | **53.8**

- **Accuracy**
64%

## Synthesis Report

Flow Status	Successful - Thu Nov 18 11:33:23 2021

Quartus Prime Version	19.1.0 Build 670 09/22/2019 Patches 0.02i SJ Lite Edition

Revision Name	patmos

Top-level Entity Name	patmos_top

Family	Cyclone IV E

Device	EP4CE115F29C7

Timing Models	Final

Total logic elements	25,062 / 114,480 ( 22 % )

Total registers	7597

Total pins	57 / 529 ( 11 % )

Total virtual pins	0

Total memory bits	1,195,200 / 3,981,312 ( 30 % )

Embedded Multiplier 9-bit elements	101 / 532 ( 19 % )

Total PLLs	1 / 4 ( 25 % )