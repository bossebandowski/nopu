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
    int *w0p = (int *)1000000;
    int *w1p = (int *)1100000;
    int *w2p = (int *)1200000;
    int *b0p = (int *)1300000;
    int *b1p = (int *)1310000;
    int *b2p = (int *)1320000;

    // copy arrays to target memory space
    memcpy(w0p, param_2_w_fc, sizeof(param_2_w_fc));
    memcpy(w1p, param_4_w_fc, sizeof(param_4_w_fc));
    memcpy(w2p, param_6_w_fc, sizeof(param_6_w_fc));
    memcpy(b0p, param_3_b, sizeof(param_3_b));
    memcpy(b1p, param_5_b, sizeof(param_5_b));
    memcpy(b2p, param_7_b, sizeof(param_7_b));
}

void print_default_locations()
{
    int w1p = (int)&param_2_w_fc[0];
    int w2p = (int)&param_4_w_fc[0];
    int b1p = (int)&param_3_b[0];
    int b2p = (int)&param_5_b[0];
    int imgp0 = (int)&images[0][0];
    int imgpn = (int)&images[9][783];

    printf("the first weight of the 1st layer %hhd is stored at address %u\n", param_2_w_fc[0], w1p);
    printf("the first weight of the 2nd layer %hhd is stored at address %u\n", param_4_w_fc[0], w2p);
    printf("the first bias of the 1st layer %ld is stored at address %u\n", param_3_b[0], b1p);
    printf("the first bias of the 2nd layer %ld is stored at address %u\n", param_5_b[0], b2p);
    printf("the first pixel of the first image %ld is stored at address %u\n", images[0][0], imgp0);
    printf("the last pixel of the last image %ld is stored at address %u\n", images[9][783], imgpn);

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
    for (int i = 1000000; i < 1000020; i+=4) {
        cop_mem_r(i);
        printf("%d:\t\t%lx\n", i, cop_get_res());
    }
}

void read_raw_outputs()
{
    for (int i = 0; i < 10; i++)
    {
        cop_mem_r(30000 + i * 4);
        printf("%d: %lx\n", i, cop_get_res());
    }
}

void print_intermediate_layer_head(int layer)
{
    for (int i = 0; i < 20; i++)
    {
        cop_mem_r(10000 * (layer + 1) + i * 4);
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
    printf("Loading network...");
    load_nn();
    printf("done\n");

    int res;
    int hwExecTime;
    int size = 784*4;

    for (int id = 0; id < 10; id++) {
        // reset the count
        cntReset();

        // run single inference
        res = run_inf(images[id], size);
        hwExecTime = cntRead();

        printf("EXPECTED %d, RETURNED %d\n", results[id], res);
    }

    printf("gross execution time per inference (including img load): %d\n", hwExecTime);
    printf("================================\n");
    
    return 0;
}
