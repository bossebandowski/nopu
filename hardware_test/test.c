
// #include <machine/patmos.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <stdio.h>
#include "accelerator/parameters.h"
#include <counter.h>

void cop_reset()
{
    asm(".word 0x3400001"); // unpredicated COP_WRITE to COP0 with FUNC = 00000, RA = 00000, RB = 00000
}

void cop_busy_wait()
{
    register uint32_t state __asm__("18") = 1;
    while (state)
    {
        //printf("state: %lu\n", state);
        asm(".word 0x3640083" // unpredicated COP_READ to COP0 with FUNC = 00001, RA = 00000, RD = 10010
            : "=r"(state)
            :
            : "18");
    }
    //printf("state: %lu\n", state);
}

void cop_run()
{
    asm(".word 0x3440001"); // unpredicated COP_WRITE to COP0 with FUNC = 00010, RA = 00000, RB = 00000
    cop_busy_wait();
}

int32_t cop_get_res()
{
    register uint32_t result __asm__("19") = 11;
    asm(".word 0x3660203"
        : "=r"(result)
        :
        : "19");

    // pref     01101
    // regD     10011
    // regA     00000
    // func     00100
    // post     0000011

    return result;
}

void cop_mem_w(int addr, int val)
{
    register uint32_t addrReg __asm__("16") = addr;
    register uint32_t valReg __asm__("17") = val;

    asm(".word 0x3470881"
        :
        : "r"(addrReg), "r"(valReg));

    // pref     01101
    // func     00011
    // regA     10000
    // regB     10001
    // post     0000001

    cop_busy_wait();
}

void cop_mem_r(int addr)
{
    register uint32_t addrReg __asm__("16") = addr;

    asm(".word 0x34B0001"
        :
        : "r"(addrReg));

    // pref     01101
    // func     00101
    // regA     10000
    // regB     00000
    // post     0000001

    cop_busy_wait();
}

void load_nn()
{
    // set fixed address pointers (starting indices of arrays)
    int *w1p = (int *)1000000;
    int *w2p = (int *)1320000;
    int *b1p = (int *)1325000;
    int *b2p = (int *)1326000;

    // copy arrays to target memory space
    memcpy(w1p, weights_1, sizeof(weights_1));
    memcpy(w2p, weights_2, sizeof(weights_2));
    memcpy(b1p, biases_1, sizeof(biases_1));
    memcpy(b2p, biases_2, sizeof(biases_2));
}

void print_default_locations()
{
    int w1p = (int)&weights_1[0];
    int w2p = (int)&weights_2[0];
    int b1p = (int)&biases_1[0];
    int b2p = (int)&biases_2[0];
    int imgp = (int)&img_0[0];
    printf("the first weight of the 1st layer %ld is stored at address %u\n", weights_1[0], w1p);
    printf("the first weight of the 2nd layer %ld is stored at address %u\n", weights_2[0], w2p);
    printf("the first bias of the 1st layer %ld is stored at address %u\n", biases_1[0], b1p);
    printf("the first bias of the 2nd layer %ld is stored at address %u\n", biases_2[0], b2p);
    printf("the first pixel of the first image %ld is stored at address %u\n", img_0[0], imgp);
}

void load_img()
{
    int *img = (int *)30;
    memcpy(img, img_5, sizeof(img_5));
}

void read_inputs()
{
    cop_mem_r(1000000);
    printf("weight 0[0]:\t%ld\n", cop_get_res());
    cop_mem_r(1000000 + 4 * 78399);
    printf("weight 0[78399]:\t%ld\n", cop_get_res());
    cop_mem_r(1320000);
    printf("weight 1[0]:\t%ld\n", cop_get_res());
    cop_mem_r(1320000 + 4 * 1);
    printf("weight 1[1]:\t%ld\n", cop_get_res());
    cop_mem_r(1320000 + 4 * 999);
    printf("weight 1[999]:\t%ld\n", cop_get_res());
    cop_mem_r(1325000);
    printf("bias 0[0]:\t%ld\n", cop_get_res());
    cop_mem_r(1326000);
    printf("bias 1[0]:\t%ld\n", cop_get_res());
    cop_mem_r(30);
    printf("img [0]:\t%ld\n", cop_get_res());
}

void read_raw_outputs()
{
    for (int i = 0; i < 10; i++)
    {
        cop_mem_r(20000 + i * 4);
        printf("%d: %lx\n", i, cop_get_res());
    }
}

void print_intermediate_layer_head()
{
    for (int i = 0; i < 20; i++)
    {
        cop_mem_r(10000 + i * 4);
        printf("%d: %lx\n", i, cop_get_res());
    }
}

int main(int argc, char **argv)
{
    // load nn parameters into desired memory space. In the future, this will be copying from flash to sram
    load_nn();

    // load image into desired memory space. In the future, this will come from the host io interface
    load_img();

    // reset cop and start inference
    cop_reset();
    cntReset();

    cop_run();
    int res = cop_get_res();
    int hwExecTime = cntRead();

    printf("=================\n"
           "result: %d\n"
           "cycles: %d\n"
           "=================\n",
           res, hwExecTime);

    read_raw_outputs();

    return 0;
}
