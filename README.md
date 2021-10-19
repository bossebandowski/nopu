# nopu

## Release Notes v0.1

Minor updates to the first release: A simple 784x100x12 neural network with two fully connected layers that can identify MNIST handwritten digits.
- weights are 8 bit wide (hence greatly reduced model size)
- added two neurons to output layer to allow for OCP burst-aligned inference
- no parallelism has been exploited
- io completely absent
- issues regarding repeated inference have been fixed
- started to move towards scalable design for adding more layers

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
gross execution time per inference (including img load): 1835554
```
- **Speed**
    - clock cycles per inference: 1835554
    - max frequency: 80 MHz
    - inferences per second: 43.6

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
97.8%

## Synthesis Report
Not yet synthesized