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
    int *b0p = (int *)1300000;
    int *b1p = (int *)1310000;

    // copy arrays to target memory space
    memcpy(w0p, param_2_w_conv, sizeof(param_2_w_conv));
    memcpy(w1p, param_4_w_fc, sizeof(param_4_w_fc));
    memcpy(b0p, param_3_b, sizeof(param_3_b));
    memcpy(b1p, param_5_b, sizeof(param_5_b));
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
        cop_mem_r(30000 + i * 4);
        printf("%d: %lx\n", i, cop_get_res());
    }
}

void print_intermediate_layer_head()
{
    for (int i = 0; i < 100; i++)
    {
        cop_mem_r(16384 + 2100 + i);
        printf("%d: %ld\n", i + 2100, cop_get_res());
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

    printf("gross execution time per inference (including img load): %d\n", hwExecTime);
    printf("================================\n");
    return 0;
}
