
// #include <machine/patmos.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <stdio.h>
#include "accelerator/parameters.h"
#include "counter.h"

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

int cop_get_res()
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
    int *w1p = (int *)900;
    int *w2p = (int *)80000;
    int *b1p = (int *)82000;
    int *b2p = (int *)83000;

    // copy arrays to target memory space
    memcpy(w1p, weights_1, sizeof(weights_1));
    memcpy(w2p, weights_2, sizeof(weights_2));
    memcpy(b1p, biases_1, sizeof(biases_1));
    memcpy(b2p, biases_2, sizeof(biases_2));
}

void nn_check()
{

    int weights_base_address = 900;
    int biases_base_address = 82000;

    int weight_ids[5] = {0, 100, 1000, 10000, 20000};
    int bias_ids[5] = {0, 25, 50, 75, 100};

    int rsp = 0;
    for (int i = 0; i < 5; i++)
    {
        // cop_mem_r((weight_ids[i]) + weights_base_address);
        cop_mem_r((weight_ids[i] << 2) + weights_base_address);
        rsp = cop_get_res();
        printf("Expected: %d, Read: %d \n", weights_1[i], rsp);

        // cop_mem_r((bias_ids[i]) + biases_base_address);
        cop_mem_r((bias_ids[i] << 2) + biases_base_address);
        rsp = cop_get_res();
        printf("Expected: %ld, Read: %d \n", biases_1[i], rsp);
    }

    printf("==================\n");
    printf("network check done\n");
    printf("==================\n");
}

void load_nn_test()
{
    printf("starting network load test...\n");
    printf("resetting cop...\n");
    cop_reset();
    printf("loading network...\n");
    load_nn();
    printf("done.\nVerifying...\n");
    nn_check();
}

void dumpit()
{
    for (int a = 790; a < 820; a++)
    {
        cop_mem_r(a);
        printf("%d : %x\n", a, cop_get_res());
    }
}

void memory_test()
{
    cop_mem_w(800, 0x11223344);
    dumpit();

    cop_mem_w(804, 0x55667788);
    dumpit();

    cop_mem_w(808, 0x99aabbcc);
    dumpit();

    cop_mem_w(812, 0xddeeff00);
    dumpit();
}

void pat_acc_mem_test()
{
    for (int i = 796; i < 813; i += 4)
    {
        // set mem value in patmos
        *(uint32_t *)i = i;
    }
    // read with accelerator and print
    dumpit();
}

void print_default_locations()
{
    int w1p = (int)&weights_1[0];
    int w2p = (int)&weights_2[0];
    int b1p = (int)&biases_1[0];
    int b2p = (int)&biases_2[0];
    int imgp = (int)&img_0_1[0];
    printf("the first weight of the 1st layer %d is stored at address %u\n", weights_1[0], w1p);
    printf("the first weight of the 2nd layer %d is stored at address %u\n", weights_2[0], w2p);
    printf("the first bias of the 1st layer %ld is stored at address %u\n", biases_1[0], b1p);
    printf("the first bias of the 2nd layer %ld is stored at address %u\n", biases_2[0], b2p);
    printf("the first pixel of the first image %d is stored at address %u\n", img_0_1[0], imgp);
}

void load_img(int id)
{
    int *img = (int *)30;
    memcpy(img, img_0_1, sizeof(img_0_1));
}

int main(int argc, char **argv)
{
    // load nn parameters into desired memory space. In the future, this will be copying from flash to sram
    load_nn();

    // load image into desired memory space. In the future, this will come from the host io interface
    load_img(0);

    // reset cop and start inference
    cop_reset();
    cntReset();
    cop_run();
    int res = cop_get_res();
    int hwExecTime = cntRead();
    printf("=================\nresult: %d\ncycles: %d\n=================\n", res, hwExecTime);
    return 0;
}
