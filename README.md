# nopu

## Release Notes v1.5

- Still cifar10 dataset (image classification on small 3-channel input images. Input shape (32x32x3))
- model architecture has not changed since release 1.3
- established ethernet communication channel between host machine and FPGA
- host sends input images and the FPGA returns the inference results
- started implementing basic handshakes on top of UDP. FPGA acknowledges the arrival of image data, the host resends image chunks of ACK is missing
- split tests into emulator and hardware tests because the emulator is not capable of handling ethernet io

## Setup and Run

- Build patmos
follow the instructions on `https://github.com/t-crest/patmos`

- Clone this repo and use the scripts to build patmos plus the coprocessor and run a test script in the emulator or on actual hardware. Careful if you are developing in your `~/t-crest/patmos` folder, these scripts might overwrite some files.

1) Emulator
    ```
    chmod +x ./scripts/*.sh
    ./scripts/build_patemu.sh
    ./scripts/run_patemu.sh
    ```

2) Hardware
    - make sure to have quartus 19.1 installed on your host machine
    - connect an Altera DE2-115 board to your machine via the USB blaster cable and the serial COM cable
    - verify that the board can be programmed by running jtagconfig
        ```
        patmos@ubuntu:~/nopu$ jtagconfig
            1) USB-Blaster [2-2.3]                        
            020F70DD   10CL120(Y|Z)/EP3C120/..
        ```
    - make scripts executable
        ```
        chmod +x ./scripts/*.sh
        ```
    - generate verilog files and run synthesis
        ```
        ./scripts/build_fpga.sh
        ```
    - compile test script, program FPGA, and download program. Try again if the download fails
        ```
        ./scripts/run_fpga.sh
            ...
            [++++++++++] 251556/251556 bytes
            sent 256516 raw bytes compressed to 159337 bytes (62%)
            configuring network...done
            ready to rumble
        ```
    - on your host machine, adapt the following network settings:
        - SPEED 100000
        - AUTONEG OFF
        - FULL DUPLEX
        - IPv4 address: `192.168.24.45`
        - network mask: `255.255.255.0`
        - default gateway: `192.168.24.1`

    - verify that your host machine can communicate with the FPGA:
        ```
        ping 192.168.24.50
        Reply from 192.168.24.50: bytes=32 time=1ms TTL=128
        ```
        if this does not work, try to update your network settings or reprogram the FPGA
    - create a venv and install requirements
        ```
        python3 -m venv venv
        # windows
        venv\Scripts\activate
        # linux
        source venv/bin/activate

        pip install -U pip cython setuptools wheel
        pip install -r model_prep/requirements.txt
        ```
    - run inference script to send test images to the FPGA and evaluate the output
        ```
        cd python-nopu
        python run_inf.py
        ```

## Results


- **Emulator output**
    ```
    EXPECTED 3, RETURNED 3
    EXPECTED 8, RETURNED 8
    EXPECTED 8, RETURNED 8
    EXPECTED 0, RETURNED 0
    EXPECTED 6, RETURNED 4
    EXPECTED 6, RETURNED 6
    EXPECTED 1, RETURNED 1
    EXPECTED 6, RETURNED 6
    EXPECTED 3, RETURNED 3
    EXPECTED 0, RETURNED 1
    ================================
    gross execution time per inference (including img load): 3216962
    ```

- **Hardware output (on host console)**
    ```
    expected CAT, got CAT
    expected SHIP, got SHIP
    expected SHIP, got SHIP
    expected AIRPLANE, got AIRPLANE
    expected FROG, got DEER
    expected FROG, got FROG
    expected AUTOMOBILE, got AUTOMOBILE
    expected FROG, got FROG
    expected CAT, got CAT
    expected AUTOMOBILE, got AUTOMOBILE
    accuracy: 0.9
    average time: 0.06239337921142578
    ```
- **Speed**
    - clock cycles per inference (net): 3216974
    - seconds per inference (gross, including communication with host): 0.06s (can vary due to communication delays)
    - max frequency: 80 MHz
    - inferences per second: 24.87

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