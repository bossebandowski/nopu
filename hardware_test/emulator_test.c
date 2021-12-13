#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>

#include <counter.h>
#include <images.h>
#include <network-configs.h>

const uint32_t IMAGE_LEN = 3072;
int32_t img[IMAGE_LEN];

void load_img()
{
    cop_config(0, 7, &img[0]);
}

void read_raw_outputs()
{
    for (int i = 0; i < 10; i++)
    {
        cop_mem_r(i);
        printf("%d: %lx\n", i, cop_get_res());
    }
}

void print_intermediate_layer_head(bool even, int offset, int count)
{
    for (int i = 0; i < count; i++)
    {
        if (even) {
            cop_mem_r(i + 16384 + offset);
        }
        else {
            cop_mem_r(i + offset);
        }

        printf("%d: %ld\n", i + offset, cop_get_res());
    }
}

int run_inf() {
    cop_run();
    return cop_get_res();
}

void run_emulator() {
    // load nn parameters into desired memory space. In the future, this will hopefully be copying from flash to sram
    int res;
    int hwExecTime;
    load_nn_cifar_10();
    load_img();

    for (int id = 0; id < 1; id++) {
        for (int idx = 0; idx < IMAGE_LEN; idx ++) {
            img[idx] = images[id][idx];
        }
        cntReset();
        res = run_inf();
        hwExecTime = cntRead();
        printf("EXPECTED %x, RETURNED %u\n", results[id], res);
    }

    printf("================================\n");
    printf("gross execution time per inference (including img load): %d\n", hwExecTime);

    // print_intermediate_layer_head(true, 0, 100);
}

int main(int argc, char **argv)
{
    run_emulator();

    return 0;
}
