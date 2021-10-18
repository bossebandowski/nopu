
// #include <machine/patmos.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <stdio.h>

#include <counter.h>
#include <cop.h>
#include <parameters.h>
#include <images.h>

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
    printf("the first weight of the 1st layer %hhd is stored at address %u\n", weights_1[0], w1p);
    printf("the first weight of the 2nd layer %hhd is stored at address %u\n", weights_2[0], w2p);
    printf("the first bias of the 1st layer %ld is stored at address %u\n", biases_1[0], b1p);
    printf("the first bias of the 2nd layer %ld is stored at address %u\n", biases_2[0], b2p);
    printf("the first pixel of the first image %ld is stored at address %u\n", img_0[0], imgp);
}

void load_img(const int32_t img[], int size)
{
    int *im_addr_0 = (int *)30;
    memcpy(im_addr_0, img, size);
}

void read_inputs()
{
    cop_mem_r(1000000);
    printf("weight 0[0]:\t\t%lx\n", cop_get_res());
    cop_mem_r(1000000 + 78399);
    printf("weight 0[78399]:\t%lx\n", cop_get_res());
    cop_mem_r(1320000);
    printf("weight 1[0]:\t\t%lx\n", cop_get_res());
    cop_mem_r(1320000 + 4);
    printf("weight 1[1]:\t\t%lx\n", cop_get_res());
    cop_mem_r(1320000 + 999);
    printf("weight 1[999]:\t\t%lx\n", cop_get_res());
    cop_mem_r(1325000);
    printf("bias 0[0]:\t\t%lx\n", cop_get_res());
    cop_mem_r(1326000);
    printf("bias 1[0]:\t\t%lx\n", cop_get_res());
    cop_mem_r(30);
    printf("img [0]:\t\t%lx\n", cop_get_res());
}

void read_weights() {
    for (int i = 1000000; i < 1000020; i++) {
        cop_mem_r(i);
        printf("%d:\t\t%lx\n", i, cop_get_res());
    }
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

int run_inf(const int32_t img[], int size) {
    load_img(img, size);
    cop_run();
    return cop_get_res();
}

int main(int argc, char **argv)
{
    // load nn parameters into desired memory space. In the future, this will be copying from flash to sram
    load_nn();

    int res;
    int hwExecTime;
    int size = 784*4;

    // reset the count
    cntReset();

    // run single inference
    res = run_inf(img_5, size);
    hwExecTime = cntRead();

    printf("inference result img_5: %d\n", res);

    printf("gross execution time per inference (including img load): %d\n", hwExecTime);
    return 0;
}
