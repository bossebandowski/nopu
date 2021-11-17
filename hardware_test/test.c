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
    int *w3p = (int *)1300000;

    int *b0p = (int *)1500000;
    int *b1p = (int *)1501000;
    int *b2p = (int *)1502000;
    int *b3p = (int *)1503000;

    int *m0p = (int *)1600000;
    int *m1p = (int *)1601000;
    int *m2p = (int *)1602000;

    // copy arrays to target memory space
    memcpy(w0p, param_7_w_conv, sizeof(param_7_w_conv));
    memcpy(w1p, param_10_w_conv, sizeof(param_10_w_conv));
    memcpy(w2p, param_14_w_fc, sizeof(param_14_w_fc));
    memcpy(w3p, param_16_w_fc, sizeof(param_16_w_fc));

    memcpy(b0p, param_2_b, sizeof(param_2_b));
    memcpy(b1p, param_3_b, sizeof(param_3_b));
    memcpy(b2p, param_4_b, sizeof(param_4_b));
    memcpy(b3p, param_5_b, sizeof(param_5_b));

    memcpy(m0p, ms_0, sizeof(ms_0));
    memcpy(m1p, ms_1, sizeof(ms_1));
    memcpy(m2p, ms_2, sizeof(ms_2));

}

void load_img(const int32_t img[], int size)
{
    int *im_addr_0 = (int *)40;
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
        cop_mem_r(i);
        printf("%d: %lx\n", i, cop_get_res());
    }
}

void print_intermediate_layer_head()
{
    for (int i = 0; i < 100; i++)
    {
        cop_mem_r(i + 16384);
        printf("%d: %ld\n", i, cop_get_res());
    }
}

int run_inf(const int32_t img[], int size) {
    load_img(img, size);
    cop_run();
    return cop_get_res();
}

int main(int argc, char **argv)
{
    // load nn parameters into desired memory space. In the future, this will hopefully be copying from flash to sram
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

    // print_intermediate_layer_head();
    // read_raw_outputs();

    printf("gross execution time per inference (including img load): %d\n", hwExecTime);
    printf("================================\n");
    return 0;
}
